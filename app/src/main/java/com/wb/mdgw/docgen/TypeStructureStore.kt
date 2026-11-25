package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore

/**
 * 按文书类型码分别保存「主要字段」结构快照。
 *
 * 快照存整份 [FieldDoc]：分组、字段顺序、显示名（alias）、注记与已填值全部保留。
 * 切换类型前把当前表单原样存进旧类型码下；切回时优先恢复，避免模板重建把
 * 用户自定义的字段与排序冲掉。只有「从模板重建」/「重置为默认结构」会清快照。
 *
 * 与 [DefaultValueStore]（按类型码的默认值覆盖表）分开存：默认值管「新字段初始值」，
 * 快照管「这个类型的表单长什么样、填了什么」，互不干扰。
 */
object TypeStructureStore {

    /** 每个类型码一个独立 JSON 文件，复用通用基类的静默失败读写 */
    private class Store(code: String) : JsonFileStore<FieldDoc>() {
        override val fileName: String =
            "docgen_type_${code.filter { it.isLetterOrDigit() || it == '_' }}.json"

        override fun serializer() = FieldDoc.serializer()

        fun load(context: Context): FieldDoc? = read(context)

        fun save(context: Context, doc: FieldDoc) = write(context, doc)
    }

    private fun store(code: String) = Store(code)

    /** 读取某类型的结构快照；无文件 / 空结构返回 null */
    fun load(context: Context, code: String): FieldDoc? =
        if (code.isBlank()) null else store(code).load(context)?.takeIf { it.lines.isNotEmpty() }

    /** 保存某类型的结构快照；空码 / 空结构忽略 */
    fun save(context: Context, code: String, doc: FieldDoc) {
        if (code.isBlank() || doc.lines.isEmpty()) return
        store(code).save(context, doc)
    }

    /** 删除某类型的快照（类型被删 / 强制按模板重建 / 重置结构时） */
    fun clear(context: Context, code: String) {
        if (code.isBlank()) return
        store(code).clear(context)
    }
}
