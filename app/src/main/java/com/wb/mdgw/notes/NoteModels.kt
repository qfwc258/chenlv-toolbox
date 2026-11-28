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

/**
 * 按 id 去重，保留首次出现的条目（列表按「新→旧」排序，首条即最新）。
 *
 * 历史版本中「新建便签」会先把空白便签写入列表，保存时又插入一条同 id 的副本，
 * 导致 LazyVerticalStaggeredGrid 的 key 重复并崩溃。加载/保存时统一去重作为兜底。
 */
fun List<StickyNote>.dedupeById(): List<StickyNote> = distinctBy { it.id }

/** 按 id 更新；不存在则插到最前。用于替代「无条件 listOf(updated) + notes」 */
fun List<StickyNote>.upsert(note: StickyNote): List<StickyNote> =
    (listOf(note) + this.filter { it.id != note.id }).dedupeById()
