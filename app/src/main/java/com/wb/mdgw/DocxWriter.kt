package com.wb.mdgw

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * 轻量级 OOXML (.docx) 生成引擎。
 *
 * docx 本质是一个 zip 包 + 若干 XML 部件，因此无需引入 POI 之类的重型库，
 * 在 Android 上手写 XML 即可完全掌控公文排版的每一个细节。
 */

// ---------- 单位换算 ----------
/** 磅 -> 半磅（OOXML 字号单位） */
fun ptToHalfPt(pt: Double): Int = (pt * 2).roundToInt()

/** 磅 -> 缇 twips（1 磅 = 20 缇） */
fun ptToTwips(pt: Double): Int = (pt * 20).roundToInt()

/** 厘米 -> 缇（1 厘米 ≈ 566.929 缇） */
fun cmToTwips(cm: Double): Int = (cm * 566.929).roundToInt()

/** XML 文本转义 */
fun xmlEscape(s: String): String = buildString(s.length + 16) {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> {
                // 过滤 XML 1.0 非法控制字符，避免生成损坏的文档
                val code = c.code
                if (code == 0x9 || code == 0xA || code == 0xD ||
                    (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
                ) append(c)
            }
        }
    }
}

/** OOXML w:val 合法的边框线型（ST_Border）：字符边框 / 段落边框直接透传，其余归一到 single */
val ST_BORDER_VALUES = setOf(
    "single", "thick", "double", "dotted", "dashed", "dotDash", "dotDotDash", "doubleWave",
    "wave", "dashSmallGap", "dashDotStroked", "threeDEngrave", "threeDEmboss", "outset", "inset",
    "thinThickSmallGap", "thickThinSmallGap", "thinThickThinSmallGap", "thickBetweenThin"
)

/** OOXML 合法的下划线线型（ST_Underline）：直接透传，其余归一到 single */
val ST_UNDERLINE_VALUES = setOf(
    "single", "double", "thick", "dash", "dashed", "dashLong", "dotDash", "dotDotDash",
    "dotted", "dottedHeavy", "dashDotHeavy", "dashDotDotHeavy", "wave", "wavyDouble",
    "wavyHeavy", "words"
)

/** 对齐方式 */
@Serializable
enum class Align(val v: String) { LEFT("left"), CENTER("center"), RIGHT("right"), BOTH("both") }

/** 一段文字中的一个样式片段 */
@Serializable
data class TextRun(
    val text: String,
    val font: String,
    val sizePt: Double,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 下划线：Word 中带下划线的文字，打开/编辑/保存后仍需保留 */
    val underline: Boolean = false,
    /**
     * 下划线线型（Word w:u/@w:val）：single/double/dash/dotted/wave/...
     * null 表示普通单线（single）。样式层与直接格式均可能声明，需一并保真。
     */
    val underlineStyle: String? = null,
    /** 删除线（strikethrough）：统一到模型路径后需保留，方能使预览与导出保真 */
    val strike: Boolean = false,
    /** 文字颜色（w:color），CSS 十六进制（如 #FF0000）；null 表示默认黑（auto） */
    val color: String? = null,
    /** 高亮（w:highlight）：Word 高亮颜色名（如 yellow）；null 表示无 */
    val highlight: String? = null,
    /** 上下标（w:vertAlign）："superscript" / "subscript"；null 表示正常 */
    val vertAlign: String? = null,
    /** 字符边框（w:rPr/w:bdr）：给单个文字片段加方框，公文中「【】式」强调、
     *  「印章位置」占位框常用。为 null 表示无边框。 */
    val border: ParaBorder? = null
)

/**
 * 段落边框的单条边。
 * @param value 线型（Word 的 w:val）：single/double/dash/dot/wave/.../none/nil
 * @param szPt  线宽（磅）
 * @param color 颜色（CSS 十六进制，如 #000000）
 */
@Serializable
data class ParaBorder(
    val value: String = "single",
    val szPt: Double = 1.0,
    val color: String = "#000000"
)

