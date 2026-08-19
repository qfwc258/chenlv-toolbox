package com.wb.mdgw

import android.util.Log
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OOXML document.xml → HTML 转换器。
 *
 * 将 Word 文档的 document.xml 逐元素转换为 HTML + 内联 CSS，在 WebView 中渲染，
 * 实现与 WPS/Word 打印效果高度一致的预览。所有格式属性（字体、字号、颜色、粗斜体、
 * 下划线、删除线、高亮、上标下标、表格边框/列宽/合并单元格）均通过 CSS 原生表达。
 *
 * 生成的 HTML 结构：
 *   - 每个 <w:p> → <p data-block="N" onclick="editBridge.startEdit(N,-1,-1)">，内含 <span data-run="N"> 逐 run
 *   - 每个 <w:tbl> → <table data-block="N">，单元格 <td data-row data-col onclick="editBridge.startEdit(N,r,c)">
 *   - 编辑不依赖 contenteditable：点击段落/单元格经 JS 桥（editBridge.startEdit）回调触发结构化编辑弹窗，
 *     编辑结果直接写回 GovDoc 模型，导出由 GovDoc.toDocx() 生成文档（保格式）。
 */
object DocxHtml {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val TAG = "DocxHtml"

    /**
     * 将原始 .docx 字节转为可在 WebView 中渲染的完整 HTML 页面。
     *
     * @param docxBytes 原始 Word 文件字节
     * @param page      页面设置（决定边距比例）
     * @return 完整 HTML 字符串
     */
    fun toHtml(docxBytes: ByteArray, page: PageSetup): String {
        var docXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(docxBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.lowercase().endsWith("word/document.xml")) {
                    docXml = zis.readBytes()
                    break
                }
                entry = zis.nextEntry
            }
        }
        val docData = docXml ?: error("不是有效的 Word 文档（缺少 document.xml）")
        // 解析 XML 加异常兜底：损坏的 docx / 非标 XML 时返回空 body，由上层降级到 SimpleTextFallback
        val dom = try {
            parseDom(docData)
        } catch (e: Exception) {
            Log.w(TAG, "toHtml: document.xml 解析失败，返回空白页", e)
            return blankHtml()
        }
        val body = dom.documentElement.childElements().firstOrNull { it.local() == "body" }
            ?: return blankHtml("文档结构异常：缺少 body")

        val bodyHtml = try {
            buildBodyHtml(body)
        } catch (e: Exception) {
            Log.w(TAG, "toHtml: buildBodyHtml 失败，返回空白页", e)
            "<p data-block='0' class='doc-para' style='text-align:left;color:#999'>文档内容解析失败（${e.message ?: "未知错误"}）</p>"
        }
        Log.d(TAG, "toHtml: generated ${bodyHtml.length} chars of body HTML")

        return buildString {
            append(htmlHead(page))
            append("<div class='page'>")
            append(bodyHtml)
            append("</div>")
            append("</body></html>")
        }
    }

    /**
     * 将 GovDoc 模型（Markdown 生成）转为 HTML 页面。
     * 与 [toHtml] 生成相同的 HTML 结构和 data 属性，确保编辑回写一致。
     */
    fun govDocToHtml(doc: GovDoc): String {
        val page = doc.page

        val bodyHtml = buildString {
            doc.blocks.forEachIndexed { idx, b ->
                when (b) {
                    is Block.Para -> {
                        if (b.runs.isEmpty()) {
                            append("<p data-block='$idx' class='doc-para' style='${bordersToCss(b.props.borders)}'><br></p>")
                        } else {
                            val align = when (b.props.align) {
                                Align.CENTER -> "center"
                                Align.RIGHT -> "right"
                                Align.BOTH -> "justify"
                                else -> "left"
                            }
                            val indent = if (b.props.firstLineIndentPt > 0)
                                "text-indent:${b.props.firstLineIndentPt}pt;" else ""
                            val lh = if (b.props.lineSpacingPt > 0)
                                "line-height:${b.props.lineSpacingPt}pt;" else ""
                            // 段前 / 段后间距：标题与正文的呼吸感，缺失会让版面挤成一片
                            val mTop = if (b.props.spaceBeforePt > 0) "margin-top:${b.props.spaceBeforePt}pt;" else ""
                            val mBot = if (b.props.spaceAfterPt > 0) "margin-bottom:${b.props.spaceAfterPt}pt;" else ""
                            val border = bordersToCss(b.props.borders)
                            // 仅当存在「带可见前导符」的制表位时才启用 flex 前导线渲染；
                            // 其余情形（无制表位 / 制表位无 leader）按普通段落渲染，\t 退化为空白，不引入布局回归。
                            val visibleLeaderTabs = b.props.tabs.filter { it.leader.isNotBlank() && it.leader != "none" }
                            val useFlex = visibleLeaderTabs.isNotEmpty()
                            val cls = if (useFlex) "doc-para doc-para-flex" else "doc-para"
                            append("<p data-block='$idx' class='$cls' style='text-align:$align;$mTop$mBot$indent$lh$border' onclick=\"editBridge.startEdit($idx,-1,-1)\">")
                            if (useFlex) {
                                append(paraInnerWithTabs(b.runs, visibleLeaderTabs))
                            } else {
                                b.runs.forEachIndexed { ri, r ->
                                    append(runToHtml(r, ri))
                                }
                            }
                            append("</p>")
                        }
                    }
                    is Block.Table -> {
                        append("<table data-block='$idx' class='doc-table'>")
                        b.rows.forEachIndexed { ri, row ->
                            append("<tr>")
                            row.forEachIndexed { ci, cell ->
                                append("<td data-block='$idx' data-row='$ri' data-col='$ci' onclick=\"editBridge.startEdit($idx,$ri,$ci)\">")
                                cell.forEachIndexed { runi, r ->
                                    append(runToHtml(r, runi))
                                }
                                append("</td>")
                            }
                            append("</tr>")
                        }
                        append("</table>")
                    }
                }
            }
        }

        return buildString {
            append(htmlHead(page))
            append("<div class='page'>")
            append(bodyHtml)
            append("</div>")
            append("</body></html>")
        }
    }

    /**
     * 公共 HTML 头部：DOCTYPE + meta + 中文字体堆栈 + 页面基础样式。
     *
     * 页面适配（还原「实际打印效果」）：
     * - 纸页按文档真实物理尺寸渲染（页宽 px = cm×96/2.54），字号 / 行距 / 边距全用
     *   真实 pt 值——即 WYSIWYG，所见即打印。
     * - 整体等比缩放：浏览器布局宽固定为该物理页宽，加载后 JS 把 .page 用 zoom
     *   缩放到视口宽（zoom = min(视口宽/页宽, 1)）。这样字号和纸页同比例一起缩小，
     *   手机上显示的就是真实打印比例（不会因 page 单独缩窄而让字看起来偏大）；
     *   大屏 zoom=1，按真实 A4 居中显示。双指仍可缩放看细节。
     */
    private fun htmlHead(page: PageSetup): String = buildString {
        // 页宽 px / 页高 px（cm→px 按 CSS 96dpi；纸张按物理尺寸渲染）
        val pageW = (page.widthCm / 2.54 * 96.0).toInt()
        val pageH = (page.heightCm / 2.54 * 96.0).toInt()
        // 页边距 px（cm→px，真实比例，随页面一起缩放）
        val lPad = (page.leftCm / 2.54 * 96.0).toInt()
        val rPad = (page.rightCm / 2.54 * 96.0).toInt()
        val tPad = (page.topCm / 2.54 * 96.0).toInt()
        val bPad = (page.bottomCm / 2.54 * 96.0).toInt()
        append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=4.0,user-scalable=yes'>")
        append("<style>")
        append("*{box-sizing:border-box;margin:0;padding:0;}")
        // 中文优先：思源宋体 / Noto Serif CJK / 宋体 / SimSun / serif；正文 12pt / 1.75 行距
        append("body{background:#E8E8E8;font-family:'Source Han Serif SC','Noto Serif CJK SC','宋体',SimSun,serif;")
        append("-webkit-text-size-adjust:100%;}")
        // 纸页：物理尺寸渲染，min-height=页高；margin auto 居中（zoom 后宽度=视口宽时居中）
        append(".page{background:white;width:${pageW}px;min-height:${pageH}px;margin:0 auto;")
        append("padding:${tPad}px ${rPad}px ${bPad}px ${lPad}px;")
        append("box-shadow:0 4px 20px rgba(0,0,0,0.18);font-family:'宋体',SimSun,'Source Han Serif SC',serif;")
        append("line-height:1.75;font-size:12pt;color:#000;")
        append("text-align:justify;text-justify:inter-ideograph;}")
        // 段落：保留 1.75 行距，首行缩进 2 字符（pt=24pt）；white-space:pre-line 让 w:br
        // 转换来的 \n 真实换行显示，避免内容被合并成一行
        append(".doc-para{margin:0;padding:0;white-space:pre-line;}")
        append(".doc-para.first-indent{text-indent:24pt;}")
        // 含前导符制表位的段落：flex 布局让前导线（.doc-leader）自动撑满到制表位，
        // 还原公文「填空下划线 / 目录点线」。两端文字与线贴合，避免整体左对齐时线贴右边。
        append(".doc-para-flex{display:flex;flex-wrap:nowrap;align-items:flex-end;width:100%;white-space:normal;}")
        append(".doc-para-flex>span{display:inline-block;}")
        // 前导线：靠 flex:1 撑满到制表位；下边框实线/点线/虚线分别还原填空下划线/目录点线
        append(".doc-leader{flex:1 1 auto;min-width:24pt;display:inline-block;height:1.1em;border-bottom:1pt solid #000;margin:0 2px;}")
        // 表格：1px 黑实线、单元格居中、垂直居中
        append(".doc-table{width:100%;border-collapse:collapse;margin:6pt 0;table-layout:fixed;}")
        append(".doc-table td{border:1px solid #000;padding:4pt 6pt;vertical-align:middle;text-align:center;}")
        append(".doc-table tr:first-child td{border-top:1.5px solid #000;}")
        append(".doc-table tr:last-child td{border-bottom:1.5px solid #000;}")
        append(".doc-table td:first-child{border-left:1.5px solid #000;}")
        append(".doc-table td:last-child{border-right:1.5px solid #000;}")
        append("</style></head><body><script>")
        // 整页等比缩放到视口宽：字号与纸页同比例缩放，还原真实打印比例
        append("(function(){var pw=${pageW};function fit(){var w=window.visualViewport?window.visualViewport.width:window.innerWidth;")
        append("var z=w/pw;if(z>1)z=1;var p=document.querySelector('.page');if(p)p.style.zoom=z;}")
        append("window.addEventListener?addEventListener('load',fit):0;")
        append("window.addEventListener('resize',fit);")
        append("window.addEventListener('orientationchange',fit);")
        append("})();</script></head><body>")
    }


    /** 空白页（用于解析失败兜底） */
    private fun blankHtml(msg: String = ""): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width,initial-scale=1.0'>")
        append("<style>body{background:#E8E8E8;font-family:serif;padding:24px;}")
        append(".page{background:white;max-width:100%;margin:0 auto;padding:32px;box-shadow:0 2px 12px rgba(0,0,0,0.12);min-height:80vh;}")
        append(".empty{color:#999;font-size:14px;text-align:center;padding:60px 0;}</style>")
        append("</head><body><div class='page'><div class='empty'>")
        append(msg.ifBlank { "（空文档）" })
        append("</div></div></body></html>")
    }


    /** OOXML 边框线型（w:val）→ CSS border-style 关键字 */
    private fun borderStyleKeyword(value: String): String = when (value) {
        "single", "thick", "thinThickSmallGap", "thickThinSmallGap", "thinThickThinSmallGap" -> "solid"
        "double", "doubleWave" -> "double"
        "dash", "dashed", "dotDash", "dotDotDash" -> "dashed"
        "dot", "dotted", "sysDot" -> "dotted"
        "wave" -> "wavy"
        else -> "solid"
    }

    /** 将单个 TextRun 转为 HTML span */
    private fun runToHtml(r: TextRun, runIndex: Int): String =
        "<span data-run='$runIndex'${runStyleAttr(r)}>${r.text.escapeHtml()}</span>"

    /** 计算单个 run 的内联 style 属性（含前导空格），供整段渲染与制表位分段渲染复用 */
    private fun runStyleAttr(r: TextRun): String {
        val styles = mutableListOf<String>()
        if (r.font.isNotBlank()) styles += "font-family:'${r.font.escapeHtml()}',serif"
        if (r.sizePt > 0) styles += "font-size:${r.sizePt}pt"
        if (r.bold) styles += "font-weight:bold"
        if (r.italic) styles += "font-style:italic"
        // 下划线（含线型）+ 删除线合并到同一 text-decoration，避免简写声明后者覆盖前者
        val deco = mutableListOf<String>()
        if (r.underline) deco += "underline"
        if (r.strike) deco += "line-through"
        if (deco.isNotEmpty()) {
            // 下划线线型（w:u/@w:val）→ text-decoration-style；wavy 等无对应 CSS 的用默认实线
            val ds = when (r.underlineStyle) {
                "double", "wavyDouble" -> "double"
                "dash", "dashed", "dashLong", "dashDotHeavy", "dashDotDotHeavy" -> "dashed"
                "dotted", "dottedHeavy" -> "dotted"
                "dotDash", "dotDotDash" -> "dotted"
                else -> null
            }
            styles += "text-decoration:${deco.joinToString(" ")}${ds?.let { " $it" } ?: ""}"
        }
        // 文字颜色（红头文件的红字 / 批注色）
        r.color?.let { styles += "color:${it.escapeHtml()}" }
        // 高亮底色（Word 高亮名 → CSS 色）
        r.highlight?.let { highlightColor(it)?.let { c -> styles += "background-color:$c" } }
        // 上标 / 下标
        when (r.vertAlign) {
            "superscript" -> styles += "vertical-align:super;font-size:75%"
            "subscript" -> styles += "vertical-align:sub;font-size:75%"
        }
        // 字符边框（w:bdr）：单字强调框 / 印章占位框
        r.border?.let { b ->
            if (b.value != "none" && b.value != "nil") {
                styles += "border:${"%.2f".format(b.szPt)}pt ${borderStyleKeyword(b.value)} ${b.color}"
                styles += "padding:0 2px"
            }
        }
        return if (styles.isNotEmpty()) " style='${styles.joinToString(";")}'" else ""
    }

    /** 段落边框 → CSS border-*（下边框常用于填空线/标题线/签名线） */
    private fun bordersToCss(b: ParaBorders?): String {
        if (b == null) return ""
        val sb = StringBuilder()
        fun side(border: ParaBorder?, cssSide: String) {
            if (border == null || border.value == "none" || border.value == "nil") return
            sb.append("border-$cssSide:${"%.2f".format(border.szPt)}pt ${borderStyleKeyword(border.value)} ${border.color};")
        }
        side(b.top, "top"); side(b.bottom, "bottom"); side(b.left, "left"); side(b.right, "right")
        return sb.toString()
    }

    /**
     * 渲染带「前导符制表位」的段落内部 HTML：把 run 文本按 `\t` 拆分，
     * 在每个制表位处插入一段可伸展的前导线（underline/dot/dash → 下边框实线/点线/虚线），
     * 从而在预览中还原公文「填空下划线」「目录点线」这类第二类「下划线」。
     *
     * 依赖外层 <p> 使用 display:flex 使前导线 flex:1 自动撑满到制表位。
     */
    private fun paraInnerWithTabs(runs: List<TextRun>, leaderTabs: List<TabStop>): String = buildString {
        var tabSeen = 0
        runs.forEachIndexed { ri, r ->
            val styleAttr = runStyleAttr(r)
            val parts = r.text.split("\t")
            parts.forEachIndexed { pi, seg ->
                if (pi > 0) {
                    // 一个制表符：取对应制表位的前导符样式（超出则沿用最后一个）
                    val tab = leaderTabs.getOrNull(tabSeen) ?: leaderTabs.lastOrNull()
                    tabSeen++
                    val ls = when (tab?.leader) {
                        "underline" -> "solid"
                        "dot", "middleDot" -> "dotted"
                        "dash" -> "dashed"
                        else -> null
                    }
                    if (ls != null) {
                        append("<span class='doc-leader' style='flex:1 1 auto;min-width:24pt;border-bottom:1pt $ls #000;margin:0 2px;'></span>")
                    } else {
                        append("<span style='flex:0 0 auto;min-width:24pt;'></span>")
                    }
                }
                if (seg.isNotEmpty()) {
                    append("<span data-run='$ri'$styleAttr>${seg.escapeHtml()}</span>")
                }
            }
        }
    }

    // ========== 私有：document.xml → HTML ==========

    private fun buildBodyHtml(body: Element): String = buildString {
        var blockIdx = 0
        for (child in body.childElements()) {
            when (child.local()) {
                "p" -> {
                    append(convertPara(child, blockIdx))
                    blockIdx++
                }
                "tbl" -> {
                    append(convertTable(child, blockIdx))
                    blockIdx++
                }
            }
        }
    }

    private fun convertPara(p: Element, blockIdx: Int): String {
        val pPr = p.child("w:pPr")
        val styles = mutableListOf<String>()
        var align = "left"
        if (pPr != null) {
            pPr.child("w:jc")?.attr("w:val")?.let { v ->
                align = when (v) {
                    "center" -> "center"
                    "right" -> "right"
                    "both" -> "justify"
                    else -> "left"
                }
            }
            pPr.child("w:ind")?.let { ind ->
                ind.attr("w:firstLine")?.toDoubleOrNull()?.let {
                    styles += "text-indent:${it / 20.0}pt"
                }
                ind.attr("w:left")?.toDoubleOrNull()?.let {
                    styles += "margin-left:${it / 20.0}pt"
                }
            }
            pPr.child("w:spacing")?.let { sp ->
                sp.attr("w:line")?.toDoubleOrNull()?.let {
                    styles += "line-height:${it / 20.0}pt"
                }
                sp.attr("w:before")?.toDoubleOrNull()?.let {
                    styles += "margin-top:${it / 20.0}pt"
                }
                sp.attr("w:after")?.toDoubleOrNull()?.let {
                    styles += "margin-bottom:${it / 20.0}pt"
                }
            }
            // 段落边框：下边框常用于填空线 / 标题线 / 签名线（本质是视觉下划线）；
            // 其余三边若也有设置则一并渲染，保证公文段落框线保真。原代码完全未处理
            // w:pBdr，导致这类「下划线」在预览中整体缺失。
            pPr.child("w:pBdr")?.let { pBdr ->
                for (side in listOf("top" to "top", "bottom" to "bottom", "left" to "left", "right" to "right")) {
                    pBdr.child("w:${side.first}")?.let { br ->
                        val bv = br.attr("w:val").orEmpty()
                        if (bv.isNotBlank() && bv != "none" && bv != "nil") {
                            val sz = br.attr("w:sz")?.toDoubleOrNull()?.div(8.0) ?: 1.0 // 八分之一磅 → 磅
                            val color = br.attr("w:color")?.let { if (it != "auto") "#$it" else "#000000" } ?: "#000000"
                            val bs = when (bv) {
                                "single", "thick", "thinThickSmallGap", "thickThinSmallGap", "thinThickThinSmallGap" -> "solid"
                                "double", "doubleWave" -> "double"
                                "dash", "dashed", "dotDash", "dotDotDash" -> "dashed"
                                "dot", "dotted", "sysDot" -> "dotted"
                                "wave" -> "wavy"
                                else -> "solid"
                            }
                            styles += "border-${side.second}:${"%.2f".format(sz)}pt $bs $color"
                        }
                    }
                }
            }
        }
        styles += "text-align:$align"
        val styleStr = styles.joinToString(";")

        val runsHtml = buildString {
            var runIdx = 0
            for (r in p.childElements()) {
                if (r.local() != "r") continue
                append(convertRun(r, runIdx))
                runIdx++
            }
        }
        if (runsHtml.isEmpty()) return "<p data-block='$blockIdx' class='doc-para' style='$styleStr'><br></p>"
        return "<p data-block='$blockIdx' class='doc-para' style='$styleStr'>$runsHtml</p>"
    }

    private fun convertRun(r: Element, runIdx: Int): String {
        val rPr = r.child("w:rPr")
        val styles = mutableListOf<String>()

        if (rPr != null) {
            // 字体
            rPr.child("w:rFonts")?.let { rf ->
                val ea = rf.attr("w:eastAsia").orEmpty()
                val ascii = rf.attr("w:ascii").orEmpty()
                val font = if (ea.isNotBlank()) ea else ascii
                if (font.isNotBlank()) styles += "font-family:'${font.escapeHtml()}',serif"
            }
            // 字号（半磅 → 磅）
            rPr.child("w:sz")?.attr("w:val")?.toDoubleOrNull()?.let {
                styles += "font-size:${it / 2.0}pt"
            }
            rPr.child("w:szCs")?.attr("w:val")?.toDoubleOrNull()?.let {
                if (styles.none { s -> s.startsWith("font-size:") })
                    styles += "font-size:${it / 2.0}pt"
            }
            // 粗体
            if (isOn(rPr.child("w:b"))) styles += "font-weight:bold"
            // 斜体
            if (isOn(rPr.child("w:i"))) styles += "font-style:italic"
            if (isOn(rPr.child("w:iCs"))) styles += "font-style:italic"
            // 下划线 + 删除线：合并到同一 text-decoration 值，避免简写后者覆盖前者
            // 导致「下划线 + 删除线」同 run 时下划线被吞掉（CSS 中后一条 text-decoration 覆盖前一条）
            val deco = mutableListOf<String>()
            rPr.child("w:u")?.let { u ->
                if (isUnderline(u)) {
                    deco += if (u.attr("w:val").orEmpty() == "double") "underline double" else "underline"
                }
            }
            if (isOn(rPr.child("w:strike"))) deco += "line-through"
            if (deco.isNotEmpty()) styles += "text-decoration:${deco.joinToString(" ")}"
            // 文字颜色
            rPr.child("w:color")?.attr("w:val")?.let { c ->
                if (c != "auto") styles += "color:#$c"
            }
            // 高亮
            rPr.child("w:highlight")?.attr("w:val")?.let { h ->
                val bg = highlightColor(h)
                if (bg != null) styles += "background-color:$bg"
            }
            // 上标/下标
            rPr.child("w:vertAlign")?.attr("w:val")?.let { va ->
                when (va) {
                    "superscript" -> styles += "vertical-align:super;font-size:75%"
                    "subscript" -> styles += "vertical-align:sub;font-size:75%"
                }
            }
            // 小型大写
            if (isOn(rPr.child("w:smallCaps"))) styles += "font-variant:small-caps"
        }

        // 收集文字内容（w:t + w:tab + w:br）
        val text = collectRunText(r)
        val styleStr = if (styles.isNotEmpty()) " style='${styles.joinToString(";")}'" else ""
        return "<span data-run='$runIdx'$styleStr>${text.escapeHtml()}</span>"
    }

    /** 递归收集 run 内的文字：w:t 取字、w:tab → 制表符、w:br → 换行符（导出时由 distributeRuns 拆回） */
    private fun collectRunText(node: Element): String = buildString {
        val nodes = node.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) {
                when (n.local()) {
                    "t" -> append(n.textContent)
                    "tab" -> append("\t")
                    // 关键修复：原代码 "br" -> {} 直接吞掉了 w:br 换行符，导致原文
                    // 「受援人：xxx  承办人：xxx」这种用 w:br 分行的段落被错误地合并成一行。
                    // 现在用 \n 占位，导出会按此切分到原 run。
                    "br" -> append("\n")
                    else -> append(collectRunText(n))
                }
            }
        }
    }

    private fun convertTable(tbl: Element, blockIdx: Int): String {
        val sb = StringBuilder()
        sb.append("<table data-block='$blockIdx' class='doc-table'")

        // 表格宽度
        tbl.child("w:tblPr")?.child("w:tblW")?.let { w ->
            val wVal = w.attr("w:w")?.toDoubleOrNull()
            val wType = w.attr("w:type").orEmpty()
            if (wVal != null && wType == "pct") {
                sb.append(" style='width:${wVal / 50.0}%'")
            }
        }
        sb.append(">")

        // 收集列宽（从 tblGrid）
        val colWidths = mutableListOf<Double>()
        tbl.child("w:tblGrid")?.let { grid ->
            for (gc in grid.childElements()) {
                if (gc.local() == "gridCol") {
                    gc.attr("w:w")?.toDoubleOrNull()?.let { colWidths += it }
                }
            }
        }

        var rowIdx = 0
        for (tr in tbl.childElements()) {
            if (tr.local() != "tr") continue
            sb.append("<tr>")
            var colIdx = 0
            for (tc in tr.childElements()) {
                if (tc.local() != "tc") continue
                sb.append(convertCell(tc, blockIdx, rowIdx, colIdx, colWidths))
                colIdx++
            }
            sb.append("</tr>")
            rowIdx++
        }
        sb.append("</table>")
        return sb.toString()
    }

    private fun convertCell(
        tc: Element, blockIdx: Int, rowIdx: Int, colIdx: Int, colWidths: List<Double>
    ): String {
        val tcPr = tc.child("w:tcPr")
        val styles = mutableListOf<String>()
        val attrs = mutableListOf<String>()

        // 列宽
        tcPr?.child("w:tcW")?.let { w ->
            val wVal = w.attr("w:w")?.toDoubleOrNull()
            val wType = w.attr("w:type").orEmpty()
            if (wVal != null) {
                when (wType) {
                    "pct" -> styles += "width:${wVal / 50.0}%"
                    "dxa" -> styles += "width:${wVal / 20.0}pt"
                }
            }
        }
        // 没有显式列宽时，从 tblGrid 取
        if (styles.none { it.startsWith("width:") } && colIdx < colWidths.size) {
            styles += "width:${colWidths[colIdx] / 20.0}pt"
        }

        // 合并单元格：gridSpan（HTML 属性，非 CSS）
        tcPr?.child("w:gridSpan")?.attr("w:val")?.toIntOrNull()?.let { span ->
            if (span > 1) attrs += "colspan='$span'"
        }
        // vMerge（继续合并）
        tcPr?.child("w:vMerge")?.let { vm ->
            val v = vm.attr("w:val").orEmpty()
            if (v == "restart" || v.isEmpty()) {
                // rowspan 需要后续行配合，此处仅标记
            }
        }

        // 单元格垂直对齐
        tcPr?.child("w:vAlign")?.attr("w:val")?.let { va ->
            when (va) {
                "center" -> styles += "vertical-align:middle"
                "bottom" -> styles += "vertical-align:bottom"
            }
        }

        // 单元格背景色
        tcPr?.child("w:shd")?.attr("w:fill")?.let { fill ->
            if (fill != "auto" && fill.isNotBlank()) styles += "background-color:#$fill"
        }

        val styleStr = if (styles.isNotEmpty()) " style='${styles.joinToString(";")}'" else ""
        val attrStr = if (attrs.isNotEmpty()) " " + attrs.joinToString(" ") else ""

        val runsHtml = buildString {
            var runIdx = 0
            for (p in tc.childElements()) {
                if (p.local() != "p") continue
                for (r in p.childElements()) {
                    if (r.local() != "r") continue
                    append(convertRun(r, runIdx))
                    runIdx++
                }
                // 段落间换行
                if (runIdx > 0) append("<br>")
            }
        }

        return "<td data-block='$blockIdx' data-row='$rowIdx' data-col='$colIdx'$attrStr$styleStr>$runsHtml</td>"
    }

    // ========== 辅助 ==========

    private fun isOn(node: Element?): Boolean {
        if (node == null) return false
        val v = node.attr("w:val")
        if (v == null) return true
        return v == "true" || v == "1" || v == "on"
    }

    private fun isUnderline(u: Element): Boolean {
        val v = u.attr("w:val")
        if (v == null) return true
        return v != "none" && v != "0" && v != "off" && v != "false"
    }

    private fun highlightColor(valStr: String): String? = when (valStr.lowercase()) {
        "yellow" -> "#FFFF00"
        "green" -> "#00FF00"
        "cyan" -> "#00FFFF"
        "magenta" -> "#FF00FF"
        "blue" -> "#0000FF"
        "red" -> "#FF0000"
        "darkblue" -> "#00008B"
        "darkcyan" -> "#008B8B"
        "darkgreen" -> "#006400"
        "darkmagenta" -> "#8B008B"
        "darkred" -> "#8B0000"
        "darkyellow" -> "#808000"
        "darkgray" -> "#A9A9A9"
        "lightgray" -> "#D3D3D3"
        "black" -> "#000000"
        "none" -> null
        else -> null
    }

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
        getAttributeNode(name)?.value?.let { return it }
        getAttributeNode(name.local())?.value?.let { return it }
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

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}