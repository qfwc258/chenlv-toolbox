package com.wb.mdgw.elementdoc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 要素式文书草稿备份（对齐参照仓库 serializeSpcDraftBundle / parseSpcDraftBundle）。
 *
 * 把所有模板的草稿打包成一个 JSON 文件，可从本机导出/导入恢复，
 * 便于换机迁移或防止 App 数据被清除。纯 JVM，便于单元测试。
 */
object ElementDraftBackup {

    const val APP_ID = "chenlv-element-doc"
    const val SCHEMA_VERSION = 1

    @Serializable
    data class Bundle(
        val app: String = APP_ID,
        val schemaVersion: Int = SCHEMA_VERSION,
        val exportedAt: String = "",
        val drafts: List<ElementDraft> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 序列化草稿列表为备份 JSON */
    fun serialize(drafts: List<ElementDraft>): String =
        json.encodeToString(Bundle.serializer(), Bundle(drafts = drafts))

    /**
     * 从备份 JSON 解析草稿列表。
     * 校验 app 标识，非法文件抛 IllegalArgumentException（参照仓库同样校验来源）。
     */
    fun parse(text: String): List<ElementDraft> {
        val bundle = runCatching { json.decodeFromString<Bundle>(text) }
            .getOrElse { throw IllegalArgumentException("非要素式文书草稿备份文件（.json）") }
        if (bundle.app != APP_ID) {
            throw IllegalArgumentException("非要素式文书草稿备份文件（app 标识不匹配）")
        }
        return bundle.drafts
    }
}
