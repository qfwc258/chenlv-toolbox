package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore

/**
 * 当前案件的替换规则（字段表单）持久化。
 *
 * 只维护「一份当前规则」，结构（分组/注释/字段顺序）完整保留；
 * 手机端编辑后落盘，重启不丢。需要多案件时可由「导入/导出 shared_text.txt」承载。
 *
 * 「当前案件类型」只决定这份规则**从哪套默认结构长出来**（重置 / 首次生成 / 切类型并入），
 * 并不按类型分文件——切类型走 [DefaultFields.mergeStructure] 保留已填内容。
 */
object FieldRuleStore : JsonFileStore<FieldDoc>() {

    override val fileName: String = "docgen_fields.json"

    override fun serializer() = FieldDoc.serializer()

    /** 读取当前规则；不存在时返回空文档 */
    fun load(context: Context): FieldDoc = read(context) ?: FieldDoc()

    /**
     * 读取当前规则；若用户从未导入 / 填写（为空），则加载当前案件类型的内置默认结构
     * （assets/docgen/defaults/<id>.txt），并套用该类型下用户自定义的字段默认值，
     * 落盘一次，使手机端开箱即有标准字段表单。
     */
    fun loadOrDefault(context: Context, caseTypeId: String): FieldDoc {
        val existing = read(context)
        if (existing != null && existing.lines.isNotEmpty()) return existing
        val doc = buildDefaultDoc(context, caseTypeId)
        if (doc.lines.isNotEmpty()) write(context, doc)
        return doc
    }

    /** 覆盖保存当前规则（失败静默，由基类保证不影响编辑） */
    fun save(context: Context, doc: FieldDoc) = write(context, doc)

    /**
     * 构建某类型的默认结构：解析该类型的资源文件后，套用该类型的用户自定义默认值覆盖表。
     */
    fun buildDefaultDoc(context: Context, caseTypeId: String): FieldDoc {
        val raw = DefaultFields.lines(context, caseTypeId)
        val overrides = DefaultValueStore.load(context, caseTypeId)
        val lines = if (overrides.isEmpty()) raw
        else DefaultFields.applyUserDefaults(context, raw, overrides, caseTypeId)
        return FieldDoc(lines)
    }

    /**
     * 重置为当前类型的内置默认结构（套用该类型的用户自定义默认值，
     * 丢弃用户增删的自定义字段与已填值），落盘并返回，供「重置为默认结构」按钮使用。
     */
    fun resetOrDefault(context: Context, caseTypeId: String): FieldDoc {
        val doc = buildDefaultDoc(context, caseTypeId)
        if (doc.lines.isNotEmpty()) write(context, doc)
        return doc
    }
}
