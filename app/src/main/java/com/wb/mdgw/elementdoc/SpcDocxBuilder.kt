package com.wb.mdgw.elementdoc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 最高人民法院要素式文书 .docx 渲染器（Kotlin 复刻参照仓库 docxBuilder.ts 的
 * buildOfficialSpcDocxBytes，Apache-2.0）。
 *
 * 生成的 OOXML 严格对齐官方原表版式：A4、封面说明框、章节标题、2/3/4 列表格、
 * 合并单元格（vMerge）、逐字保留的勾选框（□/☑，单独 SimSun run 防 emoji 回退）、
 * 官方落款与签名后附件表（证据清单/质证表）。
 *
 * 纯 JVM，仅依赖 java.util.zip，便于单元测试直接产出字节并校验 XML。
 */
object SpcDocxBuilder {

    private const val TOTAL_W = 9412

    /** 生成 docx 字节 */
    fun build(doc: ResolvedSpcDocument): ByteArray {
        val documentXml = buildDocumentXml(doc)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES_XML)
            put("_rels/.rels", ROOT_RELS_XML)
            put("word/_rels/document.xml.rels", DOC_RELS_XML)
            put("word/styles.xml", STYLES_XML)
            put("word/settings.xml", SETTINGS_XML)
            put("word/fontTable.xml", FONT_TABLE_XML)
            put("word/document.xml", documentXml)
        }
        return bos.toByteArray()
    }

    private fun buildDocumentXml(doc: ResolvedSpcDocument): String {
        val has4Col = doc.rows.any { it.kind == "grid" && it.cells.size == 4 }
        val has3Col = doc.rows.any { it.kind == "grid" && it.cells.size == 3 }

        val (gridCols, fullSpan, twoSpans, threeSpans, fourSpans) = when {
            has4Col && has3Col -> GridSet(listOf(1500, 1060, 2146, 954, 546, 3206), 6,
                intArrayOf(2, 4), intArrayOf(2, 2, 2), intArrayOf(1, 2, 2, 1))
            has4Col -> GridSet(listOf(1500, 1060, 2146, 1500, 3206), 5,
                intArrayOf(2, 3), intArrayOf(2, 2, 1), intArrayOf(1, 2, 1, 1))
            has3Col -> GridSet(listOf(2560, 3100, 3752), 3,
                intArrayOf(1, 2), intArrayOf(1, 1, 1), intArrayOf(1, 1, 1, 1))
            else -> GridSet(listOf(2560, 6852), 2,
                intArrayOf(1, 1), intArrayOf(1, 1, 1), intArrayOf(1, 1, 1, 1))
        }

        val trs = StringBuilder()
        for (i in doc.rows.indices) {
            val r = doc.rows[i]
            when {
                r.kind == "notice" -> trs.append("<w:tr>").append(
                    spcCell(TOTAL_W, fullSpan, contentXml = noticeParagraphs(r.value))
                ).append("</w:tr>")

                r.kind == "section" -> trs.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>").append(
                    spcCell(TOTAL_W, fullSpan, contentXml = sectionParagraphs(r.value))
                ).append("</w:tr>")

                r.kind == "guide" || r.kind == "full_input" -> {
                    val guideOnly = r.kind == "guide" && !r.value.contains('\n')
                    val trHeight = if (guideOnly) "<w:trHeight w:val=\"680\" w:hRule=\"atLeast\"/>" else ""
                    trs.append("<w:tr><w:trPr>").append(trHeight).append("</w:trPr>").append(
                        spcCell(
                            TOTAL_W, fullSpan,
                            vAlign = if (guideOnly) "top" else "center",
                            contentXml = paragraph(r.value, align = "left", size = 21, font = "仿宋",
                                spaceBefore = 20, spaceAfter = 20, line = 290)
                        )
                    ).append("</w:tr>")
                }

                r.kind == "grid" && r.cells.size == 4 -> {
                    val widths = listOf(1500, 3206, 1500, 3206)
                    val cellsXml = StringBuilder()
                    r.cells.forEachIndexed { ci, txt ->
                        val isLabelCol = ci == 0 || ci == 2
                        val align = when {
                            ci == 0 -> labelAlign(txt)
                            ci == 2 -> "center"
                            else -> "left"
                        }
                        cellsXml.append(spcCell(widths[ci], fourSpans[ci], contentXml = paragraph(
                            txt, align = align,
                            bold = isLabelCol, size = 21,
                            font = if (isLabelCol) "宋体" else "仿宋",
                            spaceBefore = 20, spaceAfter = 20, line = 280)))
                    }
                    trs.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>").append(cellsXml).append("</w:tr>")
                }

                r.kind == "grid" && r.cells.size == 3 -> {
                    val nextContinue = doc.rows.getOrNull(i + 1)?.vMergeContinue == true
                    val vm = when {
                        r.vMergeContinue -> "continue"
                        nextContinue -> "restart"
                        else -> null
                    }
                    val widths = listOf(2560, 3100, 3752)
                    val cellsXml = StringBuilder()
                    r.cells.forEachIndexed { ci, txt ->
                        val cellText = if (ci == 0 && vm == "continue") "" else txt
                        cellsXml.append(spcCell(widths[ci], threeSpans[ci], vMerge = if (ci == 0) vm else null,
                            contentXml = paragraph(cellText, align = if (ci == 0) labelAlign(txt) else "left",
                                bold = ci == 0, size = 21,
                                font = if (ci == 0) "宋体" else "仿宋",
                                spaceBefore = 20, spaceAfter = 20, line = 280)))
                    }
                    trs.append("<w:tr>").append(cellsXml).append("</w:tr>")
                }

                else -> {
                    // 标准官方 2 列要素行
                    val leftXml = spcCell(2560, twoSpans[0], contentXml = paragraph(
                        r.label, align = labelAlign(r.label), size = 21, font = "宋体",
                        spaceBefore = 20, spaceAfter = 20, line = 280))
                    val rightXml = spcCell(6852, twoSpans[1], contentXml = paragraph(
                        r.value, align = "left", size = 21, font = "仿宋",
                        spaceBefore = 20, spaceAfter = 20, line = 290))
                    val trPr = if (r.value.length < 320) "<w:trPr><w:cantSplit/></w:trPr>" else ""
                    trs.append("<w:tr>").append(trPr).append(leftXml).append(rightXml).append("</w:tr>")
                }
            }
        }

        val mainTable = StringBuilder("<w:tbl><w:tblPr>")
            .append("<w:tblW w:w=\"$TOTAL_W\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>")
            .append(TABLE_BORDERS)
            .append("<w:tblLayout w:type=\"fixed\"/></w:tblPr>")
            .append("<w:tblGrid>")
            .apply { gridCols.forEach { append("<w:gridCol w:w=\"$it\"/>") } }
            .append("</w:tblGrid>")
            .append(trs)
            .append("</w:tbl>")
            .toString()

        val body = StringBuilder()
        body.append(paragraph(doc.docTitle, align = "center", bold = true, size = 44, font = "宋体",
            spaceBefore = 0, spaceAfter = if (doc.docSubtitle.isNotBlank()) 40 else 140, line = 320))
        if (doc.docSubtitle.isNotBlank()) {
            body.append(paragraph(doc.docSubtitle, align = "center", bold = true, size = 32, font = "宋体",
                spaceBefore = 0, spaceAfter = 140, line = 290))
        }
        body.append(mainTable)
        body.append(paragraph("${doc.signerLine}${doc.signerName.ifBlank { "                    " }}",
            align = "right", size = 24, font = "宋体", spaceBefore = 220, spaceAfter = 60, line = 300))
        body.append(paragraph("${doc.dateLine}${doc.signDate.ifBlank { "      年    月    日" }}",
            align = "right", size = 24, font = "宋体", spaceBefore = 20, spaceAfter = 60, line = 300))
        for (fn in doc.footnotes) {
            val isHeader = fn == "注释：" || fn == "注释:"
            body.append(paragraph(fn, align = "left", bold = isHeader,
                size = if (isHeader) 19 else 19, font = if (isHeader) "宋体" else "仿宋",
                spaceBefore = if (isHeader) 140 else 20, spaceAfter = 30, line = 270))
        }
        for (att in doc.attachments) body.append(attachmentXml(att))

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>" + body + "<w:sectPr>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1360\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
    }

    private data class GridSet(
        val cols: List<Int>,
        val fullSpan: Int,
        val two: IntArray,
        val three: IntArray,
        val four: IntArray
    )

    // ---------------- OOXML 基础构造 ----------------

    private fun escapeXml(raw: String): String = buildString(raw.length + 16) {
        for (ch in raw) {
            when (ch) {
                '\u0000', '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006', '\u0007',
                '\u0008', '\u000B', '\u000C', '\u000E', '\u000F', '\u0010', '\u0011', '\u0012',
                '\u0013', '\u0014', '\u0015', '\u0016', '\u0017', '\u0018', '\u0019', '\u001A',
                '\u001B', '\u001C', '\u001D', '\u001E', '\u001F' -> {}
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun canonicalFont(font: String): String = when (font) {
        "仿宋", "仿宋_GB2312" -> "FangSong"
        "宋体" -> "SimSun"
        "黑体" -> "SimHei"
        "楷体" -> "KaiTi"
        else -> font.ifBlank { "FangSong" }
    }

    /** 一段文本 → w:p；含 □/☑ 时拆成独立 SimSun run，防止彩色 emoji 回退 */
    private fun paragraph(
        text: String,
        align: String = "left",
        bold: Boolean = false,
        size: Int = 21,
        font: String = "仿宋",
        spaceBefore: Int = 40,
        spaceAfter: Int = 40,
        line: Int = 300,
        keepNext: Boolean = false
    ): String {
        val cjk = canonicalFont(font)
        val boldXml = if (bold) "<w:b/><w:bCs/>" else ""
        val keepXml = if (keepNext) "<w:keepNext/>" else ""
        val rPr = "<w:rPr><w:rFonts w:hint=\"eastAsia\" w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"$cjk\" w:cs=\"Times New Roman\"/>$boldXml<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr>"
        val boxRPr = "<w:rPr><w:rFonts w:hint=\"eastAsia\" w:ascii=\"SimSun\" w:hAnsi=\"SimSun\" w:eastAsia=\"SimSun\" w:cs=\"SimSun\"/>$boldXml<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr>"

        val runs = StringBuilder()
        text.split('\n').forEachIndexed { idx, lineText ->
            if (idx > 0) runs.append("<w:r>$rPr<w:br/></w:r>")
            if (!lineText.contains('☑') && !lineText.contains('□')) {
                runs.append("<w:r>$rPr<w:t xml:space=\"preserve\">${escapeXml(lineText)}</w:t></w:r>")
            } else {
                // 按勾选符切分，逐段生成 run。
                // 注意：TS 原版用 split(/([☑□])/g) 带捕获组会保留分隔符；
                // Kotlin 的 String.split(Regex) 会丢弃捕获组，必须手动切分以保留 □/☑。
                val segs = mutableListOf<String>()
                val segBuf = StringBuilder()
                for (ch in lineText) {
                    if (ch == '☑' || ch == '□') {
                        if (segBuf.isNotEmpty()) { segs.add(segBuf.toString()); segBuf.clear() }
                        segs.add(ch.toString())
                    } else {
                        segBuf.append(ch)
                    }
                }
                if (segBuf.isNotEmpty()) segs.add(segBuf.toString())
                for (seg in segs) {
                    val isBox = seg == "☑" || seg == "□"
                    runs.append("<w:r>${if (isBox) boxRPr else rPr}<w:t xml:space=\"preserve\">${escapeXml(seg)}</w:t></w:r>")
                }
            }
        }
        return "<w:p><w:pPr><w:jc w:val=\"$align\"/>$keepXml<w:spacing w:before=\"$spaceBefore\" w:after=\"$spaceAfter\" w:line=\"$line\" w:lineRule=\"auto\"/></w:pPr>$runs</w:p>"
    }

    private fun noticeParagraphs(raw: String): String = buildString {
        for (rawLine in raw.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith('★') && line.endsWith('★') ->
                    append(paragraph(line, align = "left", bold = true, size = 21, font = "宋体",
                        spaceBefore = 60, spaceAfter = 30, line = 280))
                line == "说明：" || line == "说明:" ->
                    append(paragraph(line, align = "left", bold = true, size = 21, font = "宋体",
                        spaceBefore = 20, spaceAfter = 20, line = 280))
                else ->
                    append(paragraph(line, align = "left", size = 20, font = "仿宋",
                        spaceBefore = 10, spaceAfter = 15, line = 270))
            }
        }
    }

    /**
     * 2 列要素行左列 label 的水平对齐：
     *  - 带数字序号的小标题（如「1. 给付价款」「20. 是否签订保证合同」）→ 左对齐
     *  - 调解意愿部分（含「调解」/「纠纷解决方式」）→ 左对齐
     *  - 其余（当事人信息「原告（自然人）」等）→ 居中，保持官方原版式
     * 依据：人民法院在线服务官方示范文本版式；全 113 份语料 label 形态已核对。
     */
    private fun labelAlign(label: String): String {
        val t = label.trimStart()
        if (Regex("^\\d+\\s*[.．、]").containsMatchIn(t)) return "left"
        if (t.contains("调解") || t.contains("纠纷解决方式")) return "left"
        return "center"
    }

    private fun sectionParagraphs(raw: String): String {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return paragraph("", align = "center", bold = true, size = 22, font = "宋体", keepNext = true)
        }
        return buildString {
            lines.forEachIndexed { idx, line ->
                append(paragraph(line, align = "center", bold = idx == 0,
                    size = if (idx == 0) 22 else 19, font = if (idx == 0) "宋体" else "仿宋",
                    spaceBefore = if (idx == 0) 30 else 10,
                    spaceAfter = if (idx == lines.lastIndex) 30 else 10,
                    line = 270, keepNext = true))
            }
        }
    }

    private fun spcCell(
        width: Int,
        gridSpan: Int? = null,
        vMerge: String? = null,
        vAlign: String = "center",
        contentXml: String
    ): String {
        val gs = if (gridSpan != null && gridSpan > 1) "<w:gridSpan w:val=\"$gridSpan\"/>" else ""
        val vm = when (vMerge) {
            "restart" -> "<w:vMerge w:val=\"restart\"/>"
            "continue" -> "<w:vMerge/>"
            else -> ""
        }
        return "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>$gs$vm" +
            "<w:tcMar><w:top w:w=\"75\" w:type=\"dxa\"/><w:bottom w:w=\"75\" w:type=\"dxa\"/>" +
            "<w:left w:w=\"115\" w:type=\"dxa\"/><w:right w:w=\"115\" w:type=\"dxa\"/></w:tcMar>" +
            "<w:vAlign w:val=\"$vAlign\"/></w:tcPr>$contentXml</w:tc>"
    }

    private fun attachmentXml(att: ResolvedAttachment): String {
        val parts = StringBuilder()
        parts.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        parts.append(paragraph(att.label.ifBlank { "附件" }, align = "left", bold = true, size = 22,
            font = "黑体", spaceBefore = 0, spaceAfter = 40, line = 280))
        parts.append(paragraph(att.title, align = "center", bold = true, size = 28, font = "宋体",
            spaceBefore = 20, spaceAfter = if (att.subtitle.isNotBlank()) 40 else 100, line = 300))
        if (att.subtitle.isNotBlank()) {
            parts.append(paragraph(att.subtitle.trim(), align = "center", size = 20, font = "仿宋",
                spaceBefore = 0, spaceAfter = 80, line = 260))
        }

        val colCount = maxOf(1, att.columns.size, att.rows.maxOfOrNull { it.size } ?: 1)
        val colWidths: List<Int> = when {
            colCount == 5 && att.columns.firstOrNull()?.contains("编号") == true &&
                att.columns.getOrNull(1)?.contains("页码") == true -> listOf(820, 820, 2600, 1772, 3400)
            colCount == 6 && att.columns.firstOrNull()?.contains("编号") == true &&
                att.columns.getOrNull(2)?.contains("真实性") == true -> listOf(760, 2252, 1300, 1300, 1300, 2500)
            else -> {
                val base = TOTAL_W / colCount
                (0 until colCount).map { if (it == colCount - 1) TOTAL_W - base * (colCount - 1) else base }
            }
        }
        val fontSz = if (colCount >= 10) 15 else if (colCount >= 7) 17 else 19

        val trs = StringBuilder()
        if (att.columns.isNotEmpty()) {
            val headers = StringBuilder()
            (0 until colCount).forEach { ci ->
                headers.append(spcCell(colWidths[ci], contentXml = paragraph(
                    att.columns.getOrNull(ci) ?: "", align = "center", bold = true, size = fontSz,
                    font = "宋体", spaceBefore = 15, spaceAfter = 15, line = 240)))
            }
            trs.append("<w:tr><w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>").append(headers).append("</w:tr>")
        }
        for (row in att.rows) {
            if (row.size == 1 && colCount > 1) {
                trs.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>").append(
                    spcCell(TOTAL_W, colCount, contentXml = paragraph(
                        row[0], align = "left", bold = true, size = fontSz, font = "宋体",
                        spaceBefore = 15, spaceAfter = 15, line = 250))).append("</w:tr>")
            } else {
                val tds = StringBuilder()
                (0 until colCount).forEach { ci ->
                    tds.append(spcCell(colWidths[ci], contentXml = paragraph(
                        row.getOrNull(ci) ?: "", align = if (ci == 0) "center" else "left",
                        size = fontSz, font = "仿宋", spaceBefore = 15, spaceAfter = 15, line = 250)))
                }
                trs.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>").append(tds).append("</w:tr>")
            }
        }
        parts.append("<w:tbl><w:tblPr><w:tblW w:w=\"$TOTAL_W\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>")
            .append(TABLE_BORDERS).append("<w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
            .apply { colWidths.forEach { append("<w:gridCol w:w=\"$it\"/>") } }
            .append("</w:tblGrid>").append(trs).append("</w:tbl>")

        for (fl in att.footerLines) {
            parts.append(paragraph(fl, align = "right", size = 21, font = "宋体",
                spaceBefore = 80, spaceAfter = 20, line = 280))
        }
        return parts.toString()
    }

    private val TABLE_BORDERS =
        "<w:tblBorders>" +
        "<w:top w:val=\"single\" w:sz=\"6\" w:space=\"0\" w:color=\"000000\"/>" +
        "<w:left w:val=\"single\" w:sz=\"6\" w:space=\"0\" w:color=\"000000\"/>" +
        "<w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"0\" w:color=\"000000\"/>" +
        "<w:right w:val=\"single\" w:sz=\"6\" w:space=\"0\" w:color=\"000000\"/>" +
        "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
        "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" +
        "</w:tblBorders>"

    // ---------------- OOXML 包常量 ----------------

    private val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
  <Override PartName="/word/fontTable.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml"/>
</Types>"""

    private val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private val DOC_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable" Target="fontTable.xml"/>
</Relationships>"""

    private val SETTINGS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:themeFontLang w:val="en-US" w:eastAsia="zh-CN"/>
</w:settings>"""

    private val FONT_TABLE_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:fonts xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:font w:name="FangSong"><w:altName w:val="仿宋"/><w:charset w:val="86"/><w:family w:val="roman"/></w:font>
  <w:font w:name="SimSun"><w:altName w:val="宋体"/><w:charset w:val="86"/><w:family w:val="auto"/></w:font>
  <w:font w:name="SimHei"><w:altName w:val="黑体"/><w:charset w:val="86"/><w:family w:val="modern"/></w:font>
  <w:font w:name="KaiTi"><w:altName w:val="楷体"/><w:charset w:val="86"/><w:family w:val="script"/></w:font>
  <w:font w:name="Times New Roman"><w:charset w:val="00"/><w:family w:val="roman"/></w:font>
</w:fonts>"""

    private val STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults><w:rPrDefault><w:rPr>
    <w:rFonts w:hint="eastAsia" w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="FangSong" w:cs="Times New Roman"/>
    <w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/>
  </w:rPr></w:rPrDefault></w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
</w:styles>"""
}
