package com.wb.mdgw.notes

import android.content.Context
import com.wb.mdgw.JsonFileStore
import java.util.UUID

/** 便签本地持久化（应用私有 JSON 文件，读写失败静默） */
object NoteStore : JsonFileStore<NoteStoreData>() {
    override val fileName = "sticky_notes.json"
    override fun serializer() = NoteStoreData.serializer()

    fun load(context: Context): List<StickyNote> =
        read(context)?.notes?.dedupeById()
            ?.sortedWith(compareByDescending<StickyNote> { it.pinned }.thenByDescending { it.updatedAt })
            ?: DEFAULT_NOTES

    fun save(context: Context, notes: List<StickyNote>) {
        write(context, NoteStoreData(notes.dedupeById()))
    }

    fun newNote(title: String = "", content: String = ""): StickyNote = StickyNote(
        id = UUID.randomUUID().toString(),
        title = title,
        content = content,
        updatedAt = System.currentTimeMillis()
    )

    private val DEFAULT_NOTES = listOf(
        StickyNote(
            id = "welcome",
            title = "欢迎使用便签",
            content = "这里可以保存常用内容，例如：常用法条引注、律所信息、常用地址、办案口头禅等。\n\n点右上角 + 新建，点便签卡片编辑，长按或点删除按钮移除。",
            pinned = true,
            updatedAt = 0L
        )
    )
}
