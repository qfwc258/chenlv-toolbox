package com.wb.mdgw.docgen

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 从 docx 模板中提取「占位符 key」（纯 JVM，可单测）。
 *
 * 背景：模板里的占位符就是裸 key（如 leib、gcsj1），[com.wb.mdgw.DocxTemplateFiller]
 * 按精确 key 匹配替换。因此可解包模板、拼出正文文本，反推出模板用到了哪些字段，
 * 让用户在「添加字段」时直接点选，而不必手记 key。
 *
 * 输出两类：
 *  - 标准 key：在 [standardKeys]（内置默认字段全集）内的，**精确可靠**；
 *  - 疑似 key：模板里出现、但不在标准集内的全小写拼音 token（如 sq3、zdy1），
 *    供用户确认（正文里的英文缩写通过停用词过滤，降低误报）。
 */
object DocxPlaceholders {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** 自定义占位符形态：2 个及以上小写字母开头 + 可选数字（自然过滤大写英文缩写 IVD/WPS） */
    private val TOKEN_REGEX = Regex("[a-z]{2,}[0-9]*")

    /** 常见英文 / 技术词，避免把正文英文误判为占位符 */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was",
        "one", "our", "out", "day", "get", "has", "his", "how", "its", "may", "new", "now",
        "old", "see", "two", "way", "who", "did", "let", "say", "she", "too", "use", "http",
        "https", "www", "com", "pdf", "doc", "docx", "wps", "ofd", "jpg", "png", "img", "src",
        "div", "span", "true", "false", "null", "none", "type", "text", "data", "file", "page",
        "size", "name", "date", "time", "etc", "xxx", "xx"
    )

    data class ScanResult(
        /** 命中的标准 key（按 standardKeys 顺序排列） */
        val standard: List<String>,
        /** 疑似自定义占位符（非标准，已去停用词，按出现次数降序） */
        val unknown: List<String>
    )

    /** 扫描多个模板字节，汇总占位符 */
    fun scan(docxBytesList: List<ByteArray>, standardKeys: List<String>): ScanResult {
        val allText = buildString {
            docxBytesList.forEach { append(extractText(it)).append('\n') }
        }

        val standardSet = standardKeys.toSet()

        // 标准 key 精确匹配（长键优先，与替换引擎同一套规则）
        val standardHit = LinkedHashSet<String>()
        if (standardKeys.isNotEmpty()) {
            val pattern = Regex(standardKeys.sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) })
            pattern.findAll(allText).forEach { standardHit.add(it.value) }
        }

        // 疑似自定义 token
        val unknownCount = LinkedHashMap<String, Int>()
        TOKEN_REGEX.findAll(allText).forEach { m ->
            val t = m.value
            if (t !in standardSet && t !in STOP_WORDS) {
                unknownCount[t] = (unknownCount[t] ?: 0) + 1
            }
        }

        val standardOrdered = standardKeys.filter { it in standardHit }
        val unknownOrdered = unknownCount.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        return ScanResult(standardOrdered, unknownOrdered)
    }

    /** 解包 docx，取正文与页眉页脚，按段落拼接可见文本（段落间空格，避免跨段误拼） */
    private fun extractText(docx: ByteArray): String {
        val sb = StringBuilder()
        runCatching {
            ZipInputStream(ByteArrayInputStream(docx)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val n = entry.name
                    val isText = n == "word/document.xml" ||
                        (n.startsWith("word/header") && n.endsWith(".xml")) ||
                        (n.startsWith("word/footer") && n.endsWith(".xml"))
                    if (isText) sb.append(xmlParagraphText(zis.readBytes())).append('\n')
                    entry = zis.nextEntry
                }
            }
        }
        return sb.toString()
    }

    private fun xmlParagraphText(bytes: ByteArray): String = runCatching {
        val dom = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val paragraphs = dom.documentElement.getElementsByTagNameNS(NS_W, "p")
        val sb = StringBuilder()
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val texts = p.getElementsByTagNameNS(NS_W, "t")
            for (j in 0 until texts.length) {
                sb.append(texts.item(j).textContent)
            }
            sb.append(' ')
        }
        sb.toString()
    }.getOrDefault("")
}
