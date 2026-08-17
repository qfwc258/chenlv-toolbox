package com.wb.mdgw

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 编辑目标定位：
 *  - row/col 默认 -1 表示整段（段落）或整单元格；
 *  - runIndex = -1 表示「整段 / 整单元格」替换（向后兼容历史编辑）；
 *    runIndex >= 0 表示只编辑该段 / 该单元格首段中第 runIndex 个 run（字段级编辑）。
 */
@Serializable
data class EditTarget(
    val blockIndex: Int,
    val row: Int = -1,
    val col: Int = -1,
    val runIndex: Int = -1
)

/**
 * 转换产出的「公文文档模型」：与具体输出格式（docx / pdf / 屏幕预览）解耦。
 *
 * 同一份模型可以：
 *  ① 序列化为 Word（.docx）—— `toDocx()`
 *  ② 渲染为 PDF（矢量、清晰、体积小）—— `toPdf()`
 *  ③ 在 App 内预览与就地编辑—— `GovDocScreen`
 */
@Serializable
data class GovDoc(
    val blocks: List<Block>,
    val page: PageSetup,
    val title: String,
    val mainTitleFont: String,
    val bodyFont: String,
    val bodySizePt: Double,
    val lineSpacingPt: Double,
    val indentPt: Double,
    /**
     * 是否添加页码。
     *
     * - Markdown 转换：由「公文设置」里的页码开关决定。
     * - 打开 Word 文档：由**原文档页脚中是否存在 PAGE 域**决定——原文档有页码，
     *   导出的 Word / PDF 就都有；原文档没有，就都没有。
     */
    val pageNumber: Boolean = false,
    /**
     * 页码样式（字号 / 字体 / 前后缀 / 对齐 / 页脚距边界）。
     *
     * Word 页脚与 PDF 页码都读这一个对象，因此两种格式的页码必然一致；
     * 打开 Word 文档时该样式由 [DocxReader] 从原文档页脚解析得到。
     */
    val pageNumStyle: PageNumStyle = PageNumStyle(),
    /**
     * 源为 Word 文档时的原始字节。非空表示「打开 Word」场景，
     * 导出 Word 走 [DocxInPlace] 原位修改，从而 100% 保留原格式（表格不变形）。
     * Markdown / 新建场景为 null，导出仍由 [DocxWriter] 重建规范公文。
     */
    @Transient
    val originalDocx: ByteArray? = null,
    /**
     * 用户编辑过的位置集合：段落用 [EditTarget.row]=-1 表示整体替换；
     * 表格单元格用 (blockIndex, row, col) 精确定位。用于导出时只改这些点。
     */
    val edits: Set<EditTarget> = emptySet()
) {
    /** 序列化为 .docx 字节流 */
    fun toDocx(): ByteArray {
        // Word 源：基于原文件原位修改，表格 / 样式 / 文档设置完全保留
        if (originalDocx != null) {
            return DocxInPlace.edit(originalDocx, blocks, edits)
        }
        // Markdown / 新建：由 DocxWriter 重建规范公文
        val w = DocxWriter(
            page, bodyFont, bodySizePt, indentPt, lineSpacingPt,
            pageNumber = pageNumber, pageNumStyle = pageNumStyle
        )
        for (b in blocks) when (b) {
            is Block.Para -> w.addParagraph(b.runs, b.props)
            is Block.Table -> w.addTable(b.rows)
        }
        return w.build(title)
    }

    /**
     * 渲染为 PDF 字节流。
     *
     * 页码由 [PdfExporter] 在排版过程中直接画进每一页，而不是事后用 PDFBox 叠加：
     *  - 叠加方案只能用内置的 Helvetica，画不出「第 1 页」这类中文前后缀，
     *    也用不上文档字体，无法与 Word 页脚保持一致；
     *  - 内置绘制则与正文共用同一套字体解析和页面坐标，位置、字号、字体
     *    全部取自 [pageNumStyle]，与 Word 页脚逐项对齐。
     */
    fun toPdf(): ByteArray = PdfExporter.export(this)

    /**
     * 反向序列化为 Markdown：用于「打开 Word」后在编辑区呈现可编辑源。
     *
     * 不额外添加标题：Word 文档的标题本就是第一个正文段落（[DocxReader] 取首个非空段落
     * 同时写入 [title] 与 [blocks]），若再补一行 `#` 会造成标题重复。因此这里正文段落
     * 按行输出、行内格式映射为 `**粗**` / `*斜*` / `<u>下划线</u>`，表格按 GFM 语法还原，
     * 标题随首段原样呈现，不再单列。
     *
     * 注意：该文本仅作「可编辑源」展示。打开的 Word 文档以原文件为唯一真源，就地编辑
     * （预览区点字直改）即可 100% 保留原字体 / 下划线 / 表格；不要在编辑区改写后从 Markdown
     * 重建，那会丢失原文档的字体与样式。
     */
    fun toMarkdown(): String = buildString {
        for (b in blocks) when (b) {
            is Block.Para -> {
                val t = runsToMd(b.runs)
                if (t.isBlank()) appendLine() else appendLine(t)
            }
            is Block.Table -> {
                if (b.rows.isEmpty()) continue
                val cols = b.rows.first().size
                val rowMd: (List<List<TextRun>>) -> String = { row ->
                    row.joinToString(" | ", "| ", " |") { runsToMd(it) }
                }
                appendLine(rowMd(b.rows.first()))
                appendLine(List(cols) { "---" }.joinToString(" | ", "| ", " |"))
                for (row in b.rows.drop(1)) appendLine(rowMd(row))
                appendLine()
            }
        }
    }
}

