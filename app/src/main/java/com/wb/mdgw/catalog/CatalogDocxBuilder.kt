package com.wb.mdgw.catalog

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * 目录文书 .docx 生成器（证据目录 / 归档目录）。
 *
 * 自包含、零 Android、零第三方依赖：手写 OOXML 并打包 zip，与项目现有
 * DocxWriter 同思路，但表格按目录规格定制——支持自定义列宽权重、表头加粗居中、
 * 数据行分对齐、最小行高（空行也有高度）、标题与落款。
 *
 * 表格采用 fixed 布局 + 六边框，列宽由 [CatalogColumn.weight] 按比例分配，
 * 长文本（证明目的 / 项目名）自动换行，证据空行仍保留连续编号，格式恒定。
 */
object CatalogDocxBuilder {

    // ---- 字体 / 字号 ----
    private const val FONT_HEI = "黑体"
    private const val FONT_SONG = "宋体"
    private const val TITLE_SIZE_PT = 16.0   // 三号
    private const val HEADER_SIZE_PT = 10.5  // 五号
    private const val BODY_SIZE_PT = 10.5    // 五号
    private const val FOOTER_SIZE_PT = 12.0  // 小四

    // ---- 页面（A4，略收窄左右边距让表格更宽）----
    private const val PAGE_W_CM = 21.0
    private const val PAGE_H_CM = 29.7
    private const val MARGIN_TOP_CM = 2.2
    private const val MARGIN_BOTTOM_CM = 2.0
    private const val MARGIN_LEFT_CM = 2.2
    private const val MARGIN_RIGHT_CM = 2.2

    // ---- 最小行高（缇，hRule=atLeast，内容多会自动撑开）----
    private const val EVIDENCE_ROW_H = 620   // 证据目录空行约 1.1cm
    private const val ARCHIVE_ROW_H = 460

    private data class CellSpec(val text: String, val bold: Boolean, val align: CatalogAlign)

    // ================= 对外入口 =================

    /** 生成证据目录 docx */
    fun evidence(form: EvidenceForm): ByteArray {
        val totalRows = maxOf(form.items.size, form.minRows.coerceAtLeast(0))
        val rows = ArrayList<List<String>>(totalRows + 1)
        rows += CatalogTemplates.EVIDENCE_COLUMNS.map { it.header }
        for (i in 0 until totalRows) {
            val item = form.items.getOrNull(i)
            rows += listOf(
                (i + 1).toString(),
                item?.name.orEmpty().trim(),
                item?.pages.orEmpty().trim(),
                item?.purpose.orEmpty().trim(),
                item?.source.orEmpty().trim()
            )
        }
        val footer = buildList {
            add("提交人：${form.submitter.trim()}")
            if (form.date.isNotBlank()) add(form.date.trim())
        }
        return build(
            title = form.title,
            columns = CatalogTemplates.EVIDENCE_COLUMNS,
            textRows = rows.drop(1),
            hasHeader = true,
            minRowHeight = EVIDENCE_ROW_H,
            footerLines = footer
        )
    }

    /** 生成案卷归档目录 docx */
    fun archive(form: ArchiveForm): ByteArray {
        val rows = form.items.mapIndexed { i, item ->
            listOf((i + 1).toString(), item.name, item.page.trim())
        }
        return build(
            title = form.title,
            columns = CatalogTemplates.ARCHIVE_COLUMNS,
            textRows = rows,
            hasHeader = true,
            minRowHeight = ARCHIVE_ROW_H,
            footerLines = emptyList()
        )
    }

    // ================= docx 组装 =================