/** 段落四边边框；某边为 null 表示无边框。公文填空线 / 标题线 / 签名线常用下边框实现。 */
@Serializable
data class ParaBorders(
    val top: ParaBorder? = null,
    val bottom: ParaBorder? = null,
    val left: ParaBorder? = null,
    val right: ParaBorder? = null
)

/**
 * 段落中的制表位（含前导符）。Word 用制表位 + leader（前导符）实现「目录点线」、
 * 「填空下划线」等效果——这正是公文里常见的第二类「下划线」。
 *
 * @param posPt  制表位位置（从段落左边界起算，磅）
 * @param align  制表位对齐：left（左对齐）/ center（居中）/ right（右对齐）/ decimal（小数点）
 * @param leader 前导符：none（无）/ underline（实线）/ dot（点）/ dash（短横线）/ middleDot（中间点）
 *               只有非 none 的 leader 才会在预览/打印里渲染出「线/点」，none 则只是跳到该位置。
 */
@Serializable
data class TabStop(
    val posPt: Double,
    val align: String = "left",
    val leader: String = "none"
)

/**
 * 段落属性。
 * firstLineIndentPt: 首行缩进（磅）；lineSpacingPt: 固定行距（磅）
 */
@Serializable
data class ParaProps(
    val align: Align = Align.LEFT,
    val firstLineIndentPt: Double = 0.0,
    val lineSpacingPt: Double = 28.0,
    val spaceBeforePt: Double = 0.0,
    val spaceAfterPt: Double = 0.0,
    val borders: ParaBorders? = null,
    /** 段落制表位（含前导符）。公务填空线/目录点线常用的第二类「下划线」由它驱动。空列表表示无制表位。 */
    val tabs: List<TabStop> = emptyList()
)

/** 文档中的块级元素 */
@Serializable
sealed class Block {
    @Serializable
    data class Para(val runs: List<TextRun>, val props: ParaProps) : Block()
    @Serializable
    data class Table(val rows: List<List<List<TextRun>>>) : Block()
}

/** 页面设置（默认 A4 + 国标公文页边距） */
@Serializable
data class PageSetup(
    val widthCm: Double = 21.0,
    val heightCm: Double = 29.7,
    val topCm: Double = 3.0,
    val bottomCm: Double = 2.8,
    val leftCm: Double = 2.5,
    val rightCm: Double = 2.5
)

/**
 * 页码样式：Word 页脚与 PDF 页码**共用同一份参数**，从结构上保证两种导出格式的
 * 页码在字号、字体、位置上完全一致。
 *
 * 页码形态固定为「一个居中的阿拉伯数字」，不加任何前后缀。
 *
 * 从 Word 文档打开时，这些值由 [DocxReader] 从原文档页脚中解析得到，因此
 * 「原 Word 有页码 → 转 PDF 也有且样式相同；原 Word 没有 → 转 PDF 也没有」。
 */
@Serializable
data class PageNumStyle(
    /** 字号（磅）。Word 写入 `<w:sz w:val="字号×2">`，PDF 以同样磅值绘制 */
    val fontSizePt: Double = 14.0,
    /** 字体名；空串表示跟随正文字体 */
    val font: String = "",
    /** 页脚距纸张底边的距离（厘米），对应 Word 的 `w:pgMar/@w:footer` */
    val footerDistanceCm: Double = 1.75
)

