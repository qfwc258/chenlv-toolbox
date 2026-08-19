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
        val styles = parseStyleTable(stylesXml)

        val dom = parseDom(docData)
        val body = dom.documentElement.childElements().firstOrNull { it.local() == "body" }
            ?: error("文档结构异常：缺少 body")
        val blocks = mutableListOf<Block>()
        var title = ""
        for (child in body.childElements()) {
            when (child.local()) {
                "p" -> {
                    val para = parsePara(child, styles)
                    blocks += para
                    if (title.isBlank()) {
                        val t = para.runs.joinToString("") { it.text }.trim()
                        if (t.isNotEmpty()) title = t
                    }
                }
                "tbl" -> blocks += parseTable(child, styles)
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

    /**
     * 解析 styles.xml：样式名表 + 各样式的字符属性（粗体 / 斜体 / 下划线 / 删除线 /
     * 字体 / 字号）与段落对齐，含 basedOn 继承链。
     *
     * 真实 Word 文档（法院模板、WPS 导出等）的粗体 / 下划线大量只存在于样式层
     * （run 上无 w:b / w:u，靠 w:pStyle / w:rStyle 继承），只取样式名会导致
     * 这些格式在预览中整体丢失。此处完整解析，供 [parseRuns] 按 OOXML 优先级
     * （docDefaults < 段落样式 < 字符样式 < 直接格式）合并。
     */
    private fun parseStyleTable(xml: ByteArray?): StyleTable {
        val empty = StyleTable(emptyMap(), emptyMap(), StyleProps())
        if (xml == null) return empty
        return runCatching {
            val dom = parseDom(xml)
            val names = mutableMapOf<String, String>()
            val defs = mutableMapOf<String, StyleDef>()
            var defaults = StyleProps()
            for (s in dom.documentElement.childElements()) {
                when (s.local()) {
                    "docDefaults" -> s.child("w:rPrDefault")?.child("w:rPr")?.let {
                        defaults = mergeProps(defaults, readRunProps(it))
                    }
                    "style" -> {
                        val id = s.attr("w:styleId") ?: continue
                        val name = s.child("w:name")?.attr("w:val") ?: ""
                        names[id] = name
                        val basedOn = s.child("w:basedOn")?.attr("w:val")
                        val props = s.child("w:rPr")?.let { readRunProps(it) } ?: StyleProps()
                        val align = s.child("w:pPr")?.child("w:jc")?.attr("w:val")
                        defs[id] = StyleDef(name, basedOn, props, align)
                    }
                }
            }
            StyleTable(names, defs, defaults)
        }.getOrDefault(empty)
    }

    /** 三态字符属性：null=未声明（继承下层），false=显式关闭。样式合并的载体。 */
    private class StyleProps(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val font: String? = null,
        val sizePt: Double? = null,
        val underlineStyle: String? = null,
        val color: String? = null,
        val highlight: String? = null,
        val vertAlign: String? = null
    )

    /** 单个样式定义：名称、父样式（basedOn）、字符属性、段落对齐（仅段落样式有意义） */
    private class StyleDef(
        val name: String,
        val basedOn: String?,
        val props: StyleProps,
        val align: String?
    )

    /** styles.xml 解析结果：样式表 + 文档级默认字符属性（docDefaults/rPrDefault） */
    private class StyleTable(
        val names: Map<String, String>,
        private val defs: Map<String, StyleDef>,
        val defaults: StyleProps
    ) {
        /** 样式 id → 沿 basedOn 链继承合并后的有效字符属性（子样式覆盖父样式） */
        fun propsOf(id: String?): StyleProps {
            var cur = id
            var depth = 0
            val chain = mutableListOf<StyleDef>()
            while (cur != null && depth < 8) { // 深度上限防循环引用
                val d = defs[cur] ?: break
                chain += d
                cur = d.basedOn
                depth++
            }
            var acc = StyleProps()
            for (i in chain.indices.reversed()) acc = mergeProps(acc, chain[i].props)
            return acc
        }

        /** 段落样式链上的对齐声明（子样式优先），供段落未直接声明 w:jc 时继承 */
        fun alignOf(id: String?): String? {
            var cur = id
            var depth = 0
            while (cur != null && depth < 8) {
                val d = defs[cur] ?: return null
                d.align?.let { return it }
                cur = d.basedOn
                depth++
            }
            return null
        }
    }

    /** 从 w:rPr 读取直接字符属性（三态）；w:bCs/w:iCs 为复杂文种开关，与 w:b/w:i 取并 */
    private fun readRunProps(rPr: Element): StyleProps {
        val uEl = rPr.child("w:u")
        return StyleProps(
            bold = triOn(rPr.child("w:b")) ?: triOn(rPr.child("w:bCs")),
            italic = triOn(rPr.child("w:i")) ?: triOn(rPr.child("w:iCs")),
            underline = triUnderline(uEl),
            strike = triOn(rPr.child("w:strike")),
            font = rPr.child("w:rFonts")?.run {
                val ea = attr("w:eastAsia").orEmpty()
                if (ea.isBlank()) attr("w:ascii").orEmpty() else ea
            }?.takeIf { it.isNotBlank() },
            sizePt = rPr.child("w:sz")?.attr("w:val")?.toDoubleOrNull()?.div(2.0)
                ?: rPr.child("w:szCs")?.attr("w:val")?.toDoubleOrNull()?.div(2.0),
            // 下划线线型（w:u/@w:val）：none 之外的合法线型透传
            underlineStyle = uEl?.attr("w:val")?.takeIf { it.isNotBlank() && it != "none" },
            // 文字颜色：Word 6 位 HEX → CSS；auto 表示默认色（视为未声明）
            color = rPr.child("w:color")?.attr("w:val")
                ?.takeIf { it.isNotBlank() && it != "auto" }
                ?.let { "#$it" },
            // 高亮颜色名（yellow/green/...）；none 视为未声明
            highlight = rPr.child("w:highlight")?.attr("w:val")
                ?.takeIf { it.isNotBlank() && it != "none" },
            // 上标 / 下标
            vertAlign = rPr.child("w:vertAlign")?.attr("w:val")
                ?.takeIf { it == "superscript" || it == "subscript" }
        )
    }

    private fun triOn(node: Element?): Boolean? = if (node == null) null else isOn(node)

    private fun triUnderline(node: Element?): Boolean? = if (node == null) null else isUnderline(node)

    /** 属性合并：over 中声明的属性生效，未声明（null）的沿用 base */
    private fun mergeProps(base: StyleProps, over: StyleProps): StyleProps = StyleProps(
        bold = over.bold ?: base.bold,
        italic = over.italic ?: base.italic,
        underline = over.underline ?: base.underline,
        strike = over.strike ?: base.strike,
        font = over.font ?: base.font,
        sizePt = over.sizePt ?: base.sizePt,
        underlineStyle = over.underlineStyle ?: base.underlineStyle,
        color = over.color ?: base.color,
        highlight = over.highlight ?: base.highlight,
        vertAlign = over.vertAlign ?: base.vertAlign
    )

    private fun parseTable(tbl: Element, styles: StyleTable): Block.Table {
        val rows = mutableListOf<List<List<TextRun>>>()
        for (tr in tbl.childElements()) {
            if (tr.local() != "tr") continue
            val cells = mutableListOf<List<TextRun>>()
            for (tc in tr.childElements()) {
                if (tc.local() != "tc") continue
                val runs = mutableListOf<TextRun>()
                for (p in tc.childElements()) {
                    if (p.local() != "p") continue
                    val pid = p.child("w:pPr")?.child("w:pStyle")?.attr("w:val")
                    runs += parseRuns(p, styles, pid)
                }
                cells += if (runs.isEmpty()) emptyList() else runs
            }
            if (cells.isNotEmpty()) rows += cells
        }
        return Block.Table(rows)
    }

    private fun parsePara(p: Element, styles: StyleTable): Block.Para {
        var align = Align.LEFT
        var firstLinePt = 0.0
        var linePt = MdToGongwen.LINE_SPACING
        // 段前 / 段后间距（缇 → 磅）：标题段与正文段的呼吸感由它决定，缺失会让预览版面挤成一片
        var spaceBeforePt = 0.0
        var spaceAfterPt = 0.0
        // 段落边框 / 制表位在 pPr 块内解析，但作用域须覆盖整个函数（返回值要用），
        // 因此先在块外声明、块内赋值，避免「Unresolved reference」。
        var borders: ParaBorders? = null
        var tabs: List<TabStop> = emptyList()
        val pPr = p.child("w:pPr")
        // 段落样式 id：既用于标题识别，也作为 run 字符属性的继承底座
        val styleId = pPr?.child("w:pStyle")?.attr("w:val")
        if (pPr != null) {
            val jc = pPr.child("w:jc")?.attr("w:val")
            if (jc != null) {
                align = when (jc) {
                    "center" -> Align.CENTER
                    "right" -> Align.RIGHT
                    "both" -> Align.BOTH
                    else -> Align.LEFT
                }
            } else {
                // 段落未直接声明对齐时继承段落样式（「标题」等样式常自带居中）
                when (styles.alignOf(styleId)) {
                    "center" -> align = Align.CENTER
                    "right" -> align = Align.RIGHT
                    "both" -> align = Align.BOTH
                }
            }
            pPr.child("w:ind")?.attr("w:firstLine")?.toDoubleOrNull()?.let { firstLinePt = it / 20.0 }
            pPr.child("w:spacing")?.let { sp ->
                sp.attr("w:line")?.toDoubleOrNull()?.let { linePt = it / 20.0 }
                sp.attr("w:before")?.toDoubleOrNull()?.let { spaceBeforePt = it / 20.0 }
                sp.attr("w:after")?.toDoubleOrNull()?.let { spaceAfterPt = it / 20.0 }
            }
            // 段落边框：公文填空线/标题线/签名线常用 w:pBdr 下边框实现。统一到模型路径后
            // 需解析并保留，否则预览中这类「下划线」整体缺失（旧字节路径已支持）。
            borders = pPr.child("w:pBdr")?.let { pBdr ->
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
            tabs = pPr.child("w:tabs")?.let { tabsEl ->
                tabsEl.childElements().filter { it.local() == "tab" }.mapNotNull { tab ->
                    val pos = tab.attr("w:pos")?.toDoubleOrNull()?.div(20.0) ?: return@mapNotNull null // 缇 → 磅
                    val al = tab.attr("w:val").orEmpty().let { if (it.isBlank()) "left" else it }
                    val ld = tab.attr("w:leader").orEmpty().let { if (it.isBlank()) "none" else it }
                    TabStop(posPt = pos, align = al, leader = ld)
                }
            }.orEmpty()
            if (styleId != null) {
                val sname = styles.names[styleId].orEmpty()
                // Word 标题样式（含「标题」/heading）视为公文标题：居中、加粗
                if (sname.contains("标题") || sname.contains("heading", ignoreCase = true)) {
                    align = Align.CENTER
                }
            }
        }
        val runs = parseRuns(p, styles, styleId)
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
            ParaProps(
                align = align,
                firstLineIndentPt = firstLinePt,
                lineSpacingPt = linePt,
                spaceBeforePt = spaceBeforePt,
                spaceAfterPt = spaceAfterPt,
                borders = borders,
                tabs = tabs
            )
        )
    }

    /**
     * 解析段落内所有 run。字符属性按 OOXML 优先级合并：
     * docDefaults < 段落样式(w:pStyle) < 字符样式(w:rStyle) < 直接格式(w:rPr)。
     * 真实文档的粗体 / 下划线常只存在于样式层（run 上无 w:b / w:u），
     * 不做合并的话这些格式会在预览中显示为普通文字。
     */
    private fun parseRuns(p: Element, styles: StyleTable, paraStyleId: String? = null): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        for (r in p.childElements()) {
            if (r.local() != "r") continue
            val rPr = r.child("w:rPr")
            val direct = if (rPr != null) readRunProps(rPr) else StyleProps()
            val rStyleId = rPr?.child("w:rStyle")?.attr("w:val")
            val eff = mergeProps(
                mergeProps(
                    mergeProps(styles.defaults, styles.propsOf(paraStyleId)),
                    styles.propsOf(rStyleId)
                ),
                direct
            )
            // 字符边框（w:rPr/w:bdr）：单字强调框 / 印章占位框，仅直接格式支持
            val border = rPr?.child("w:bdr")?.let { bd ->
                val v = bd.attr("w:val").orEmpty()
                if (v.isBlank() || v == "none" || v == "nil") return@let null
                val szPb = bd.attr("w:sz")?.toDoubleOrNull()?.div(8.0) ?: 1.0 // 八分之一磅 → 磅
                val color = bd.attr("w:color")?.let { if (it != "auto") "#$it" else "#000000" } ?: "#000000"
                ParaBorder(value = v, szPt = szPb, color = color)
            }
            val sb = StringBuilder()
            appendText(r, sb)
            runs += TextRun(
                sb.toString(),
                eff.font.orEmpty(),
                eff.sizePt ?: 0.0,
                eff.bold == true,
                eff.italic == true,
                eff.underline == true,
                eff.underlineStyle,
                eff.strike == true,
                eff.color,
                eff.highlight,
                eff.vertAlign,
                border
            )
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
