package com.wb.mdgw.catalog

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * 目录文书 .docx 生成器（证据目录 / 案卷归档目录）。
 *
 * 自包含、零 Android、零第三方依赖：手写 OOXML 并打包 zip。字体、字号、页边距、
 * 固定列宽、行高、表头风格均复刻所里原 Word 模板（已逐格核对）：
 *  - 证据目录：标题楷体_GB2312 二号加粗；表头/正文仿宋_GB2312 小四（12pt），表头不加粗；
 *    固定列宽 711/1632/876/3989/1403；边距上下 2.54、左右 3.17；行高 atLeast。
 *  - 归档目录：标题黑体二号（不加粗）；表头仿宋_GB2312 三号（16pt）加粗，正文小四；
 *    固定列宽 934/7346/1153；边距上下 2.0、左右 2.5；行高自动。
 */
object CatalogDocxBuilder {

    private const val FONT_FANGSONG = "仿宋_GB2312"
    private const val FONT_KAITI = "楷体_GB2312"
    private const val FONT_HEITI = "黑体"

    private const val PAGE_W_CM = 21.0
    private const val PAGE_H_CM = 29.7

    /** 一套目录的完整排版规格 */
    private class Spec(
        val marginTopCm: Double, val marginBottomCm: Double,
        val marginLeftCm: Double, val marginRightCm: Double,
        val titleFont: String, val titleSizePt: Double, val titleBold: Boolean,
        val headerFont: String, val headerSizePt: Double, val headerBold: Boolean,
        val bodyFont: String, val bodySizePt: Double,
        val footerFont: String, val footerSizePt: Double,
        val headerRowHeight: Int?, val dataRowHeight: Int?
    )

    private val EVIDENCE_SPEC = Spec(
        marginTopCm = 2.54, marginBottomCm = 2.54, marginLeftCm = 3.17, marginRightCm = 3.17,
        titleFont = FONT_KAITI, titleSizePt = 22.0, titleBold = true,
        headerFont = FONT_FANGSONG, headerSizePt = 12.0, headerBold = false,
        bodyFont = FONT_FANGSONG, bodySizePt = 12.0,
        footerFont = FONT_FANGSONG, footerSizePt = 12.0,
        headerRowHeight = 587, dataRowHeight = 520
    )

    private val ARCHIVE_SPEC = Spec(
        marginTopCm = 2.0, marginBottomCm = 2.0, marginLeftCm = 2.5, marginRightCm = 2.5,
        titleFont = FONT_HEITI, titleSizePt = 22.0, titleBold = false,
        headerFont = FONT_FANGSONG, headerSizePt = 16.0, headerBold = true,
        bodyFont = FONT_FANGSONG, bodySizePt = 12.0,
        footerFont = FONT_FANGSONG, footerSizePt = 12.0,
        headerRowHeight = null, dataRowHeight = null
    )

    // ================= 对外入口 =================