    private fun build(
        title: String,
        columns: List<CatalogColumn>,
        textRows: List<List<String>>,
        hasHeader: Boolean,
        minRowHeight: Int,
        footerLines: List<String>
    ): ByteArray {
        val document = documentXml(title, columns, textRows, hasHeader, minRowHeight, footerLines)
        val bos = ByteArrayOutputStream(1 shl 17)
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypesXml())
            put("_rels/.rels", rootRelsXml())
            put("docProps/core.xml", corePropsXml(title))
            put("docProps/app.xml", appPropsXml())
            put("word/_rels/document.xml.rels", docRelsXml())
            put("word/styles.xml", stylesXml())
            put("word/document.xml", document)
        }
        return bos.toByteArray()
    }

    private fun documentXml(
        title: String,
        columns: List<CatalogColumn>,
        textRows: List<List<String>>,
        hasHeader: Boolean,
        minRowHeight: Int,
        footerLines: List<String>
    ): String {
        val usable = cmToTwips(PAGE_W_CM - MARGIN_LEFT_CM - MARGIN_RIGHT_CM)
        val widths = distributeWidths(columns, usable)
        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        sb.append("<w:body>")

        // 标题
        sb.append(paragraphXml(title, FONT_HEI, TITLE_SIZE_PT, bold = true,
            align = CatalogAlign.CENTER, beforePt = 6.0, afterPt = 12.0, lineAuto = true))

        // 表格
        sb.append(tableXml(columns, widths, textRows, hasHeader, minRowHeight))

        // 表格后必须有一个段落，且作为落款的起始
        sb.append(paragraphXml("", FONT_SONG, BODY_SIZE_PT, bold = false,
            align = CatalogAlign.LEFT, beforePt = 0.0, afterPt = 6.0, lineAuto = true))
        for (line in footerLines) {
            sb.append(paragraphXml(line, FONT_SONG, FOOTER_SIZE_PT, bold = false,
                align = CatalogAlign.RIGHT, beforePt = 2.0, afterPt = 2.0, lineAuto = true))
        }

        // 页面设置
        sb.append("<w:sectPr>")
        sb.append("<w:pgSz w:w=\"").append(cmToTwips(PAGE_W_CM))
            .append("\" w:h=\"").append(cmToTwips(PAGE_H_CM))
            .append("\" w:orient=\"portrait\" w:code=\"9\"/>")
        sb.append("<w:pgMar w:top=\"").append(cmToTwips(MARGIN_TOP_CM))
            .append("\" w:right=\"").append(cmToTwips(MARGIN_RIGHT_CM))
            .append("\" w:bottom=\"").append(cmToTwips(MARGIN_BOTTOM_CM))
            .append("\" w:left=\"").append(cmToTwips(MARGIN_LEFT_CM))
            .append("\" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/>")
        sb.append("<w:cols w:space=\"425\"/>")
        sb.append("<w:docGrid w:linePitch=\"312\"/>")
        sb.append("</w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun tableXml(
        columns: List<CatalogColumn>,
        widths: IntArray,
        textRows: List<List<String>>,
        hasHeader: Boolean,
        minRowHeight: Int
    ): String {
        val colCount = columns.size
        val usable = widths.sum()
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr>")
        sb.append("<w:tblStyle w:val=\"TableGrid\"/>")
        sb.append("<w:tblW w:w=\"").append(usable).append("\" w:type=\"dxa\"/>")
        sb.append("<w:jc w:val=\"center\"/>")
        sb.append("<w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(edge)
                .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>")
        }
        sb.append("</w:tblBorders>")
        sb.append("<w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblCellMar>")
        sb.append("<w:top w:w=\"30\" w:type=\"dxa\"/><w:left w:w=\"70\" w:type=\"dxa\"/>")
        sb.append("<w:bottom w:w=\"30\" w:type=\"dxa\"/><w:right w:w=\"70\" w:type=\"dxa\"/>")
        sb.append("</w:tblCellMar>")
        sb.append("</w:tblPr>")

        sb.append("<w:tblGrid>")
        for (w in widths) sb.append("<w:gridCol w:w=\"").append(w).append("\"/>")
        sb.append("</w:tblGrid>")

        // 表头行
        if (hasHeader) {
            sb.append("<w:tr>")
            sb.append("<w:trPr><w:trHeight w:val=\"").append(minRowHeight)
                .append("\" w:hRule=\"atLeast\"/><w:tblHeader/></w:trPr>")
            for (c in 0 until colCount) {
                sb.append(cellXml(widths[c], columns[c].header, bold = true,
                    align = CatalogAlign.CENTER, font = FONT_HEI, sizePt = HEADER_SIZE_PT))
            }
            sb.append("</w:tr>")
        }

        // 数据行
        for (row in textRows) {
            sb.append("<w:tr>")
            sb.append("<w:trPr><w:trHeight w:val=\"").append(minRowHeight)
                .append("\" w:hRule=\"atLeast\"/></w:trPr>")
            for (c in 0 until colCount) {
                val text = row.getOrNull(c).orEmpty()
                sb.append(cellXml(widths[c], text, bold = false,
                    align = columns[c].dataAlign, font = FONT_SONG, sizePt = BODY_SIZE_PT))
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun cellXml(
        width: Int,
        text: String,
        bold: Boolean,
        align: CatalogAlign,
        font: String,
        sizePt: Double
    ): String {
        val sb = StringBuilder()
        sb.append("<w:tc><w:tcPr>")
        sb.append("<w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
        sb.append("<w:vAlign w:val=\"center\"/>")
        sb.append("</w:tcPr>")
        sb.append("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"10\" w:after=\"10\" w:line=\"260\" w:lineRule=\"auto\"/>")
        sb.append("<w:ind w:firstLine=\"0\" w:firstLineChars=\"0\"/>")
        sb.append("<w:jc w:val=\"").append(align.xml).append("\"/>")
        sb.append("</w:pPr>")
        sb.append(runXml(text, font, sizePt, bold))
        sb.append("</w:p></w:tc>")
        return sb.toString()
    }

    private fun paragraphXml(
        text: String,
        font: String,
        sizePt: Double,
        bold: Boolean,
        align: CatalogAlign,
        beforePt: Double,
        afterPt: Double,
        lineAuto: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"").append(ptToTwips(beforePt))
            .append("\" w:after=\"").append(ptToTwips(afterPt))
        if (lineAuto) sb.append("\" w:line=\"240\" w:lineRule=\"auto\"/>") else sb.append("\"/>")
        sb.append("<w:ind w:firstLine=\"0\" w:firstLineChars=\"0\"/>")
        sb.append("<w:jc w:val=\"").append(align.xml).append("\"/>")
        sb.append("</w:pPr>")
        if (text.isNotEmpty()) sb.append(runXml(text, font, sizePt, bold))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun runXml(text: String, font: String, sizePt: Double, bold: Boolean): String {
        val hp = ptToHalfPt(sizePt)
        val sb = StringBuilder()
        sb.append("<w:r><w:rPr>")
        sb.append("<w:rFonts w:ascii=\"").append(font)
            .append("\" w:hAnsi=\"").append(font)
            .append("\" w:eastAsia=\"").append(font)
            .append("\" w:cs=\"").append(font).append("\"/>")
        if (bold) sb.append("<w:b/><w:bCs/>")
        sb.append("<w:sz w:val=\"").append(hp).append("\"/>")
        sb.append("<w:szCs w:val=\"").append(hp).append("\"/>")
        sb.append("</w:rPr>")
        if (text.isEmpty()) {
            sb.append("<w:t xml:space=\"preserve\"></w:t>")
        } else {
            val lines = text.split("\n")
            lines.forEachIndexed { i, line ->
                if (i > 0) sb.append("<w:br/>")
                sb.append("<w:t xml:space=\"preserve\">").append(esc(line)).append("</w:t>")
            }
        }
        sb.append("</w:r>")
        return sb.toString()
    }

    /** 按权重分配列宽，末列吸收取整误差，保证总和恰为可用宽度 */
    private fun distributeWidths(columns: List<CatalogColumn>, usable: Int): IntArray {
        val widths = IntArray(columns.size)
        val totalWeight = columns.sumOf { it.weight }.coerceAtLeast(1)
        var used = 0
        for (i in 0 until columns.size - 1) {
            widths[i] = (usable.toLong() * columns[i].weight / totalWeight).toInt()
            used += widths[i]
        }
        widths[columns.size - 1] = usable - used
        return widths
    }

    // ================= 静态部件 =================

    private fun stylesXml(): String {
        val hp = ptToHalfPt(BODY_SIZE_PT)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults>
<w:rPrDefault><w:rPr>
<w:rFonts w:ascii="$FONT_SONG" w:hAnsi="$FONT_SONG" w:eastAsia="$FONT_SONG" w:cs="$FONT_SONG"/>
<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr>
<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>
<w:ind w:firstLine="0" w:firstLineChars="0"/>
</w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal">
<w:name w:val="Normal"/><w:qFormat/>
<w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>
<w:ind w:firstLine="0" w:firstLineChars="0"/></w:pPr>
<w:rPr><w:rFonts w:ascii="$FONT_SONG" w:hAnsi="$FONT_SONG" w:eastAsia="$FONT_SONG" w:cs="$FONT_SONG"/>
<w:sz w:val="$hp"/><w:szCs w:val="$hp"/></w:rPr>
</w:style>
<w:style w:type="table" w:styleId="TableGrid">
<w:name w:val="Table Grid"/>
<w:tblPr><w:tblBorders>
<w:top w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:left w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:bottom w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:right w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:insideH w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:insideV w:val="single" w:sz="4" w:space="0" w:color="000000"/>
</w:tblBorders></w:tblPr>
</w:style>
</w:styles>"""
    }

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

    private fun rootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun docRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun corePropsXml(title: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>${esc(title)}</dc:title>
<dc:creator>陈律工具箱</dc:creator>
<cp:lastModifiedBy>陈律工具箱</cp:lastModifiedBy>
</cp:coreProperties>"""

    private fun appPropsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
 xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>陈律工具箱 for Android</Application>
</Properties>"""

    // ---- 单位换算 / 转义（与 DocxWriter 一致，本文件自包含以便纯 JVM 单测）----
    private fun ptToHalfPt(pt: Double): Int = (pt * 2).roundToInt()
    private fun ptToTwips(pt: Double): Int = (pt * 20).roundToInt()
    private fun cmToTwips(cm: Double): Int = (cm * 566.929).roundToInt()

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> {
                val code = c.code
                if (code == 0x9 || code == 0xA || code == 0xD ||
                    (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
                ) append(c)
            }
        }
    }
}
