package com.wb.mdgw.elementdoc

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 要素式文书草稿存储：所有草稿存于一个 JSON 列表，按 (templateId, caseName) 唯一定位。
 * 复用 [JsonFileStore] 的读写/静默失败机制。
 */
object ElementDraftStore : JsonFileStore<List<ElementDraft>>() {
    override val fileName: String = "element_doc_drafts.json"
    override fun serializer(): KSerializer<List<ElementDraft>> = ListSerializer(ElementDraft.serializer())

    fun all(context: Context): List<ElementDraft> = read(context) ?: emptyList()

    fun find(context: Context, templateId: String, caseName: String): ElementDraft? =
        all(context).firstOrNull { it.templateId == templateId && it.caseName == caseName }

    /** 保存（同 key 覆盖） */
    fun save(context: Context, draft: ElementDraft) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.templateId == draft.templateId && it.caseName == draft.caseName }
        val copy = draft.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = copy else list.add(copy)
        write(context, list)
    }

    fun listForTemplate(context: Context, templateId: String): List<ElementDraft> =
        all(context).filter { it.templateId == templateId }.sortedByDescending { it.updatedAt }

    /** 删除某份草稿 */
    fun delete(context: Context, templateId: String, caseName: String) {
        val list = all(context).filterNot { it.templateId == templateId && it.caseName == caseName }
        write(context, list)
    }
}