    /** 生成证据目录 docx */
    fun evidence(form: EvidenceForm): ByteArray {
        val totalRows = maxOf(form.items.size, form.minRows.coerceAtLeast(0))
        val rows = ArrayList<List<String>>(totalRows)
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
            add(form.date.trim().ifBlank { "年          月          日" })
        }
        return build(
            spec = EVIDENCE_SPEC,
            title = form.title.ifBlank { "证 据 目 录" },
            columns = CatalogTemplates.EVIDENCE_COLUMNS,
            dataRows = rows,
            footerLines = footer
        )
    }

    /** 生成案卷归档目录 docx */
    fun archive(form: ArchiveForm): ByteArray {
        val rows = form.items.mapIndexed { i, item ->
            listOf((i + 1).toString(), item.name, item.page.trim())
        }
        return build(
            spec = ARCHIVE_SPEC,
            title = form.title.ifBlank { "宁乡市法律援助案卷归档目录" },
            columns = CatalogTemplates.ARCHIVE_COLUMNS,
            dataRows = rows,
            footerLines = emptyList()
        )
    }

    // ================= docx 组装 =================

    private fun build(
        spec: Spec,
        title: String,
        columns: List<CatalogColumn>,
        dataRows: List<List<String>>,
        footerLines: List<String>
    ): ByteArray {
        val document = documentXml(spec, title, columns, dataRows, footerLines)
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
            put("word/styles.xml", stylesXml(spec))
            put("word/document.xml", document)
        }
        return bos.toByteArray()
    }

    private fun documentXml(
        spec: Spec,
        title: String,
        columns: List<CatalogColumn>,
        dataRows: List<List<String>>,
        footerLines: List<String>
    ): String {
        val widths = columns.map { it.widthDxa }
        val tableWidth = widths.sum()
        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        sb.append("<w:body>")

        // 标题
        sb.append(paragraphXml(title, spec.titleFont, spec.titleSizePt, spec.titleBold,
            CatalogAlign.CENTER, beforePt = 20.0, afterPt = 30.0))

        // 表格
        sb.append(tableXml(spec, columns, widths, tableWidth, dataRows))

        // 表格后必须有一个段落
        sb.append(paragraphXml("", spec.bodyFont, spec.bodySizePt, bold = false,
            align = CatalogAlign.LEFT, beforePt = 0.0, afterPt = 4.0))
        for (line in footerLines) {
            sb.append(paragraphXml(line, spec.footerFont, spec.footerSizePt, bold = false,
                align = CatalogAlign.RIGHT, beforePt = 2.0, afterPt = 2.0))
        }

        // 页面设置
        sb.append("<w:sectPr>")
        sb.append("<w:pgSz w:w=\"").append(cmToTwips(PAGE_W_CM))
            .append("\" w:h=\"").append(cmToTwips(PAGE_H_CM))
            .append("\" w:orient=\"portrait\" w:code=\"9\"/>")
        sb.append("<w:pgMar w:top=\"").append(cmToTwips(spec.marginTopCm))
            .append("\" w:right=\"").append(cmToTwips(spec.marginRightCm))
            .append("\" w:bottom=\"").append(cmToTwips(spec.marginBottomCm))
            .append("\" w:left=\"").append(cmToTwips(spec.marginLeftCm))
            .append("\" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/>")
        sb.append("<w:cols w:space=\"425\"/>")
        sb.append("<w:docGrid w:linePitch=\"312\"/>")
        sb.append("</w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun tableXml(
        spec: Spec,
        columns: List<CatalogColumn>,
        widths: List<Int>,
        tableWidth: Int,
        dataRows: List<List<String>>
    ): String {
        val colCount = columns.size
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr>")
        sb.append("<w:tblStyle w:val=\"TableGrid\"/>")
        sb.append("<w:tblW w:w=\"").append(tableWidth).append("\" w:type=\"dxa\"/>")
        sb.append("<w:jc w:val=\"center\"/>")
        sb.append("<w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(edge)
                .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>")
        }
        sb.append("</w:tblBorders>")
        sb.append("<w:tblLayout w:type=\"fixed\"/>")
        sb.append("<w:tblCellMar>")
        sb.append("<w:top w:w=\"20\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/>")
        sb.append("<w:bottom w:w=\"20\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/>")
        sb.append("</w:tblCellMar>")
        sb.append("</w:tblPr>")

        sb.append("<w:tblGrid>")
        for (w in widths) sb.append("<w:gridCol w:w=\"").append(w).append("\"/>")
        sb.append("</w:tblGrid>")

        // 表头行
        sb.append("<w:tr>")
        sb.append(rowPrXml(spec.headerRowHeight, header = true))
        for (c in 0 until colCount) {
            sb.append(cellXml(widths[c], columns[c].header,
                bold = spec.headerBold, align = CatalogAlign.CENTER,
                font = spec.headerFont, sizePt = spec.headerSizePt))
        }
        sb.append("</w:tr>")

        // 数据行
        for (row in dataRows) {
            sb.append("<w:tr>")
            sb.append(rowPrXml(spec.dataRowHeight, header = false))
            for (c in 0 until colCount) {
                val text = row.getOrNull(c).orEmpty()
                sb.append(cellXml(widths[c], text, bold = false,
                    align = columns[c].dataAlign, font = spec.bodyFont, sizePt = spec.bodySizePt))
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun rowPrXml(height: Int?, header: Boolean): String {
        val sb = StringBuilder()
        if (height != null || header) {
            sb.append("<w:trPr>")
            if (height != null) {
                sb.append("<w:trHeight w:val=\"").append(height).append("\" w:hRule=\"atLeast\"/>")
            }
            if (header) sb.append("<w:tblHeader/>")
            sb.append("</w:trPr>")
        }
        return sb.toString()
    }

    private fun cellXml(
        width: Int, text: String, bold: Boolean, align: CatalogAlign,
        font: String, sizePt: Double
    ): String {
        val sb = StringBuilder()
        sb.append("<w:tc><w:tcPr>")
        sb.append("<w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
        sb.append("<w:vAlign w:val=\"center\"/>")
        sb.append("</w:tcPr>")
        sb.append("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"10\" w:after=\"10\" w:line=\"240\" w:lineRule=\"auto\"/>")
        sb.append("<w:ind w:firstLine=\"0\" w:firstLineChars=\"0\"/>")
        sb.append("<w:jc w:val=\"").append(align.xml).append("\"/>")
        sb.append("</w:pPr>")
        sb.append(runXml(text, font, sizePt, bold))
        sb.append("</w:p></w:tc>")
        return sb.toString()
    }

    private fun paragraphXml(
        text: String, font: String, sizePt: Double, bold: Boolean,
        align: CatalogAlign, beforePt: Double, afterPt: Double
    ): String {
        val sb = StringBuilder()
        sb.append("<w:p><w:pPr>")
        sb.append("<w:spacing w:before=\"").append(ptToTwips(beforePt))
            .append("\" w:after=\"").append(ptToTwips(afterPt))
            .append("\" w:line=\"240\" w:lineRule=\"auto\"/>")
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

    // ================= 静态部件 =================

    private fun stylesXml(spec: Spec): String {
        val hp = ptToHalfPt(spec.bodySizePt)
        val font = spec.bodyFont
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults>
<w:rPrDefault><w:rPr>
<w:rFonts w:ascii="$font" w:hAnsi="$font" w:eastAsia="$font" w:cs="$font"/>
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
<w:rPr><w:rFonts w:ascii="$font" w:hAnsi="$font" w:eastAsia="$font" w:cs="$font"/>
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

    // ---- 单位换算 / 转义（自包含以便纯 JVM 单测）----
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
