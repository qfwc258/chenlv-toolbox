package com.wb.mdgw.tableform

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 表格填报的「按文件」草稿（JSON 持久化）。
 *
 * 每个导入过的文件一份，以 [uri] 为主键（[fileName] 兜底）。
 *  - 卡片式：[cardRecords]，每条记录为「数据列 domCell(字符串) -> 值」；
 *  - 清单 / 网格式：[cellValues]，键为 `"domRow_domCell"`。
 * 重新从「最近打开」或文件选择器打开同一文件时自动恢复。
 */
@Serializable
data class TableFormDraft(
    val uri: String = "",
    val fileName: String = "",
    val tableIndex: Int = 0,
    val mode: String = "",
    val headerRow: Int = 0,
    val cardRecords: List<Map<String, String>> = emptyList(),
    val cellValues: Map<String, String> = emptyMap()
)

/**
 * 多份草稿存储（每个文件一份）。
 * 历史版本曾用单文件 `tableform_draft.json`，新版改用 `tableform_drafts.json`（列表）。
 */
object TableFormDraftStore : JsonFileStore<List<TableFormDraft>>() {
    override val fileName: String = "tableform_drafts.json"
    override fun serializer() = ListSerializer(TableFormDraft.serializer())

    /** 按 uri 精确匹配，找不到再按文件名兜底匹配 */
    fun loadFor(context: Context, uri: String, fileName: String): TableFormDraft? {
        val all = read(context).orEmpty()
        return all.firstOrNull { it.uri == uri }
            ?: all.firstOrNull { uri.isNotBlank() && it.uri == "" && it.fileName == fileName }
    }

    /** 保存（覆盖同 uri / 同文件名的旧草稿） */
    fun save(context: Context, d: TableFormDraft) {
        val all = read(context).orEmpty().filterNot {
            (d.uri.isNotBlank() && it.uri == d.uri) ||
                (d.uri.isBlank() && it.uri.isBlank() && it.fileName == d.fileName)
        }.toMutableList()
        all.add(d)
        write(context, all)
    }

    /** 删除某文件的草稿（移除最近记录时一并清理） */
    fun remove(context: Context, uri: String) {
        if (uri.isBlank()) return
        write(context, read(context).orEmpty().filterNot { it.uri == uri })
    }
}