/** 把一段 runs 渲染为带行内格式的 Markdown 文本 */
private fun runsToMd(runs: List<TextRun>): String = buildString {
    for (r in runs) {
        val open = buildString { if (r.bold) append("**"); if (r.italic) append("*") }
        val close = buildString { if (r.italic) append("*"); if (r.bold) append("**") }
        var t = "$open${r.text}$close"
        if (r.underline) t = "<u>$t</u>"
        append(t)
    }
}

// ============================================================
// 就地编辑辅助：整段 / 整格「单框编辑」与「按字段拆分」共用
// ============================================================

/**
 * 把整段 / 整格的新文字 [newText] 回填到原始各 run，但**带格式（下划线 / 粗体 / 斜体）的
 * run 保持其原始字符长度不变**，新文字的增减全部由「无格式 run」吸收。
 *
 * 这与 [DocxInPlace] 中 `distributeRespectingFormat` 的语义完全一致：根除「附近无下划线
 * 文字被误加上下划线」的 spill 瑕疵，同时让带下划线的字段（如「（盖章）」）始终完整保住
 * 其下划线、边界不被明文吞掉。每个 run 的 rPr（字体 / 字号 / 下划线 / 粗斜体）在导出时
 * 仍按原样保留，此处只决定各 run 各分到多少字。
 *
 * @return 与 [runs] 等长，每个元素是该 run 应写入的新文字。
 */
fun distributeRunsRespectingFormat(runs: List<TextRun>, newText: String): List<String> {
    val n = runs.size
    if (n == 0) return emptyList()
    if (newText.isEmpty()) return List(n) { "" }

    val lens = runs.map { it.text.length }
    val o = lens.sum()
    if (o <= 0) return List(n) { if (it == 0) newText else "" }

    // 各 run 在原文中的累计起始位置（含结尾 O）
    val origStart = mutableListOf(0)
    for (l in lens) origStart += origStart.last() + l

    // 各 run 边界按比例映射到新文本中的起始位置（取下界），pos.size == n + 1
    val pos = MutableList(n + 1) { 0 }
    for (i in 1..n) pos[i] = (origStart[i].toLong() * newText.length / o).toInt() // 向下取整，避免向左吞并
    pos[n] = newText.length

    // 格式 run 锁定原始长度：平移其右边界，差值由右侧 run 吸收（多为无格式 run）
    val locked = runs.map { it.bold || it.italic || it.underline }
    for (i in locked.indices) {
        if (!locked[i]) continue
        val cur = pos[i + 1] - pos[i]
        val diff = lens[i] - cur
        pos[i + 1] = (pos[i + 1] + diff).coerceIn(pos[i], newText.length)
    }

    val counts = (0 until n).map { pos[it + 1] - pos[it] }
    var cursor = 0
    val out = (0 until n).mapTo(mutableListOf()) { i ->
        val end = (cursor + counts[i]).coerceAtMost(newText.length)
        val s = newText.substring(cursor, end).also { cursor = end }
        s
    }
    // 极端情形：仍有剩余文字（格式 run 全锁且总长不足），追加到最后一个 run
    if (cursor < newText.length) out[n - 1] = out[n - 1] + newText.substring(cursor)
    return out
}

/**
 * 把 [total] 个字符按各 run 原始长度 [lens] 等比切分，返回各 run 应分到的字符数（总和恰为
 * [total]）。用于「按字段拆分」模式下，同一组内（样式一致）的文本回填——同组样式相同，
 * 等比切分不会造成跨样式的下划线污染，且允许带格式字段随用户输入变长。
 */
fun proportionalSplit(lens: List<Int>, total: Int): List<Int> {
    val n = lens.size
    if (n == 0) return emptyList()
    if (total <= 0) return List(n) { 0 }
    val o = lens.sum()
    if (o <= 0) return List(n) { if (it == 0) total else 0 }

    val origStart = mutableListOf(0)
    for (l in lens) origStart += origStart.last() + l
    val pos = MutableList(n + 1) { 0 }
    for (i in 1..n) pos[i] = (origStart[i].toLong() * total / o).toInt()
    pos[n] = total
    val counts = (0 until n).map { pos[it + 1] - pos[it] }.toMutableList()
    // 修正四舍五入累计误差，保证总和恰为 total
    var diff = total - counts.sum()
    var i = 0
    while (diff != 0) {
        val step = if (diff > 0) 1 else -1
        counts[i % n] += step
        diff -= step
        i++
    }
    return counts
}

/**
 * 把相邻、样式（粗 / 斜 / 下划线）相同的 run 合并为一组，用于在编辑弹窗中减少输入框数量，
 * 同时保留每个原始 run 的索引，便于保存时按各自 rPr 回填。
 */
data class RunGroup(
    val runIndices: List<Int>,
    val text: String,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean
)

fun groupRuns(runs: List<TextRun>): List<RunGroup> {
    if (runs.isEmpty()) return emptyList()
    val groups = mutableListOf<RunGroup>()
    for ((i, r) in runs.withIndex()) {
        val last = groups.lastOrNull()
        if (last != null && last.bold == r.bold && last.italic == r.italic && last.underline == r.underline) {
            groups[groups.lastIndex] = last.copy(
                runIndices = last.runIndices + i,
                text = last.text + r.text
            )
        } else {
            groups += RunGroup(listOf(i), r.text, r.bold, r.italic, r.underline)
        }
    }
    return groups
}
