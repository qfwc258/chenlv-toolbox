package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * 解析 .docx（Word 2007+ Open XML）为 GovDoc 模型。
 *
 * 忠实还原段落结构与样式：标题居中、正文缩进 / 两端对齐、字号、字体、
 * 粗斜体、表格等，使在工具箱内得到的预览 / 编辑 / 导出与原始 Word 一致。
 *
 * docx 本质是 zip 包，主文档位于 `word/document.xml`，样式名在
 * `word/styles.xml`，本解析器不依赖 POI 等重型库，用标准 DOM 即可。
 */
object DocxReader {

    /**
     * @param bytes .docx 文件字节
     * @param spec  当前生效的公文规范：决定纸张 / 页边距 / 页码等文档级参数。
     *              段落内的字体、字号、粗斜体仍忠实沿用原 Word 文档，
     *              这样「打开 Word → 导出 PDF」既保留原有字体，又符合所选公文规范的版式。
     */
    @JvmOverloads
    fun read(bytes: ByteArray, spec: GovDocSpec = GovDocSpec.DEFAULT): GovDoc {
        var docXml: ByteArray? = null
        var stylesXml: ByteArray? = null
        val footerXmls = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val n = entry.name.lowercase()
                when {
                    n.endsWith("word/document.xml") -> docXml = zis.readBytes()
                    n.endsWith("word/styles.xml") -> stylesXml = zis.readBytes()
                    // footer1.xml / footer2.xml …（首页、奇偶页可能各有一个）
                    n.startsWith("word/footer") && n.endsWith(".xml") -> footerXmls += zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        val docData = docXml ?: error("不是有效的 Word 文档（缺少 document.xml）")
        val styleNames = parseStyles(stylesXml)

        val dom = parseDom(docData)
        val body = dom.documentElement.childElements().firstOrNull { it.local() == "body" }
            ?: error("文档结构异常：缺少 body")
        val blocks = mutableListOf<Block>()
        var title = ""
        for (child in body.childElements()) {
            when (child.local()) {
                "p" -> {
                    val para = parsePara(child, styleNames)
                    blocks += para
                    if (title.isBlank()) {
                        val t = para.runs.joinToString("") { it.text }.trim()
                        if (t.isNotEmpty()) title = t
                    }
                }
                "tbl" -> blocks += parseTable(child)
                // sectPr 等其它节点忽略
            }
        }
        if (blocks.isEmpty()) error("文档内容为空")

        // 页码完全取决于原文档：页脚里有 PAGE 域才有页码，样式也照搬原文档
        val pn = parseFooterPageNum(footerXmls, body)

        return GovDoc(
            blocks = blocks,
            page = spec.page,
            title = title.ifBlank { "公文" },
            mainTitleFont = spec.mainTitleFont,
            bodyFont = spec.bodyFont,
            bodySizePt = spec.bodySizePt,
            lineSpacingPt = spec.lineSpacingPt,
            indentPt = spec.indentPt,
            pageNumber = pn != null,
            pageNumStyle = pn ?: PageNumStyle(),
            // 保留原始字节：导出 Word 时走 DocxInPlace 原位修改，整体格式与表格不变形
            originalDocx = bytes
        )
    }

    /** 匹配独立单词 PAGE，避免把 NUMPAGES / PAGEREF 误判成页码域 */
    private val PAGE_FIELD = Regex("""(^|[^A-Za-z])PAGE([^A-Za-z]|$)""")

    /**
     * 从页脚判断原文档有没有页码，并还原其字号 / 字体 / 页脚距边界。
     *
     * @return 有页码时返回样式；没有则返回 null
     */
    private fun parseFooterPageNum(footers: List<ByteArray>, body: Element): PageNumStyle? {
        for (raw in footers) {
            val dom = runCatching { parseDom(raw) }.getOrNull() ?: continue
            val root = dom.documentElement
            if (!hasPageField(root)) continue

            // 字号 / 字体：取页脚里第一个显式声明的值，缺省沿用公文默认
            var sizePt = 0.0
            var font = ""
            collectRunProps(root) { f, s ->
                if (font.isBlank() && f.isNotBlank()) font = f
                if (sizePt <= 0.0 && s > 0.0) sizePt = s
            }
            return PageNumStyle(
                fontSizePt = if (sizePt > 0) sizePt else 14.0,
                font = font,
                footerDistanceCm = footerDistanceCm(body)
            )
        }
        return null
    }

    /** 页脚中是否存在 PAGE 域：既支持三段式 w:instrText，也支持 w:fldSimple */
    private fun hasPageField(node: Element): Boolean {
        for (c in node.childElements()) {
            when (c.local()) {
                "instrText" -> if (PAGE_FIELD.containsMatchIn(c.textContent)) return true
                "fldSimple" -> if (PAGE_FIELD.containsMatchIn(c.attr("w:instr").orEmpty())) return true
            }
            if (hasPageField(c)) return true
        }
        return false
    }

    /** 递归收集页脚里 run 的字体与字号 */
    private fun collectRunProps(node: Element, sink: (String, Double) -> Unit) {
        for (c in node.childElements()) {
            if (c.local() == "rPr") {
                val f = c.child("w:rFonts")?.run {
                    val ea = attr("w:eastAsia").orEmpty()
                    if (ea.isBlank()) attr("w:ascii").orEmpty() else ea
                }.orEmpty()
                val s = c.child("w:sz")?.attr("w:val")?.toDoubleOrNull()?.div(2.0) ?: 0.0
                sink(f, s)
            }
            collectRunProps(c, sink)
        }
    }

    /** 取 sectPr/pgMar 的 w:footer（缇）→ 厘米；缺省 1.75cm（Word 默认） */
    private fun footerDistanceCm(body: Element): Double {
        val tw = body.childElements().firstOrNull { it.local() == "sectPr" }
            ?.child("w:pgMar")?.attr("w:footer")?.toDoubleOrNull() ?: return 1.75
        // 缇 → 厘米，换算系数与写入端 cmToTwips 保持一致；异常值回落到默认
        val cm = tw / 566.929
        return if (cm in 0.3..5.0) cm else 1.75
    }

    private fun parseStyles(xml: ByteArray?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (xml == null) return map
        runCatching {
            val dom = parseDom(xml)
            for (s in dom.documentElement.childElements()) {
                if (s.local() != "style") continue
                val id = s.attr("w:styleId") ?: continue
                val name = s.child("w:name")?.attr("w:val") ?: ""
                map[id] = name
            }
        }
        return map
    }

    private fun parseTable(tbl: Element): Block.Table {
        val rows = mutableListOf<List<List<TextRun>>>()
        for (tr in tbl.childElements()) {
            if (tr.local() != "tr") continue
            val cells = mutableListOf<List<TextRun>>()
            for (tc in tr.childElements()) {
                if (tc.local() != "tc") continue
                val runs = mutableListOf<TextRun>()
                for (p in tc.childElements()) {
                    if (p.local() != "p") continue
                    runs += parseRuns(p)
                }
                cells += if (runs.isEmpty()) emptyList() else runs
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return Block.Table(rows)
    }

    private fun parsePara(p: Element, styleNames: Map<String, String>): Block.Para {
        var align = Align.LEFT
        var firstLinePt = 0.0
        var linePt = MdToGongwen.LINE_SPACING
        val pPr = p.child("w:pPr")
        if (pPr != null) {
            pPr.child("w:jc")?.attr("w:val")?.let { v ->
                align = when (v) {
                    "center" -> Align.CENTER
                    "right" -> Align.RIGHT
                    "both" -> Align.BOTH
                    else -> Align.LEFT
                }
            }
            pPr.child("w:ind")?.attr("w:firstLine")?.toDoubleOrNull()?.let { firstLinePt = it / 20.0 }
            pPr.child("w:spacing")?.attr("w:line")?.toDoubleOrNull()?.let { linePt = it / 20.0 }
            // 段落边框：公文填空线/标题线/签名线常用 w:pBdr 下边框实现。统一到模型路径后
            // 需解析并保留，否则预览中这类「下划线」整体缺失（旧字节路径已支持）。
            val borders = pPr.child("w:pBdr")?.let { pBdr ->
                fun borderOf(tag: String): ParaBorder? {
                    val b = pBdr.child("w:$tag") ?: return null
                    val v = b.attr("w:val").orEmpty()
                    if (v.isBlank() || v == "none" || v == "nil") return null
                    val sz = b.attr("w:sz")?.toDoubleOrNull()?.div(8.0) ?: 1.0 // 八分之一磅 → 磅
                    val color = b.attr("w:color")?.let { if (it != "auto") "#$it" else "#000000" } ?: "#000000"
                    return ParaBorder(value = v, szPt = sz, color = color)
                }
                ParaBorders(top = borderOf("top"), bottom = borderOf("bottom"), left = borderOf("left"), right = borderOf("right"))
            }
            // 制表位（含前导符 leader）：公务填空线/目录点线由它驱动，是第二类「下划线」。
            // 旧字节路径未解析，导致这类效果在模型路径预览中整体缺失。这里解析并保留。
            val tabs = pPr.child("w:tabs")?.let { tabsEl ->
                tabsEl.childElements().filter { it.local() == "tab" }.mapNotNull { tab ->
                    val pos = tab.attr("w:pos")?.toDoubleOrNull()?.div(20.0) ?: return@mapNotNull null // 缇 → 磅
                    val al = tab.attr("w:val").orEmpty().let { if (it.isBlank()) "left" else it }
                    val ld = tab.attr("w:leader").orEmpty().let { if (it.isBlank()) "none" else it }
                    TabStop(posPt = pos, align = al, leader = ld)
                }
            }.orEmpty()
            val styleId = pPr.child("w:pStyle")?.attr("w:val")
            if (styleId != null) {
                val sname = styleNames[styleId].orEmpty()
                // Word 标题样式（含「标题」/heading）视为公文标题：居中、加粗
                if (sname.contains("标题") || sname.contains("heading", ignoreCase = true)) {
                    align = Align.CENTER
                }
            }
        }
        val runs = parseRuns(p)
        val effSize = runs.firstNotNullOfOrNull { if (it.sizePt > 0) it.sizePt else null }
            ?: MdToGongwen.SIZE_NORMAL
        val effFont = runs.firstOrNull { it.font.isNotBlank() }?.font
            ?: MdToGongwen.FONT_FANG
        val finalRuns = if (runs.isEmpty()) emptyList() else runs.map {
            it.copy(
                sizePt = if (it.sizePt > 0) it.sizePt else effSize,
                font = if (it.font.isBlank()) effFont else it.font
            )
        }
        return Block.Para(
            finalRuns,
            ParaProps(align = align, firstLineIndentPt = firstLinePt, lineSpacingPt = linePt, borders = borders, tabs = tabs)
        )
    }

    private fun parseRuns(p: Element): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        for (r in p.childElements()) {
            if (r.local() != "r") continue
            val rPr = r.child("w:rPr")
            var bold = false
            var italic = false
            var underline = false
            var strike = false
            var font = ""
            var sz = 0.0
            var border: ParaBorder? = null
            if (rPr != null) {
                bold = isOn(rPr.child("w:b"))
                italic = isOn(rPr.child("w:i"))
                underline = isUnderline(rPr.child("w:u"))
                strike = isOn(rPr.child("w:strike"))
                font = rPr.child("w:rFonts")?.run {
                    val ea = attr("w:eastAsia").orEmpty()
                    if (ea.isBlank()) attr("w:ascii").orEmpty() else ea
                }.orEmpty()
                sz = rPr.child("w:sz")?.attr("w:val")?.toDoubleOrNull()?.div(2.0) ?: 0.0
                // 字符边框（w:rPr/w:bdr）：单字强调框 / 印章占位框。旧字节路径完全未处理，
                // 导致这类「框线」在模型路径预览中缺失。这里解析并保留。
                border = rPr.child("w:bdr")?.let { bd ->
                    val v = bd.attr("w:val").orEmpty()
                    if (v.isBlank() || v == "none" || v == "nil") return@let null
                    val szPb = bd.attr("w:sz")?.toDoubleOrNull()?.div(8.0) ?: 1.0 // 八分之一磅 → 磅
                    val color = bd.attr("w:color")?.let { if (it != "auto") "#$it" else "#000000" } ?: "#000000"
                    ParaBorder(value = v, szPt = szPb, color = color)
                }
            }
            val sb = StringBuilder()
            appendText(r, sb)
            runs += TextRun(sb.toString(), font, sz, bold, italic, underline, strike, border)
        }
        return runs
    }

    /** 递归收集 run 内文本：w:t 取字、w:tab 转制表符、w:br 转换行，域代码忽略 */
    private fun appendText(node: Element, sb: StringBuilder) {
        val nodes: NodeList = node.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) {
                when (n.local()) {
                    "t" -> sb.append(n.textContent)
                    "tab" -> sb.append("\t")
                    "br" -> sb.append("\n")
                    else -> appendText(n, sb)
                }
            }
        }
    }

    /** <w:b/>（无属性）即开启；<w:b w:val="false"/> 关闭 */
    private fun isOn(node: Element?): Boolean {
        if (node == null) return false
        val v = node.attr("w:val")
        if (v == null) return true
        return v == "true" || v == "1" || v == "on"
    }

    /** <w:u w:val="single"/> 等表示有下划线；<w:u w:val="none"/> 或关闭则无；
     *  <w:u/> 无属性时 Word 视为单线下划线 */
    private fun isUnderline(node: Element?): Boolean {
        if (node == null) return false
        val v = node.attr("w:val")
        if (v == null) return true
        return v != "none" && v != "0" && v != "off" && v != "false"
    }

    // ---------- DOM 辅助 ----------
    private fun parseDom(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    private fun Element.local(): String {
        val tag = tagName
        val idx = tag.indexOf(':')
        return if (idx >= 0) tag.substring(idx + 1) else tag
    }

    private fun Element.attr(name: String): String? {
        val local = name.local()
        // 不感知命名空间时，属性名可能带 w: 前缀或就是本地名
        getAttributeNode(name)?.value?.let { return it }
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
