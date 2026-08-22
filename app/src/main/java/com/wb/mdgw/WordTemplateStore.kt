package com.wb.mdgw

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * 用户自定义文档模板（Word 页「模板」按钮）。
 *
 * 支持 txt / md 两类模板，可存多个；内容在新建 / 编辑模板时固化到本地 JSON，
 * 重启应用后依然保留，供后续文档一键插入复用。
 *
 * 设计要点：
 *  - 复用 [JsonFileStore]：kotlinx.serialization 序列化 `List<WordTemplate>`，零反射；
 *  - 模板内容完整保存（可含多段 markdown），不做裁剪；
 *  - 「应用」动作不在此持久化（仅插入到编辑器），由调用方在点击时用当前光标位置插入。
 */
@Serializable
data class WordTemplate(
    val id: String,
    val name: String,
    /** 模板类型后缀："md" 或 "txt" */
    val ext: String = "md",
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

object WordTemplateStore : JsonFileStore<List<WordTemplate>>() {

    override val fileName: String = "word_templates.json"

    override fun serializer(): KSerializer<List<WordTemplate>> = ListSerializer(WordTemplate.serializer())

    /** 读取全部模板；不存在或解析失败返回空列表 */
    fun load(context: Context): List<WordTemplate> = read(context) ?: emptyList()

    /** 覆盖式保存模板列表（失败静默忽略，绝不影响编辑） */
    fun save(context: Context, templates: List<WordTemplate>) = write(context, templates)
}
