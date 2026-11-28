package com.wb.mdgw.elementdoc

/**
 * 要素式文书表格网格的**唯一事实来源**。
 *
 * Word 导出（SpcDocxBuilder）与 A4 打印预览（ElementDocHtmlBuilder）必须共用本文件，
 * 否则预览中 2/3/4 列要素行的列宽会被浏览器平均分配，与官方原表 / 导出的 Word 不一致。
 *
 * 宽度来自官方示范文本的 twips 实测值，总宽 9412 twips = A4 可用宽度
 * （11906 - 左边距 1360 - 右边距 1134），对应 HTML 内容区 166mm。
 */
object SpcGridLayout {
    const val TOTAL_W = 9412

    /** 网格列定义与各类行的 gridSpan（与 w:tblGrid / w:gridSpan 一一对应） */
    data class GridSet(
        val cols: List<Int>,
        val fullSpan: Int,
        val two: IntArray,
        val three: IntArray,
        val four: IntArray
    )

    /** 按文档中出现的最大列数选择网格，保证与 SpcDocxBuilder 完全一致 */
    fun forDocument(doc: ResolvedSpcDocument): GridSet {
        val has4Col = doc.rows.any { it.kind == "grid" && it.cells.size == 4 }
        val has3Col = doc.rows.any { it.kind == "grid" && it.cells.size == 3 }
        return when {
            has4Col && has3Col -> GridSet(
                listOf(1500, 1060, 2146, 954, 546, 3206), 6,
                intArrayOf(2, 4), intArrayOf(2, 2, 2), intArrayOf(1, 2, 2, 1)
            )
            has4Col -> GridSet(
                listOf(1500, 1060, 2146, 1500, 3206), 5,
                intArrayOf(2, 3), intArrayOf(2, 2, 1), intArrayOf(1, 2, 1, 1)
            )
            has3Col -> GridSet(
                listOf(2560, 3100, 3752), 3,
                intArrayOf(1, 2), intArrayOf(1, 1, 1), intArrayOf(1, 1, 1, 1)
            )
            else -> GridSet(
                listOf(2560, 6852), 2,
                intArrayOf(1, 1), intArrayOf(1, 1, 1), intArrayOf(1, 1, 1, 1)
            )
        }
    }

    /** 附件表列宽：证据目录 / 质证表使用官方固定列宽，其余等分 */
    fun attachmentWidths(att: ResolvedAttachment): List<Int> {
        val colCount = attachmentColCount(att)
        return when {
            colCount == 5 && att.columns.firstOrNull()?.contains("编号") == true &&
                att.columns.getOrNull(1)?.contains("页码") == true ->
                listOf(820, 820, 2600, 1772, 3400)
            colCount == 6 && att.columns.firstOrNull()?.contains("编号") == true &&
                att.columns.getOrNull(2)?.contains("真实性") == true ->
                listOf(760, 2252, 1300, 1300, 1300, 2500)
            else -> {
                val base = TOTAL_W / colCount
                (0 until colCount).map { if (it == colCount - 1) TOTAL_W - base * (colCount - 1) else base }
            }
        }
    }

    fun attachmentColCount(att: ResolvedAttachment): Int =
        maxOf(1, att.columns.size, att.rows.maxOfOrNull { it.size } ?: 1)

    /** twips → 百分比，保留 4 位小数；Locale.ROOT 防止某些系统用逗号小数 */
    fun percent(twips: Int): String =
        String.format(java.util.Locale.ROOT, "%.4f", twips * 100.0 / TOTAL_W) + "%"
}
