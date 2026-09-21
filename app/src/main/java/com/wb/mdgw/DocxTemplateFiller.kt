package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * 文书模板占位符填充引擎（对应 PC 端 python-docx 脚本的核心能力）。
 *
 * 与 [DocxInPlace]（按"块模型 + 编辑位置"原位回填）不同，本引擎做**通用 key→value
 * 占位符查找替换**：
 *  - 直接以原始 .docx 为模板，解包 zip 后保留每一个部件（样式 / 页面 / 图片 / 关系），
 *    只改 `word/document.xml` 与 `word/header*.xml`、`footer*.xml` 的文字；
 *  - 占位符可能被 Word 拆到多个 run，引擎先把段落所有 run 文本拼成全文再定位，
 *    回填时按原 run 边界「格式 run 锁长、无格式 run 吸收增减」，完整保留字体 / 字号 /
 *    粗斜体 / 下划线 / 颜色；
 *  - 表格（含嵌套）单元格内的段落同样替换；表格行允许跨页断行、设置最小行高 atLeast；
 *  - 值为空时，按**原占位符 key 的长度**填入等量空格，留白长度零变化；
 *  - 其余部件一字节不动，从根本上避免表格 / 排版变形。
 *
 * 纯 JVM 实现（java.util.zip + javax.xml），可在单元测试中直接运行。
 */
object DocxTemplateFiller {

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private val TEXTUAL = setOf("t", "tab", "br")

