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
            // 始终以 blocks 为唯一真源生成编辑目标，不依赖 edits 集合。
            // edits 可能因序列化/反序列化丢失（@Transient originalDocx 被清空后
            // 走 DocxWriter 重建分支，但若 originalDocx 仍在而 edits 异常，
            // 则 edits.isEmpty() 条件不成立时也会丢失修改），
            // 因此直接根据 blocks 内容生成全量编辑目标，确保所有修改不丢失。
            val effectiveEdits = buildSet {
                for ((idx, b) in blocks.withIndex()) {
                    when (b) {
                        is Block.Para -> b.runs.indices.forEach { add(EditTarget(idx, runIndex = it)) }
                        is Block.Table -> b.rows.forEachIndexed { r, row ->
                            row.forEachIndexed { c, _ -> add(EditTarget(idx, r, c)) }
                        }
                    }
                }
            }
            return DocxInPlace.edit(originalDocx, blocks, effectiveEdits)
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
 * 把整段 / 整格的新文字 [newText] 回填到原始各 run，同时尽量保持带格式（下划线 / 粗体 /
 * 斜体）run 的原文在新文字中的位置不变。
 *
 * 算法分两阶段：
 *  1. **智能匹配**：对每个格式化 run，在新文字中搜索其原文；若找到（且位置合理），
 *     则将该 run 原位锁定，确保「用户增删只影响无格式 run，格式化文字始终完整」。
 *  2. **回退切分**：若格式化 run 的原文在新文字中找不到，则回退到比例切分 + 长度锁定，
 *     保证格式不溢出到无格式 run。
 *
 * 每个 run 的 rPr（字体 / 字号 / 下划线 / 粗斜体）在导出时仍按原样保留，
 * 此处只决定各 run 各分到多少字。
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

    val locked = runs.map { it.bold || it.italic || it.underline }
    val hasLocked = locked.any { it }

    // 各 run 在原文中的累计起始位置
    val origStart = mutableListOf(0)
    for (l in lens) origStart += origStart.last() + l

    // ---- 无格式 run：直接比例切分 ----
    if (!hasLocked) {
        val counts = proportionalSplit(lens, newText.length)
        var cursor = 0
        return counts.map { c ->
            val end = (cursor + c).coerceAtMost(newText.length)
            newText.substring(cursor, end).also { cursor = end }
        }
    }

    // ---- 阶段 1：智能匹配格式化 run 的原文 ----
    // 对每个格式化 run，在新文字中搜索其原文，匹配成功则记录精确位置
    data class LockedSpan(val runIndex: Int, val start: Int, val end: Int)
    val matchedSpans = mutableListOf<LockedSpan>()

    for (i in runs.indices) {
        if (!locked[i]) continue
        val runText = runs[i].text
        if (runText.isEmpty()) continue

        // 以比例位置为中心，向外扩展搜索窗口
        val hintStart = (origStart[i].toLong() * newText.length / o).toInt()
        val hintEnd = (origStart[i + 1].toLong() * newText.length / o).toInt()
        val window = (runText.length * 3).coerceAtLeast(30)
        val searchStart = (hintStart - window).coerceAtLeast(0)
        val searchEnd = (hintEnd + window).coerceAtMost(newText.length)

        val found = newText.indexOf(runText, searchStart)
        if (found >= 0 && found < searchEnd) {
            matchedSpans += LockedSpan(i, found, found + runText.length)
        }
    }

    // ---- 阶段 2：构建结果 ----
    if (matchedSpans.isEmpty()) {
        // 所有格式化 run 都未匹配到原文 → 回退到比例切分 + 长度锁定
        return proportionalDistributionWithLocking(lens, locked, newText, origStart)
    }

    // 按新文字中的位置排序，解决重叠（后出现的 span 右移）
    matchedSpans.sortBy { it.start }
    for (j in 1 until matchedSpans.size) {
        val prev = matchedSpans[j - 1]
        if (matchedSpans[j].start < prev.end) {
            val len = matchedSpans[j].end - matchedSpans[j].start
            val newStart = prev.end
            matchedSpans[j] = LockedSpan(
                matchedSpans[j].runIndex,
                newStart,
                (newStart + len).coerceAtMost(newText.length)
            )
        }
    }
    val lockedMap = matchedSpans.associateBy { it.runIndex }

    // 将连续的无格式 run 分组为"间隙"，按顺序填充
    data class Gap(val runIndices: List<Int>, val textStart: Int, val textEnd: Int)
    val gaps = mutableListOf<Gap>()
    val result = MutableList(n) { "" }
    var gapStart = 0
    val gapIndices = mutableListOf<Int>()

    for (i in 0 until n) {
        val span = lockedMap[i]
        if (span != null) {
            if (gapIndices.isNotEmpty()) {
                gaps += Gap(gapIndices.toList(), gapStart, span.start)
                gapIndices.clear()
            }
            result[i] = newText.substring(span.start, span.end)
            gapStart = span.end
        } else {
            gapIndices += i
        }
    }
    if (gapIndices.isNotEmpty()) {
        gaps += Gap(gapIndices.toList(), gapStart, newText.length)
    }

    // 每个间隙内按原始长度比例切分
    for (gap in gaps) {
        val gapText = newText.substring(gap.textStart, gap.textEnd)
        val gapLens = gap.runIndices.map { lens[it] }
        val gapTotal = gapLens.sum()
        if (gapTotal <= 0) {
            if (gap.runIndices.isNotEmpty()) result[gap.runIndices.first()] = gapText
        } else {
            val counts = proportionalSplit(gapLens, gapText.length)
            var c = gap.textStart
            for ((j, idx) in gap.runIndices.withIndex()) {
                val end = (c + counts[j]).coerceAtMost(gap.textEnd)
                result[idx] = newText.substring(c, end)
                c = end
            }
        }
    }

    // 收尾：剩余文字（格式化 run 锁太紧导致总长不足）追加到最后一个 run
    val totalUsed = result.sumOf { it.length }
    if (totalUsed < newText.length) {
        result[n - 1] = result[n - 1] + newText.substring(totalUsed)
    }
    return result
}

/**
 * 回退策略：比例切分 + 格式化 run 长度锁定。
 * 当智能匹配找不到格式化 run 的原文时使用。
 */
private fun proportionalDistributionWithLocking(
    lens: List<Int>, locked: List<Boolean>, newText: String, origStart: List<Int>
): List<String> {
    val n = lens.size
    val o = lens.sum()
    val pos = MutableList(n + 1) { 0 }
    for (i in 1..n) pos[i] = (origStart[i].toLong() * newText.length / o).toInt()
    pos[n] = newText.length

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
