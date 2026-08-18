package com.wb.mdgw

import android.util.Log
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

/**
 * 打开 Word 文档后的「原位修改」导出器。
 *
 * 与 [DocxWriter]（从模型整篇重建 Word）不同，这里**直接以原始 .docx 为模板**：
 *  - 解包后保留 zip 内每一个部件（样式 / 页眉页脚 / 文档属性 / 关系等），一个字节都不动；
 *  - 仅对 [edits] 集合中标记的段落 / 表格单元格，在原始 `document.xml` 的 DOM 上
 *    把文字替换为新内容（沿用该位置原有的 run 样式）；
 *  - 未编辑的节点原样保留，因此**表格的列宽、合并单元格、边框、单元格对齐，乃至
 *    整个文档的页面 / 样式 / 页眉页脚全部 100% 不变形**。
 *
 * 这是从架构上根治「Word 往返后表格变形」的唯一彻底办法：
 * 重建必然丢失细节，原位修改则根本不去碰它。
 */
object DocxInPlace {

    /**
     * @param original 打开的源 Word 字节（[GovDoc.originalDocx]）
     * @param blocks   当前（可能已被用户编辑过文字的）块模型
     * @param edits    用户编辑过的位置；仅这些位置会被替换，其余原样
     */
    fun edit(original: ByteArray, blocks: List<Block>, edits: Set<EditTarget>): ByteArray {
        Log.d("DocxInPlace", "edit: blocks=${blocks.size}, edits=${edits.size}")
        // 没有任何编辑：原样返回源文件，字节级完全一致（最彻底保真）
        if (edits.isEmpty()) {
            Log.w("DocxInPlace", "edit: edits is empty, returning original bytes unchanged")
            return original
        }

        // 1. 解包，保留全部部件
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(original)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                parts[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        val docBytes = parts["word/document.xml"]
            ?: error("不是有效的 Word 文档（缺少 document.xml）")

        // 2. DOM 解析（与 DocxReader 同样的非命名空间模式，前缀 w: 原样保留）
        val dom = parseDom(docBytes)
        val docEl = dom.documentElement
        val body = docEl.childElements().firstOrNull { it.local() == "body" }
            ?: error("文档结构异常：缺少 body")

        // 3. 按 body 中 p / tbl 的顺序与 GovDoc.blocks 对齐（混合序列，单一 bIdx）
        //    GovDoc.blocks 中段落与表格共用一个从 0 开始的索引，因此这里也用统一计数。
        //    每个位置可能有「整段 / 整格」编辑（runIndex<0，向后兼容旧记录）或
        //    「run 级」编辑（runIndex>=0）——后者直接定位原始 run 写入，保留其各自 rPr，
        //    从而让带下划线 / 粗斜体的片段成为独立可编辑单元（字段跟着变长、互不干扰）。
        var bIdx = 0
        var appliedCount = 0
        for (child in body.childElements()) {
            when (child.local()) {
                "p" -> {
                    val paraTargets = edits.filter { it.blockIndex == bIdx && it.row < 0 }
                    val para = blocks.getOrNull(bIdx)
                    if (paraTargets.isNotEmpty() && para is Block.Para) {
                        if (paraTargets.any { it.runIndex < 0 }) {
                            // 整段编辑：保留既有 distributeRespectingFormat 逻辑（向后兼容）
                            replaceParaText(child, para)
                            appliedCount++
                        } else {
                            // run 级编辑：逐 run 定位原始 run 直接写入，保留各自 rPr
                            val runs = child.childElements().filter { it.local() == "r" }
                            var runApplied = 0
                            for (t in paraTargets) {
                                val run = runs.getOrNull(t.runIndex)
                                val newText = para.runs.getOrNull(t.runIndex)?.text
                                if (run != null && newText != null) {
                                    setRunText(run, newText)
                                    runApplied++
                                }
                            }
                            if (runApplied > 0) appliedCount++
                            Log.d("DocxInPlace", "edit: block[$bIdx] PARA, targets=${paraTargets.size}, runs=${runs.size}, applied=$runApplied")
                        }
                    }
                    bIdx++
                }
                "tbl" -> {
                    val table = blocks.getOrNull(bIdx) as? Block.Table
                    var r = 0
                    var cellApplied = 0
                    for (tr in child.childElements()) {
                        if (tr.local() != "tr") continue
                        var c = 0
                        for (tc in tr.childElements()) {
                            if (tc.local() != "tc") continue
                            val cellTargets = edits.filter {
                                it.blockIndex == bIdx && it.row == r && it.col == c
                            }
                            if (table != null && cellTargets.isNotEmpty()) {
                                val cell = table.rows.getOrNull(r)?.getOrNull(c)
                                if (cell != null) {
                                    if (cellTargets.any { it.runIndex < 0 }) {
                                        replaceCellText(tc, cell)
                                        cellApplied++
                                    } else {
                                        val firstP = tc.childElements().firstOrNull { it.local() == "p" }
                                        val runs = firstP?.childElements()?.filter { it.local() == "r" }
                                            ?: emptyList()
                                        for (t in cellTargets) {
                                            val run = runs.getOrNull(t.runIndex)
                                            val newText = cell.getOrNull(t.runIndex)?.text
                                            if (run != null && newText != null) {
                                                setRunText(run, newText)
                                                cellApplied++
                                            }
                                        }
                                    }
                                }
                            }
                            c++
                        }
                        r++
                    }
                    if (cellApplied > 0) appliedCount++
                    Log.d("DocxInPlace", "edit: block[$bIdx] TABLE, rows=$r, cellsApplied=$cellApplied")
                    bIdx++
                }
                // sectPr 等其它节点原样不动
            }
        }
        Log.d("DocxInPlace", "edit: total blocks processed=$bIdx, applied=$appliedCount")

        // 4. 用标准 Transformer 序列化（正确处理命名空间 / w: 前缀绑定），其余部件原样写回
        val serialized = serialize(docEl)
        Log.d("DocxInPlace", "edit: serialized document.xml, checking namespace prefixes...")
        // 确保序列化后的 XML 保留了 w: 命名空间前缀（某些 Transformer 实现可能用 ns1: 等自动前缀）
        val fixed = fixNamespacePrefix(serialized)
        parts["word/document.xml"] = (XML_DECL + fixed).toByteArray(Charsets.UTF_8)
        Log.d("DocxInPlace", "edit: done, writing ${parts.size} zip entries")
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

    /** 段落：保留原始 run 结构与各自样式（含下划线/粗斜体），仅把整段新文字按原始各 run 长度比例回填 */
    private fun replaceParaText(p: Element, para: Block.Para) {
        val newText = para.runs.joinToString("") { it.text }
        val runs = p.childElements().filter { it.local() == "r" }
        // 只把文字分配到「含 w:t 的文字 run」；图片 / 绘图 run（无 w:t）原样保留，绝不写入文字
        val textRuns = runs.filter { runHasText(it) }
        if (textRuns.isEmpty()) {
            if (newText.isNotEmpty()) appendRunWithText(p, newText, anchorAfter = p.child("w:pPr"))
            return
        }
        replaceRunsText(textRuns, newText)
    }

    /** 单元格：保留首段落内原始 run 结构与各自样式，仅回填新文字；其余段落原样不动 */
    private fun replaceCellText(tc: Element, cell: List<TextRun>) {
        val newText = cell.joinToString("") { it.text }
        val ps = tc.childElements().filter { it.local() == "p" }.toMutableList()
        if (ps.isEmpty()) {
            val p = tc.ownerDocument.createElementNS(NS_W, "w:p")
            appendRunWithText(p, newText, anchorAfter = null)
            tc.appendChild(p)
            return
        }
        val firstP = ps.first()
        val runs = firstP.childElements().filter { it.local() == "r" }
        // 只分配文字给含 w:t 的 run；图片 / 绘图 run 原样保留
        val textRuns = runs.filter { runHasText(it) }
        if (textRuns.isEmpty()) {
            if (newText.isNotEmpty()) appendRunWithText(firstP, newText, anchorAfter = firstP.child("w:pPr"))
        } else {
            replaceRunsText(textRuns, newText)
        }
    }

    /**
     * 把 newText 回填到原始的各个 run，但**带格式（下划线 / 粗体 / 斜体）的 run 保持
     * 其原始字符长度不变**，新文字的增减全部由「无格式 run」吸收。
     *
     * 这样可根除一个瑕疵：若按等比切分，当带下划线的 run 不在首尾且新文字长度变化时，
     * 相邻「本无下划线」的文字会被划进下划线 run 的份额，导致附近文字也被误加上下划线。
     * 改为「格式 run 锁长、无格式 run 吸收余量」后，无下划线的文字绝不会落到下划线 run 中。
     * 每个 run 的 rPr（下划线 / 粗斜体 / 字体 / ​字号）始终原样保留，仅文字内容更新。
     */
    private fun replaceRunsText(runs: List<Element>, newText: String) {
        if (newText.isEmpty()) {
            for (run in runs) clearRunText(run)
            return
        }
        val lens = runs.map { runTextLen(it) }
        val counts = distributeRespectingFormat(lens, runs.map { runHasFormatting(it) }, newText.length)
        var cursor = 0
        for (i in runs.indices) {
            val end = (cursor + counts[i]).coerceAtMost(newText.length)
            setRunText(run = runs[i], text = newText.substring(cursor, end))
            cursor = end
        }
        // 极端情形：仍有剩余文字（格式 run 全锁且总长不足），追加到最后一个 run
        if (cursor < newText.length) {
            val tail = newText.substring(cursor)
            val last = runs.last()
            val cur = last.childElements().filter { it.local() == "t" }.firstOrNull()?.textContent ?: ""
            setRunText(run = last, text = cur + tail)
        }
    }

    /** 统计一个 run 的「模型文本长度」：w:t 文字 + 每个 w:tab/ w:br 各算 1，
     *  与 GovDoc 模型（\t / \n）一一对应，确保字符数分配不偏移。 */
    private fun runTextLen(run: Element): Int =
        run.childElements().sumOf { c ->
            when (c.local()) {
                "t" -> c.textContent.length
                "tab", "br" -> 1
                else -> 0
            }
        }

    /** run 是否承载纯文本（含 w:t / w:tab / w:br 子元素）；图片 / 绘图 / 域等承载型 run 返回 false */
    private fun runHasText(run: Element): Boolean =
        run.childElements().any { it.local() in setOf("t", "tab", "br") }

    /**
     * 把 text 写入 run：保留其 rPr（下划线 / 粗斜体 / 字体 / 字号）与 run 的位置，仅替换文字内容。
     *
     * 文字中的 `\t` / `\n` 会还原为 `w:tab` / `w:br`——这是「打开 Word → 编辑 → 保存」后
     * 仍能保住原文制表位（含前导下划线 / 目录点线）与软换行的关键。
     * 不含制表符 / 换行时走快速路径，只改 w:t 的文本节点，对 DOM 扰动最小。
     */
    private fun setRunText(run: Element, text: String) {
        val content = run.childElements().filter { it.local() in TEXTUAL }
        // 图片 / 绘图 / 域等承载型 run 不含 w:t / w:tab / w:br（只有 w:drawing 等），
        // 绝不为其写入文字，否则会把文字塞进图片、破坏对象。
        if (content.isEmpty()) return

        val needStructure = text.any { it == '\t' || it == '\n' } ||
            content.any { it.local() == "tab" || it.local() == "br" }
        if (!needStructure) {
            val texts = content.filter { it.local() == "t" }
            if (texts.isEmpty()) return
            texts.first().textContent = text
            for (i in 1 until texts.size) run.removeChild(texts[i])
            return
        }

        // 需要结构：清掉旧的 t / tab / br，按 \t、\n 切段重建（连续普通文字合并进一个 w:t）
        val doc = run.ownerDocument
        // 插入点定位在 rPr 之后，维持 OOXML「rPr 在前、内容在后」的顺序要求
        var ref: Element? = run.child("w:rPr")
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

    /** 清空 run 的文字（保留 rPr），用于整段被清空的情形 */
    private fun clearRunText(run: Element) {
        for (t in run.childElements().filter { it.local() in setOf("t", "tab", "br") }) run.removeChild(t)
    }

    /**
     * 受格式约束的字符数分配，用于把新文字切分回填到各原始 run：
     *
     * 做法：先按「各 run 在原文中的起始位置」比例，把每个 run 的**起始边界**映射到新文本
     * 中的对应位置（取下界），再对带格式（下划线 / 粗体 / 斜体）的 run **锁定为其原始长度**，
     * 把由此产生的差值推给右侧相邻 run 吸收。
     *
     * 这样能同时保证两件关键的事：
     *  1) 无格式的明文**绝不会**被划进带下划线的 run —— 根除「附近文字被误加下划线」；
     *  2) 带下划线的字段（如「（盖章）」）能完整保住其下划线，边界字符不被无格式 run 吞掉。
     */
    private fun distributeRespectingFormat(lens: List<Int>, locked: List<Boolean>, total: Int): List<Int> {
        val n = lens.size
        if (n == 0) return emptyList()
        val o = lens.sum()
        if (o <= 0) return List(n) { if (it == 0) total else 0 }

        // 各 run 在原文中的累计起始位置（含结尾 O）
        val origStart = mutableListOf(0)
        for (l in lens) origStart += origStart.last() + l

        // 各 run 边界映射到新文本中的起始位置（取下界），pos.size == n + 1
        val pos = MutableList(n + 1) { 0 }
        for (i in 1..n) pos[i] = (origStart[i].toLong() * total / o).toInt() // 向下取整，避免向左吞并
        pos[n] = total

        // 格式 run 锁定原始长度：平移其右边界 pos[i+1]，差值由右侧 run 吸收（多为无格式 run）
        for (i in locked.indices) {
            if (!locked[i]) continue
            val cur = pos[i + 1] - pos[i]
            val diff = lens[i] - cur
            pos[i + 1] = (pos[i + 1] + diff).coerceIn(pos[i], total)
        }

        // 由最终边界算每个 run 的字符数
        return (0 until n).map { pos[it + 1] - pos[it] }
    }

    /** run 是否带「需要锁长」的格式：下划线 / 粗体 / 斜体 */
    private fun runHasFormatting(run: Element): Boolean {
        val rPr = run.child("w:rPr") ?: return false
        val u = rPr.child("w:u")
        if (u != null && underlineOn(u)) return true
        val b = rPr.child("w:b")
        if (b != null && onFlag(b)) return true
        val i = rPr.child("w:i")
        if (i != null && onFlag(i)) return true
        return false
    }

    /** w:u 是否开启下划线（无 val 视为开启；val=none/0/false/off 视为关闭） */
    private fun underlineOn(u: Element): Boolean {
        val v = u.attr("w:val")
        if (v == null) return true
        return v != "none" && v != "0" && v != "false" && v != "off"
    }

    /** w:b / w:i 是否开启（无 val 视为开启） */
    private fun onFlag(n: Element): Boolean {
        val v = n.attr("w:val")
        if (v == null) return true
        return v == "true" || v == "1" || v == "on"
    }

    /** 在段落内追加一个使用默认样式的 run（仅当段落完全没有 run 时兜底） */
    private fun appendRunWithText(p: Element, text: String, anchorAfter: Element?) {
        val doc = p.ownerDocument
        val r = doc.createElementNS(NS_W, "w:r")
        val t = doc.createElementNS(NS_W, "w:t").also {
            it.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            it.textContent = text
        }
        r.appendChild(t)
        if (anchorAfter != null) {
            val next = anchorAfter.nextSibling
            if (next != null) p.insertBefore(r, next) else p.appendChild(r)
        } else {
            p.appendChild(r)
        }
    }

    // ---------- DOM 辅助 ----------
    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    /** run 内承载纯文本内容的元素集合：w:t 文字、w:tab 制表符、w:br 软换行 */
    private val TEXTUAL = setOf("t", "tab", "br")

    private fun parseDom(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    /** 用标准 Transformer 序列化，正确处理命名空间与 w: 前缀绑定 */
    private fun serialize(el: Element): String {
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.setOutputProperty(OutputKeys.METHOD, "xml")
        val sw = StringWriter()
        tf.transform(DOMSource(el), StreamResult(sw))
        return sw.toString()
    }

    /**
     * 部分 Android Transformer 实现会将 w: 前缀替换为 ns1: 等自动前缀，
     * 或使用默认命名空间（无前缀），导致输出的 document.xml 格式不正确。
     * 此函数检测并修复这两种情况，确保输出始终使用 w: 前缀。
     */
    private fun fixNamespacePrefix(xml: String): String {
        val nsUri = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        // 检查 w: 前缀是否已保留（正常情况）
        if (xml.contains(" xmlns:w=\"") || xml.contains(" xmlns:W=\"") || xml.contains("<w:") || xml.contains("</w:")) {
            Log.d("DocxInPlace", "fixNamespacePrefix: w: prefix already present, no fix needed")
            return xml
        }
        // 情况 1：检测自动生成的前缀（如 ns1:）并替换为 w:
        val prefixRegex = Regex("xmlns:(\\w+)=\"" + Regex.escape(nsUri) + "\"")
        val match = prefixRegex.find(xml)
        if (match != null) {
            val autoPrefix = match.groupValues[1]
            if (autoPrefix != "w") {
                Log.w("DocxInPlace", "fixNamespacePrefix: auto prefix '$autoPrefix' -> 'w'")
                // 注意替换顺序：先替换属性（= 号），再替换标签
                return xml
                    .replace(":$autoPrefix=", ":w=")
                    .replace(":$autoPrefix ", ":w ")
                    .replace("<$autoPrefix:", "<w:")
                    .replace("</$autoPrefix:", "</w:")
            }
        }
        // 情况 2：检测默认命名空间（无前缀），将其转换为 w: 前缀
        val defaultNsRegex = Regex("xmlns=\"" + Regex.escape(nsUri) + "\"")
        if (defaultNsRegex.containsMatchIn(xml)) {
            Log.w("DocxInPlace", "fixNamespacePrefix: default namespace -> w: prefix")
            // 将默认命名空间声明改为 w: 前缀声明
            var fixed = xml.replaceFirst("xmlns=\"$nsUri\"", "xmlns:w=\"$nsUri\"")
            // 为所有无前缀的元素添加 w: 前缀（它们原本在默认命名空间中）
            // 匹配 <tagName 或 </tagName，后跟空格、> 或 : 之一
            // 如果后跟 : 说明该标签已有其他前缀（如 mc:），不添加 w: 前缀
            val tagRegex = Regex("<(/)?([a-zA-Z][a-zA-Z0-9]*)([\\s>:])")
            fixed = tagRegex.replace(fixed) { mr ->
                val slash = mr.groupValues[1]
                val tag = mr.groupValues[2]
                val after = mr.groupValues[3]
                // 跳过已带前缀的标签（后跟 :）、xml 声明、处理指令
                if (after == ":" || tag in setOf("xml", "?xml")) mr.value
                else "<${slash}w:$tag$after"
            }
            return fixed
        }
        Log.w("DocxInPlace", "fixNamespacePrefix: no namespace fix applied, returning as-is")
        return xml
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