    /**
     * 用 [rules]（key → value）填充模板 [docx] 字节，返回新的 docx 字节。
     *
     * @param rules 替换规则；value 为空（或全空白）时按 key 长度补空格留白。
     * @throws IllegalArgumentException 不是有效 docx（缺少 word/document.xml）时抛出。
     */
    fun fill(docx: ByteArray, rules: Map<String, String>): ByteArray {
        // 1. 解包，保留全部部件
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                parts[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        if (!parts.containsKey("word/document.xml")) {
            throw IllegalArgumentException("不是有效的 Word 文档（缺少 word/document.xml）")
        }

        // 没有任何规则：原样返回
        if (rules.isEmpty()) return docx

        // 长键优先：构造 alternation，同一位置优先匹配更长的 key
        val sortedKeys = rules.keys.sortedByDescending { it.length }
        val pattern = Regex(sortedKeys.joinToString("|") { Regex.escape(it) })

        // 2. 处理正文与页眉页脚
        for (name in parts.keys.toList()) {
            if (isTextPart(name)) {
                val original = parts[name] ?: continue
                parts[name] = processXml(original, pattern, rules)
            }
        }

        // 3. 重新打包
        val bos = ByteArrayOutputStream(1 shl 18)
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 是否为需要做文字替换的 XML 部件 */
    private fun isTextPart(name: String): Boolean {
        if (name == "word/document.xml") return true
        if (name.startsWith("word/header") && name.endsWith(".xml")) return true
        if (name.startsWith("word/footer") && name.endsWith(".xml")) return true
        return false
    }

    /** 处理单个 XML 部件：替换所有段落 + 修正所有表格行高 */
    private fun processXml(bytes: ByteArray, pattern: Regex, rules: Map<String, String>): ByteArray {
        val dom = parseDom(bytes)
        val doc = dom.documentElement

        // 段落（正文、表格单元格、嵌套表格、页眉页脚均通过 getElementsByTagName 覆盖）
        val paragraphs = doc.getElementsByTagNameNS(NS_W, "p").toElementList()
        for (p in paragraphs) fillParagraph(p, pattern, rules)

        // 表格行：允许跨页 + 最小行高
        val rows = doc.getElementsByTagNameNS(NS_W, "tr").toElementList()
        for (tr in rows) fixTableRow(tr)

        val serialized = serialize(doc)
        val fixed = fixNamespacePrefix(serialized)
        return (XML_DECL + fixed).toByteArray(Charsets.UTF_8)
    }

    /**
     * 替换单个段落内的占位符；命中任意 key 返回 true。
     *
     * 关键：普通字符严格留在它原本所属的 run（格式边界不动），只有占位符区间被替换；
     * 替换值统一写入「占位符起始位置所在的 run」，因此长度增减只改变该 run，
     * 绝不会把相邻无格式字符（如占位符前的「：」）卷进下划线 / 粗体 run。
     */
    private fun fillParagraph(p: Element, pattern: Regex, rules: Map<String, String>): Boolean {
        // 段落内全部文本 run（含 hyperlink 等容器内的，按文档顺序）
        val runs = p.getElementsByTagNameNS(NS_W, "r")
            .toElementList()
            .filter { runHasText(it) }
        if (runs.isEmpty()) return false

        val texts = runs.map { runText(it) }
        // 每个 run 在拼接全文中的起止偏移
        val starts = IntArray(runs.size + 1)
        for (i in runs.indices) starts[i + 1] = starts[i] + texts[i].length
        val full = texts.joinToString("")
        if (full.isEmpty()) return false

        val matches = pattern.findAll(full).toList()
        if (matches.isEmpty()) return false

        // 每个 run 的新文本缓冲
        val out = Array(runs.size) { StringBuilder() }

        // 全局字符位置落在哪个 run（starts[i] <= pos < starts[i+1]）
        fun runIndexOf(pos: Int): Int {
            for (i in runs.indices) if (pos < starts[i + 1]) return i
            return runs.size - 1
        }

        // 把普通（非占位符）区间 [a,b) 的字符按原 run 边界原样写回各 run
        fun appendPlain(a0: Int, b0: Int) {
            if (a0 >= b0) return
            var a = a0
            var i = runIndexOf(a)
            while (a < b0) {
                val segEnd = minOf(b0, starts[i + 1])
                out[i].append(full.substring(a, segEnd))
                a = segEnd
                i++
            }
        }

        var cursor = 0
        for (m in matches) {
            val mStart = m.range.first
            val mEnd = m.range.last + 1
            // 占位符之前的普通文本：原样保留
            appendPlain(cursor, mStart)
            // 替换值：空值按 key 长度补空格；继承占位符起始 run 的格式
            val key = m.value
            val raw = rules[key].orEmpty()
            val value = if (raw.isBlank()) " ".repeat(key.length) else raw
            out[runIndexOf(mStart)].append(value)
            cursor = mEnd
        }
        // 末尾普通文本
        appendPlain(cursor, full.length)

        for (i in runs.indices) setRunText(runs[i], out[i].toString())
        return true
    }

    /**
     * 表格行：移除 cantSplit（允许跨页）；无 trHeight 则设最小行高 500 / atLeast，
     * 有则把 hRule 改为 atLeast（行高可随内容增长）。
     */
    private fun fixTableRow(tr: Element) {
        val doc = tr.ownerDocument
        val trPr = tr.child("trPr") ?: doc.createElementNS(NS_W, "w:trPr").also {
            tr.insertBefore(it, tr.firstChild)
        }
        // 移除禁止跨页断行
        trPr.childElements().filter { it.local() == "cantSplit" }.forEach { trPr.removeChild(it) }

        val heights = trPr.childElements().filter { it.local() == "trHeight" }
        if (heights.isEmpty()) {
            val h = doc.createElementNS(NS_W, "w:trHeight")
            h.setAttributeNS(NS_W, "w:val", "500")
            h.setAttributeNS(NS_W, "w:hRule", "atLeast")
            trPr.appendChild(h)
        } else {
            heights.forEach { it.setAttributeNS(NS_W, "w:hRule", "atLeast") }
        }
    }

    // ------------------------------------------------------------------
    // run 文本回填（移植自 DocxInPlace 已验证算法，保持本引擎自包含）
    // ------------------------------------------------------------------

    /** run 文本（w:t 文字 + w:tab→\t + w:br→\n） */
    private fun runText(run: Element): String {
        val sb = StringBuilder()
        for (c in run.childElements()) {
            when (c.local()) {
                "t" -> sb.append(c.textContent)
                "tab" -> sb.append('\t')
                "br" -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun runHasText(run: Element): Boolean =
        run.childElements().any { it.local() in TEXTUAL }

    /** 写 run 文本：保留 rPr，仅替换文字；\t / \n 还原为 w:tab / w:br；带 xml:space=preserve */
    private fun setRunText(run: Element, text: String) {
        val content = run.childElements().filter { it.local() in TEXTUAL }
        if (content.isEmpty()) return // 图片 / 绘图 / 域等承载型 run 绝不写入

        val needStructure = text.any { it == '\t' || it == '\n' } ||
            content.any { it.local() == "tab" || it.local() == "br" }
        if (!needStructure) {
            val texts = content.filter { it.local() == "t" }
            if (texts.isEmpty()) return
            texts.first().textContent = text
            for (i in 1 until texts.size) run.removeChild(texts[i])
            // 保证空格不被折叠
            texts.first().setAttributeNS(XML_NS, "xml:space", "preserve")
            return
        }

        val doc = run.ownerDocument
        var ref: Element? = run.child("rPr")
        for (o in content) run.removeChild(o)

        fun put(node: Element) {
            val r = ref
            if (r != null) {
                val next = r.nextSibling
                if (next != null) run.insertBefore(node, next) else run.appendChild(node)
            } else {
                val first = run.firstChild
                if (first != null) run.insertBefore(node, first) else run.appendChild(node)
            }
            ref = node
        }

        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\t' -> { put(doc.createElementNS(NS_W, "w:tab")); i++ }
                '\n' -> { put(doc.createElementNS(NS_W, "w:br")); i++ }
                else -> {
                    var j = i
                    while (j < text.length && text[j] != '\t' && text[j] != '\n') j++
                    val t = doc.createElementNS(NS_W, "w:t")
                    t.setAttributeNS(XML_NS, "xml:space", "preserve")
                    t.textContent = text.substring(i, j)
                    put(t)
                    i = j
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DOM / zip 辅助
    // ------------------------------------------------------------------

    private fun parseDom(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    private fun serialize(el: Element): String {
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.setOutputProperty(OutputKeys.METHOD, "xml")
        val sw = StringWriter()
        tf.transform(DOMSource(el), StreamResult(sw))
        return sw.toString()
    }

    /** 修复部分 Android Transformer 把 w: 前缀改成 ns1: / 默认命名空间的问题（移植自 DocxInPlace） */
    private fun fixNamespacePrefix(xml: String): String {
        val nsUri = NS_W
        if (xml.contains(" xmlns:w=\"") || xml.contains(" xmlns:W=\"") ||
            xml.contains("<w:") || xml.contains("</w:")
        ) {
            return xml
        }
        val prefixRegex = Regex("xmlns:(\\w+)=\"" + Regex.escape(nsUri) + "\"")
        val match = prefixRegex.find(xml)
        if (match != null) {
            val autoPrefix = match.groupValues[1]
            if (autoPrefix != "w") {
                return xml
                    .replace(":$autoPrefix=", ":w=")
                    .replace(":$autoPrefix ", ":w ")
                    .replace("<$autoPrefix:", "<w:")
                    .replace("</$autoPrefix:", "</w:")
            }
        }
        val defaultNsRegex = Regex("xmlns=\"" + Regex.escape(nsUri) + "\"")
        if (defaultNsRegex.containsMatchIn(xml)) {
            var fixed = xml.replaceFirst("xmlns=\"$nsUri\"", "xmlns:w=\"$nsUri\"")
            val tagRegex = Regex("<(/)?([a-zA-Z][a-zA-Z0-9]*)([\\s>:])")
            fixed = tagRegex.replace(fixed) { mr ->
                val slash = mr.groupValues[1]
                val tag = mr.groupValues[2]
                val after = mr.groupValues[3]
                if (after == ":" || tag in setOf("xml", "?xml")) mr.value
                else "<${slash}w:$tag$after"
            }
            return fixed
        }
        return xml
    }

    private fun NodeList.toElementList(): List<Element> {
        val list = mutableListOf<Element>()
        for (i in 0 until length) {
            val n = item(i)
            if (n is Element) list += n
        }
        return list
    }

    private fun Element.local(): String {
        val tag = tagName
        val idx = tag.indexOf(':')
        return if (idx >= 0) tag.substring(idx + 1) else tag
    }

    private fun Element.attr(name: String): String? {
        getAttributeNode(name)?.value?.let { return it }
        val local = name.local()
        getAttributeNode(local)?.value?.let { return it }
        return null
    }

    private fun Element.child(tag: String): Element? =
        childElements().firstOrNull { it.local() == tag.local() }

    private fun Element.childElements(): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) list += n
        }
        return list
    }

    private fun String.local(): String {
        val idx = indexOf(':')
        return if (idx >= 0) substring(idx + 1) else this
    }
}
