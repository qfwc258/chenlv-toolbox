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

/** 类型 id → 该类型的覆盖表（key 为默认值有效 key，见 [FieldLabels.defaultKey]） */
@Serializable
data class ScopedDefaults(
    val byType: Map<String, Map<String, String>> = emptyMap()
)

/**
 * 字段默认值覆盖表持久化（docgen_defaults_by_type.json），按案件类型分开存。
 *
 * 有效默认值优先级：该类型下的用户自定义覆盖 → 该类型的内置默认（assets/docgen/defaults/<id>.txt）→ 空。
 * 升级前的旧文件 docgen_defaults.json 是全局一份、内容为法律援助口径，首次读取时整体挂到
 * 「民事法援」名下（见 [doc]），其余类型从空覆盖开始。
 */
object DefaultValueStore : JsonFileStore<ScopedDefaults>() {

    override val fileName: String = "docgen_defaults_by_type.json"

    override fun serializer() = ScopedDefaults.serializer()

    /** 读取整表；新文件尚未生成时把旧版全局覆盖表一次性迁移到「民事法援」名下 */
    private fun doc(context: Context): ScopedDefaults {
        read(context)?.let { return it }
        val legacy = LegacyDefaultValueStore.loadLegacy(context)
        if (legacy.isEmpty()) return ScopedDefaults()
        val migrated = ScopedDefaults(mapOf(CaseTypeStore.DEFAULT_ID to legacy))
        write(context, migrated)
        return migrated
    }

    /** 读取某类型的用户自定义默认值覆盖表；无记录返回空 */
    fun load(context: Context, caseTypeId: String): Map<String, String> =
        doc(context).byType[caseTypeId]?.filterKeys { it.isNotBlank() } ?: emptyMap()

    /** 覆盖保存某类型的覆盖表（传空表＝该类型全部恢复内置默认，不影响其他类型） */
    fun save(context: Context, caseTypeId: String, values: Map<String, String>) {
        val byType = LinkedHashMap(doc(context).byType)
        byType[caseTypeId] = values
        write(context, ScopedDefaults(byType))
    }

    /** 设置某类型的单个字段默认值（key 应为默认值有效 key） */
    fun set(context: Context, caseTypeId: String, defaultKey: String, value: String) {
        val map = LinkedHashMap(load(context, caseTypeId))
        map[defaultKey] = value
        save(context, caseTypeId, map)
    }

    /** 删除某类型的全部覆盖（类型被删除时调用） */
    fun remove(context: Context, caseTypeId: String) {
        val byType = LinkedHashMap(doc(context).byType)
        if (byType.remove(caseTypeId) == null) return
        write(context, ScopedDefaults(byType))
    }
}
