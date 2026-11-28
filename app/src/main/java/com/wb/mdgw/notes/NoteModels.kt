package com.wb.mdgw.notes

import kotlinx.serialization.Serializable

/**
 * 便签：保存常用内容（可修改、增删）。
 * 每条便签包含标题、正文、置顶标记与更新时间。
 */
@Serializable
data class StickyNote(
    val id: String,
    val title: String = "",
    val content: String = "",
    val pinned: Boolean = false,
    val updatedAt: Long = 0L
)

@Serializable
data class NoteStoreData(
    val notes: List<StickyNote> = emptyList()
)
