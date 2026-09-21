package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore

/**
 * 当前案件的替换规则（字段表单）持久化。
 *
 * 只维护「一份当前规则」，结构（分组/注释/字段顺序）完整保留；
 * 手机端编辑后落盘，重启不丢。需要多案件时可由「导入/导出 shared_text.txt」承载。
 */
object FieldRuleStore : JsonFileStore<FieldDoc>() {

    override val fileName: String = "docgen_fields.json"

    override fun serializer() = FieldDoc.serializer()

    /** 读取当前规则；不存在时返回空文档 */
    fun load(context: Context): FieldDoc = read(context) ?: FieldDoc()

    /**
     * 读取当前规则；若用户从未导入 / 填写（为空），则加载内置的空白法律援助字段结构
     * （assets/docgen/default_shared_text.txt，仅含分组 / 注释 / key，不含真实案件数据），
     * 并落盘一次，使手机端开箱即有标准字段表单。
     */
    fun loadOrDefault(context: Context): FieldDoc {
        val existing = read(context)
        if (existing != null && existing.lines.isNotEmpty()) return existing
        val lines = runCatching {
            context.assets.open("docgen/default_shared_text.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()?.let { SharedTextParser.parse(it) } ?: emptyList()
        val doc = FieldDoc(lines)
        if (lines.isNotEmpty()) write(context, doc)
        return doc
    }

    /** 覆盖保存当前规则（失败静默，由基类保证不影响编辑） */
    fun save(context: Context, doc: FieldDoc) = write(context, doc)
}