class DocxWriter(
    private val page: PageSetup = PageSetup(),
    private val defaultFont: String = "仿宋_GB2312",
    private val defaultSizePt: Double = 16.0,
    private val defaultIndentPt: Double = 32.0,
    private val defaultLineSpacingPt: Double = 28.0,
    private val pageNumber: Boolean = false,
    /** 页码样式；与 PDF 导出共用，二者必须取自同一个对象 */
    private val pageNumStyle: PageNumStyle = PageNumStyle()
) {
    private val _blocks = mutableListOf<Block>()

    /** 中文/英文标点字符集合，用于表格智能列宽权重判断。 */
    private val PUNCTUATION_CHARS: Set<Char> = setOf(
        '，', '。', '、', '；', '：', '！', '？',
        '.', ',', ';', ':', '!', '?'
    )

    /** 已收集的块级元素（供预览 / PDF 等其它消费者复用同一份模型） */
    val blocks: List<Block> get() = _blocks

    fun addParagraph(runs: List<TextRun>, props: ParaProps) {
        _blocks += Block.Para(runs, props)
    }

    /** 添加空段落（用于标题后的空行） */
    fun addEmptyParagraph(props: ParaProps = ParaProps(lineSpacingPt = defaultLineSpacingPt)) {
        _blocks += Block.Para(emptyList(), props)
    }

    fun addTable(rows: List<List<List<TextRun>>>) {
        if (rows.isNotEmpty()) _blocks += Block.Table(rows)
    }

    // ---------- XML 片段构造 ----------

    private fun runXml(r: TextRun): String {
        val sb = StringBuilder()
        sb.append("<w:r><w:rPr>")
        val f = xmlEscape(r.font)
        sb.append("<w:rFonts w:ascii=\"").append(f)
            .append("\" w:hAnsi=\"").append(f)
            .append("\" w:eastAsia=\"").append(f)
            .append("\" w:cs=\"").append(f).append("\"/>")
        if (r.bold) sb.append("<w:b/><w:bCs/>")
        if (r.italic) sb.append("<w:i/><w:iCs/>")
        // OOXML 顺序要求：u / strike / color / highlight / vertAlign 必须排在 sz / szCs 之前
        if (r.underline) {
            // 线型透传（ST_Underline 枚举），未知值归一 single
            val uv = r.underlineStyle?.takeIf { it in ST_UNDERLINE_VALUES } ?: "single"
            sb.append("<w:u w:val=\"").append(uv).append("\"/>")
        }
        if (r.strike) sb.append("<w:strike/>")
        // 文字颜色：去掉 # 前缀写回 w:color（Word 端为 6 位 HEX）
        r.color?.let { c ->
            val hex = c.removePrefix("#").trim()
            if (hex.isNotBlank() && hex != "auto") sb.append("<w:color w:val=\"").append(xmlEscape(hex)).append("\"/>")
        }
        // 高亮：Word 高亮颜色名直接透传（yellow/green/...）
        r.highlight?.takeIf { it.isNotBlank() && it != "none" }?.let {
            sb.append("<w:highlight w:val=\"").append(xmlEscape(it)).append("\"/>")
        }
        // 上下标
        when (r.vertAlign) {
            "superscript" -> sb.append("<w:vertAlign w:val=\"superscript\"/>")
            "subscript" -> sb.append("<w:vertAlign w:val=\"subscript\"/>")
        }
        // 字符边框（w:bdr）：公文中单字强调框 / 印章占位框
        r.border?.let { b ->
            // w:bdr 取 ST_Border 枚举值：合法值直接透传，别名与未知值归一到 single
            val v = when (b.value) {
                in ST_BORDER_VALUES -> b.value
                "dash" -> "dashed"
                "dot" -> "dotted"
                else -> "single"
            }
            val color = b.color.removePrefix("#")
            sb.append("<w:bdr w:val=\"").append(v)
                .append("\" w:sz=\"").append((b.szPt * 8).roundToInt().coerceIn(2, 96))
                .append("\" w:space=\"0\" w:color=\"").append(xmlEscape(color)).append("\"/>")
        }
        val hp = ptToHalfPt(r.sizePt)
        sb.append("<w:sz w:val=\"").append(hp).append("\"/>")
        sb.append("<w:szCs w:val=\"").append(hp).append("\"/>")
        sb.append("</w:rPr>")
        // 文字中的 \t / \n 拆成 w:tab / w:br，保证导出 Word 后制表位（含前导线）与软换行同原文一致
        val t = r.text
        if (t.isEmpty()) {
            sb.append("<w:t xml:space=\"preserve\"></w:t>")
        } else {
            var i = 0
            while (i < t.length) {
                when (t[i]) {
                    '\t' -> { sb.append("<w:tab/>"); i++ }
                    '\n' -> { sb.append("<w:br/>"); i++ }
                    else -> {
                        var j = i
                        while (j < t.length && t[j] != '\t' && t[j] != '\n') j++
                        sb.append("<w:t xml:space=\"preserve\">").append(xmlEscape(t.substring(i, j))).append("</w:t>")
                        i = j
                    }
                }
            }
        }
        sb.append("</w:r>")
        return sb.toString()
    }

    /** 注意：OOXML 中 pPr 子元素顺序必须为 spacing -> ind -> jc，必须在 jc 之后紧跟 tabs，否则 Word 会判定文档损坏 */
    private fun pPrXml(p: ParaProps): String {
        val sb = StringBuilder()
        sb.append("<w:pPr>")
        sb.append("<w:spacing w:before=\"").append(ptToTwips(p.spaceBeforePt))
            .append("\" w:after=\"").append(ptToTwips(p.spaceAfterPt))
            .append("\" w:line=\"").append(ptToTwips(p.lineSpacingPt))
            .append("\" w:lineRule=\"exact\"/>")
        sb.append("<w:ind w:firstLine=\"").append(ptToTwips(p.firstLineIndentPt)).append("\"/>")
        sb.append("<w:jc w:val=\"").append(p.align.v).append("\"/>")
        // 制表位（含前导符 leader）：公务填空线/目录点线由它驱动。顺序在 jc 之后、其它 pPr 子项之前
        if (p.tabs.isNotEmpty()) {
            sb.append("<w:tabs>")
            for (t in p.tabs) {
                val al = when (t.align) {
                    "center" -> "center"
                    "right" -> "right"
                    "decimal" -> "decimal"
                    else -> "left"
                }
                val ld = when (t.leader) {
                    "underline", "dot", "dash", "middleDot", "none" -> t.leader
                    else -> "none"
                }
                sb.append("<w:tab w:val=\"").append(al).append("\" w:pos=\"").append(ptToTwips(t.posPt)).append("\"")
                if (ld != "none") sb.append(" w:leader=\"").append(ld).append("\"")
                sb.append("/>")
            }
            sb.append("</w:tabs>")
        }
        sb.append("</w:pPr>")
        return sb.toString()
    }

    private fun paraXml(b: Block.Para): String {
        val sb = StringBuilder()
        sb.append("<w:p>").append(pPrXml(b.props))
        for (r in b.runs) sb.append(runXml(r))
        sb.append("</w:p>")
        return sb.toString()
    }

    /**
     * 根据单元格文本内容智能分配列宽（twip 单位）：
     * - 统计每列的内容权重（中文字≈1.0，西文≈0.55）
     * - 短列（如序号、状态）拿最小宽度，长列拿剩余空间
     */
    private fun smartTableColWidths(
        rows: List<List<List<TextRun>>>,
        totalW: Int,
        minColW: Int = 800  // ~40pt ≈ 200 twips, 足够放序号
    ): List<Int> {
        val colCount = rows.maxOf { it.size }.coerceAtLeast(1)
        if (colCount <= 1) return listOf(totalW)

        // 计算每列的内容权重
        val weights = DoubleArray(colCount)
        for (c in 0 until colCount) {
            var weight = 0.0
            for (row in rows) {
                val cell = row.getOrNull(c) ?: continue
                weight += cellContentWeight(cell)
            }
            weights[c] = weight.coerceAtLeast(1.0)
        }

        // 短列判定：权重低于平均值 30%
        val avgWeight = weights.average()
        val isShortCol = weights.map { it < avgWeight * 0.3 }

        val remaining = (totalW - minColW * colCount).coerceAtLeast(0)
        val totalLongWeight = weights.mapIndexed { idx, w ->
            if (isShortCol[idx]) 0.0 else w
        }.sum()
        val longCount = colCount - isShortCol.count { it }

        val colW = IntArray(colCount)
        for (c in 0 until colCount) {
            if (isShortCol[c]) {
                colW[c] = minColW
            } else {
                val share = if (totalLongWeight > 0 && longCount > 0) {
                    (weights[c] / totalLongWeight * remaining).toInt().coerceAtLeast(minColW)
                } else if (longCount > 0) {
                    (remaining / longCount).toInt().coerceAtLeast(minColW)
                } else {
                    minColW
                }
                colW[c] = share
            }
        }

        // 修正总和 = totalW（最后一列吃余数）
        val sum = colW.sum()
        if (sum != totalW) {
            colW[colCount - 1] += (totalW - sum)
        }
        return colW.toList()
    }

    /** 计算单元格内容的视觉宽度权重：中文≈1.0，西文≈0.55，标点≈0.3。 */
    private fun cellContentWeight(runs: List<TextRun>): Double {
        val text = runs.joinToString("") { it.text }
        if (text.isEmpty()) return 0.5
        var weight = 0.0
        for (c in text) {
            weight += when {
                c in '0'..'9' -> 0.5
                c in PUNCTUATION_CHARS -> 0.3
                c.code in 0x4E00..0x9FFF -> 1.0   // CJK 汉字
                c.code in 0x3000..0x303F -> 0.6   // CJK 兼容
                c.isLetterOrDigit() -> 0.55
                else -> 0.5
            }
        }
        return weight
    }

    private fun tableXml(b: Block.Table): String {
        // 可用正文宽度（缇）
        val usable = cmToTwips(page.widthCm - page.leftCm - page.rightCm)
        val colW = smartTableColWidths(b.rows, usable)

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
        sb.append("</w:tblPr>")

        sb.append("<w:tblGrid>")
        for (w in colW) {
            sb.append("<w:gridCol w:w=\"").append(w).append("\"/>")
        }
        sb.append("</w:tblGrid>")

        for (row in b.rows) {
            sb.append("<w:tr>")
            for (c in colW.indices) {
                val cell = row.getOrNull(c) ?: emptyList()
                sb.append("<w:tc><w:tcPr>")
                sb.append("<w:tcW w:w=\"").append(colW[c]).append("\" w:type=\"dxa\"/>")
                sb.append("<w:vAlign w:val=\"center\"/>")
                sb.append("</w:tcPr>")
                // 表格单元格内不缩进
                sb.append("<w:p>")
                sb.append(pPrXml(ParaProps(
                    align = Align.LEFT,
                    firstLineIndentPt = 0.0,
                    lineSpacingPt = defaultLineSpacingPt
                )))
                for (r in cell) sb.append(runXml(r))
                sb.append("</w:p>")
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        // 表格后必须紧跟一个段落，否则 Word 中两个相邻表格会被合并
        sb.append("<w:p>").append(pPrXml(ParaProps(
            firstLineIndentPt = 0.0, lineSpacingPt = defaultLineSpacingPt
        ))).append("</w:p>")
        return sb.toString()
    }

    private fun sectPrXml(): String {
        val sb = StringBuilder()
        sb.append("<w:sectPr>")
        // 显式声明 A4 尺寸，杜绝部分手机 Office 回退成 Letter
        sb.append("<w:pgSz w:w=\"").append(cmToTwips(page.widthCm))
            .append("\" w:h=\"").append(cmToTwips(page.heightCm))
            .append("\" w:orient=\"portrait\" w:code=\"9\"/>")
        sb.append("<w:pgMar w:top=\"").append(cmToTwips(page.topCm))
            .append("\" w:right=\"").append(cmToTwips(page.rightCm))
            .append("\" w:bottom=\"").append(cmToTwips(page.bottomCm))
            .append("\" w:left=\"").append(cmToTwips(page.leftCm))
            .append("\" w:header=\"851\" w:footer=\"")
            .append(cmToTwips(pageNumStyle.footerDistanceCm))
            .append("\" w:gutter=\"0\"/>")
        sb.append("<w:cols w:space=\"425\"/>")
        sb.append("<w:docGrid w:type=\"lines\" w:linePitch=\"312\"/>")
        if (pageNumber) {
            // 引用页脚部件（rId 须与 document.xml.rels 中一致）
            sb.append("<w:footerReference w:type=\"default\" r:id=\"rIdFtr\"/>")
        }
        sb.append("</w:sectPr>")
        return sb.toString()
    }

    private fun documentXml(): String {
        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<w:body>")
        for (b in _blocks) {
            when (b) {
                is Block.Para -> sb.append(paraXml(b))
                is Block.Table -> sb.append(tableXml(b))
            }
        }
        sb.append(sectPrXml())
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun stylesXml(): String {
        val f = xmlEscape(defaultFont)
        val hp = ptToHalfPt(defaultSizePt)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults>
<w:rPrDefault><w:rPr>
<w:rFonts w:ascii="$f" w:hAnsi="$f" w:eastAsia="$f" w:cs="$f"/>
<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr>
<w:spacing w:before="0" w:after="0" w:line="${ptToTwips(defaultLineSpacingPt)}" w:lineRule="exact"/>
<w:ind w:firstLine="${ptToTwips(defaultIndentPt)}"/>
</w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal">
<w:name w:val="Normal"/><w:qFormat/>
<w:pPr>
<w:spacing w:before="0" w:after="0" w:line="${ptToTwips(defaultLineSpacingPt)}" w:lineRule="exact"/>
<w:ind w:firstLine="${ptToTwips(defaultIndentPt)}"/>
</w:pPr>
<w:rPr><w:rFonts w:ascii="$f" w:hAnsi="$f" w:eastAsia="$f" w:cs="$f"/><w:sz w:val="$hp"/><w:szCs w:val="$hp"/></w:rPr>
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

    private fun contentTypesXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
        if (pageNumber) {
            append("""<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
        }
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>""")
    }

    private fun rootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun docRelsXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        if (pageNumber) {
            append("""<Relationship Id="rIdFtr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""")
        }
        append("</Relationships>")
    }

    /**
     * 页脚：PAGE 域页码（Word 打开后自动计算实际页号）。
     *
     * 字号 / 字体 / 加粗 / 对齐 / 前后缀全部取自 [pageNumStyle]——PDF 导出读的是
     * 同一个对象，因此两种格式的页码外观逐项一致。
     */
    private fun footerXml(): String {
        val st = pageNumStyle
        val fontName = st.font.ifBlank { defaultFont }
        val hp = ptToHalfPt(st.fontSizePt)
        val f = xmlEscape(fontName)
        // 三个 run（域开始 / 域代码 / 域结束）的格式必须一致，否则 Word 显示会错乱
        val rPr = "<w:rPr>" +
            "<w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:eastAsia=\"$f\" w:cs=\"$f\"/>" +
            "<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>" +
            "</w:rPr>"

        // PAGE 域：Word 打开后自动计算当前页号，页脚只有这一个居中的数字
        val pageField =
            "<w:r>$rPr<w:fldChar w:fldCharType=\"begin\"/></w:r>" +
                "<w:r>$rPr<w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>" +
                "<w:r>$rPr<w:fldChar w:fldCharType=\"end\"/></w:r>"

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p>
<w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>
$pageField
</w:p>
</w:ftr>"""
    }

    private fun corePropsXml(title: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>${xmlEscape(title)}</dc:title>
<dc:creator>陈律工具箱</dc:creator>
<cp:lastModifiedBy>陈律工具箱</cp:lastModifiedBy>
</cp:coreProperties>"""

    private fun appPropsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
 xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>陈律工具箱 for Android</Application>
</Properties>"""

    /** 打包生成 .docx 字节流 */
    fun build(title: String = "公文"): ByteArray {
        val bos = ByteArrayOutputStream(1 shl 18)
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
            if (pageNumber) put("word/footer1.xml", footerXml())
            put("word/document.xml", documentXml())
        }
        return bos.toByteArray()
    }
}
