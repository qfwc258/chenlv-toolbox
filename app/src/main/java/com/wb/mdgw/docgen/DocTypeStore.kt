package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore

/**
 * 文书类型列表持久化（可增删、重命名）。
 *
 * 用户未自定义 / 文件缺失时返回内置默认 6 项（与 PC 脚本 DOCUMENT_TYPES 对齐）。
 * 「选中了哪些类型」是另一维度，存在 SettingsStore（空集合=全部）。
 */
object DocTypeStore : JsonFileStore<DocTypeList>() {

    override val fileName: String = "docgen_types.json"

    override fun serializer() = DocTypeList.serializer()

    /** 内置默认类型（code 为文件名匹配码，label 为显示名） */
    val DEFAULT: List<DocTypeItem> = listOf(
        DocTypeItem("1", "委托"),
        DocTypeItem("2", "调查"),
        DocTypeItem("3", "诉讼"),
        DocTypeItem("4", "执行"),
        DocTypeItem("8", "法援刑"),
        DocTypeItem("9", "法援民")
    )

    /** 读取类型列表；为空 / 解析失败时回退默认 */
    fun load(context: Context): List<DocTypeItem> =
        read(context)?.items?.filter { it.code.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT

    /** 覆盖保存类型列表 */
    fun save(context: Context, items: List<DocTypeItem>) {
        write(context, DocTypeList(items))
    }
}
