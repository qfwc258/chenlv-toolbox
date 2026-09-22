package com.wb.mdgw.tableform

/**
 * 通用 Word 表格填报的解析模型（纯 JVM，零 Android / 零序列化依赖）。
 *
 * 定位：导入含表格的 .docx，识别表头、空白格、合并单元格，推断手机表单结构；
 * 回填由 [TableFormFiller] 在**原 docx** 上原位写回，边框 / 合并 / 列宽 / 行高 / 字体
 * 100% 保留。
 *
 * 定位约定（关键）：
 *  - 每个单元格用 DOM 定位 [TfCellRef]`(tableIndex, domRow, domCell)`，即文档中第几个
 *    `<w:tbl>`、该表内第几个 `<w:tr>`、该行内第几个 `<w:tc>`。
 *  - 回填只依赖 DOM 顺序，不依赖逻辑网格，因此合并单元格也能稳定定位。
 *  - 逻辑列（gridSpan / vMerge 展开后）仅用于 UI 布局、表头对齐与同列字体克隆。
 */

/** 单元格水平对齐 */
enum class TfAlign { LEFT, CENTER, RIGHT }

/** 手机表单呈现模式：卡片式（记录型）/ 清单式（固定项目+单值）/ 网格兜底式 */
enum class TfMode { CARD, LIST, GRID }

/** 单元格 DOM 定位（与回填器一致） */
data class TfCellRef(val domRow: Int, val domCell: Int)

/**
 * 一个解析出的单元格（按 DOM 顺序，不展开合并）。
 *
 * @param logicalCol 展开 gridSpan / vMerge 后的逻辑起始列（用于 UI 与同列样本）
 * @param colSpan    水平合并跨列数（gridSpan），默认 1
 * @param vMergeStart 是否纵向合并的「起始」格（w:vMerge w:val="restart"）
 * @param vMergeCont  是否纵向合并的「续」格（w:vMerge 无 val / continue）
 * @param sample      同列（逻辑列）第一个有字格的 DOM 位置，空 格回填时克隆其字体
 */
data class TfCell(
    val domRow: Int,
    val domCell: Int,
    val text: String,
    val blank: Boolean,
    val logicalCol: Int,
    val colSpan: Int = 1,
    val vMergeStart: Boolean = false,
    val vMergeCont: Boolean = false,
    val hasImage: Boolean = false,
    val align: TfAlign = TfAlign.LEFT,
    val sample: TfCellRef? = null
) {
    val isVerticalMerge: Boolean get() = vMergeStart || vMergeCont
}

/** 一行（DOM 顺序） */
data class TfRow(val domRow: Int, val cells: List<TfCell>)

/** 一张解析出的表 */
data class TfTable(
    val tableIndex: Int,
    val gridCols: Int,
    val rows: List<TfRow>,
    val hasVerticalMerge: Boolean,
    val mode: TfMode,
    val headerRow: Int,
    /** 序号逻辑列（CARD 模式加行 / 重排用），null 表示无 */
    val seqLogicalCol: Int?
) {
    /** 按 domRow 取行 */
    fun row(domRow: Int): TfRow? = rows.getOrNull(domRow)

    /** 表头行（可能为 null：无表头） */
    val header: TfRow? get() = rows.getOrNull(headerRow)

    /** 数据行（表头行之后） */
    val dataRows: List<TfRow>
        get() = if (headerRow in rows.indices) rows.drop(headerRow + 1) else rows

    /** 逻辑列 -> 表头文字（CARD 字段名） */
    fun headerLabels(): Map<Int, String> {
        val h = header ?: return emptyMap()
        return h.cells.filter { !it.vMergeCont }.associate { it.logicalCol to it.text.trim() }
    }

    /** 平铺所有单元格 */
    val allCells: List<TfCell> get() = rows.flatMap { it.cells }
}

/** 一个文档的解析结果 */
data class TfDoc(val tables: List<TfTable>) {
    fun table(i: Int): TfTable? = tables.getOrNull(i)
}
