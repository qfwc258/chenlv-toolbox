package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/** 旧版全局覆盖表的数据形状，仅保留用于一次性迁移 */
@Serializable
data class DefaultValueMap(
    val values: Map<String, String> = emptyMap()
)

private object LegacyDefaultValueStore : JsonFileStore<DefaultValueMap>() {
    override val fileName: String = "docgen_defaults.json"
    override fun serializer() = DefaultValueMap.serializer()
    fun loadLegacy(context: Context): Map<String, String> =
        read(context)?.values?.filterKeys { it.isNotBlank() } ?: emptyMap()
}

/**
 * 类型码 → 该类型的覆盖表（key 为默认值有效 key，见 [FieldLabels.defaultKey]）。
 *
 * 维度是「文书类型码」（如 1/5/8/9，与 [DocTypeStore] 的 code 一致），不再按案件结构 id。
 * 这样调查 / 诉讼 / 执行（2/3/4，没有专属字段结构）也能各自存一份默认值，互不干扰。
 * 文件名沿用 `docgen_defaults_by_type.json`，避免升级丢数据；读取时把旧格式
 * （key 为 civil_legal_aid 等 caseTypeId）一次性迁移到对应类型码。
 */
@Serializable
data class ScopedDefaults(
    val byType: Map<String, Map<String, String>> = emptyMap()
)

object DefaultValueStore : JsonFileStore<ScopedDefaults>() {

    override val fileName: String = "docgen_defaults_by_type.json"

    override fun serializer() = ScopedDefaults.serializer()

    /** 旧 caseTypeId → 内置类型码（仅用于一次性迁移，绑定关系来自 DocTypeStore.DEFAULT） */
    private val LEGACY_ID_TO_CODE = mapOf(
        CaseTypeStore.ID_CIVIL_ENTRUST to "1",
        CaseTypeStore.ID_CRIMINAL_ENTRUST to "5",
        CaseTypeStore.ID_CRIMINAL_AID to "8",
        CaseTypeStore.ID_CIVIL_AID to "9"
    )

    /**
     * 读取整表并完成两级迁移：
     *  1. 新文件尚未生成时，把旧版全局覆盖表（docgen_defaults.json，法援口径）挂到默认类型码 9；
     *  2. 新文件已存在但 key 仍是旧 caseTypeId 时，转成对应类型码（同码已有数据则保留新数据）。
     */
    private fun doc(context: Context): ScopedDefaults {
        val raw = read(context)
        if (raw == null) {
            val legacy = LegacyDefaultValueStore.loadLegacy(context)
            if (legacy.isEmpty()) return ScopedDefaults()
            val migrated = ScopedDefaults(mapOf(DocTypeStore.DEFAULT_CODE to legacy))
            write(context, migrated)
            return migrated
        }
        val legacyKeys = raw.byType.keys.filter { it in LEGACY_ID_TO_CODE }
        if (legacyKeys.isEmpty()) return raw
        val byCode = LinkedHashMap(raw.byType.filterKeys { it !in LEGACY_ID_TO_CODE })
        legacyKeys.forEach { id ->
            val code = LEGACY_ID_TO_CODE[id] ?: return@forEach
            val value = raw.byType[id].orEmpty()
            if (code !in byCode && value.isNotEmpty()) byCode[code] = value
        }
        val migrated = ScopedDefaults(byCode)
        write(context, migrated)
        return migrated
    }

    /** 读取某类型码的覆盖表；无记录返回空 */
    fun load(context: Context, code: String): Map<String, String> =
        doc(context).byType[code]?.filterKeys { it.isNotBlank() } ?: emptyMap()

    /** 覆盖保存某类型码的覆盖表（传空表＝该类型恢复内置默认，不影响其他类型） */
    fun save(context: Context, code: String, values: Map<String, String>) {
        val byType = LinkedHashMap(doc(context).byType)
        byType[code] = values
        write(context, ScopedDefaults(byType))
    }

    /** 设置某类型码的单个字段默认值（key 应为默认值有效 key） */
    fun set(context: Context, code: String, defaultKey: String, value: String) {
        val map = LinkedHashMap(load(context, code))
        map[defaultKey] = value
        save(context, code, map)
    }

    /** 删除某类型码的全部覆盖（类型被删除时调用） */
    fun remove(context: Context, code: String) {
        val byType = LinkedHashMap(doc(context).byType)
        if (byType.remove(code) == null) return
        write(context, ScopedDefaults(byType))
    }
}
