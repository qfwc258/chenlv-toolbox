package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 用户自定义字段默认值（JSON 持久化包装类）。
 *
 * key 为「默认值有效 key」（见 [FieldLabels.defaultKey]）：
 *  - 办案过程三元组 gcsj/gcfs/gcnr 按基础 key 存一份，对所有序号生效；
 *  - 其余字段（含 sq1/sq2）按各自 key 独立存储。
 * 仅保存「与内置默认值不同」的覆盖值；清空即恢复内置默认。
 */
@Serializable
data class DefaultValueMap(
    val values: Map<String, String> = emptyMap()
)

/**
 * 字段默认值覆盖表持久化（docgen_defaults.json）。
 *
 * 有效默认值优先级：用户自定义覆盖 → 内置默认（assets/default_shared_text.txt）→ 空。
 */
object DefaultValueStore : JsonFileStore<DefaultValueMap>() {

    override val fileName: String = "docgen_defaults.json"

    override fun serializer() = DefaultValueMap.serializer()

    /** 读取用户自定义默认值覆盖表；文件缺失/解析失败返回空 */
    fun load(context: Context): Map<String, String> =
        read(context)?.values?.filterKeys { it.isNotBlank() } ?: emptyMap()

    /** 整体覆盖保存 */
    fun save(context: Context, values: Map<String, String>) {
        write(context, DefaultValueMap(values))
    }

    /** 设置单个字段默认值（key 应为默认值有效 key） */
    fun set(context: Context, defaultKey: String, value: String) {
        val map = load(context).toMutableMap()
        map[defaultKey] = value
        save(context, map)
    }
}
