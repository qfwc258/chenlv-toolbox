package com.wb.mdgw.tableform

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 表格填报的「按文件」草稿（JSON 持久化）。
 *
 * 记录最近一次导入的文件名、所选表格、模式 / 表头行，以及已填内容：
 *  - 卡片式：[cardRecords]，每条记录为「数据列 domCell(字符串) -> 值」；
 *  - 清单 / 网格式：[cellValues]，键为 `"domRow_domCell"`。
 * 重新导入同名文件时自动恢复。
 */
@Serializable
data class TableFormDraft(
    val fileName: String = "",
    val tableIndex: Int = 0,
    val mode: String = "",
    val headerRow: Int = 0,
    val cardRecords: List<Map<String, String>> = emptyList(),
    val cellValues: Map<String, String> = emptyMap()
) {
    fun matches(name: String): Boolean = name.isNotBlank() && name == fileName
}

object TableFormDraftStore : JsonFileStore<TableFormDraft>() {
    override val fileName: String = "tableform_draft.json"
    override fun serializer() = TableFormDraft.serializer()
    fun load(context: Context): TableFormDraft? = read(context)
    fun save(context: Context, d: TableFormDraft) = write(context, d)
}
