package com.wb.mdgw

/**
 * 公文全文检索（纯逻辑，不依赖 Compose / Android）。
 *
 * 长公文里常常要找「某一条款 / 某一格」改字，逐屏滚动很低效。
 * 这里把检索抽成纯函数 [search]，遍历段落与表格单元格，按关键字
 * （忽略大小写）匹配，返回命中位置与上下文预览，供 UI 实时列出结果、
 * 点击直接定位编辑。
 */
object GovDocSearch {

    /**
     * 一条命中。
     *
     * @param blockIndex 块序号（段落或表格在 [GovDoc.blocks] 中的下标）
     * @param row        表格行号；段落命中时为 -1
     * @param col        表格列号；段落命中时为 -1
     * @param preview    命中所属块/单元格的完整文本（UI 负责截断展示）
     */
    data class Hit(
        val blockIndex: Int,
        val row: Int,
        val col: Int,
        val preview: String
    )

    /** 检索 [doc] 中是否包含 [query]（trim 后为空直接返回空列表）。忽略大小写。 */
    fun search(doc: GovDoc, query: String): List<Hit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val hits = mutableListOf<Hit>()
        doc.blocks.forEachIndexed { bi, b ->
            when (b) {
                is Block.Para -> {
                    val text = b.runs.joinToString("") { it.text }
                    if (text.contains(q, ignoreCase = true)) {
                        hits += Hit(bi, -1, -1, text)
                    }
                }
                is Block.Table -> b.rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        val text = cell.joinToString("") { it.text }
                        if (text.contains(q, ignoreCase = true)) {
                            hits += Hit(bi, r, c, text)
                        }
                    }
                }
            }
        }
        return hits
    }
}
