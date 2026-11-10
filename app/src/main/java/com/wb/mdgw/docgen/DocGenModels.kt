package com.wb.mdgw.docgen

import kotlinx.serialization.Serializable

/**
 * 生成文书功能的数据模型。
 *
 * 对应 PC 端 Python 脚本：模板目录 mb/ + 替换规则 shared_text.txt（key::value），
 * 按案件编号、文书类型批量生成保留原格式的 docx。
 */

/**
 * 替换规则文件（shared_text.txt）解析后的「结构化行」。
 *
 * 保留原始的分组 / 注释 / 空行顺序，使手机端表单能按分组折叠展示、
 * 导出时能 1:1 还原 PC 端文本格式。用单一数据类 + type 区分，规避多态序列化。
 *
 * @param type  行类型：[TYPE_GROUP] / [TYPE_COMMENT] / [TYPE_FIELD] / [TYPE_BLANK]
 * @param key   字段 key（仅 TYPE_FIELD，如 "weitr"）
 * @param value 字段值（仅 TYPE_FIELD，可多行）
 * @param text  分组标题或注释正文（GROUP / COMMENT）
 * @param alias 字段本地显示名（仅 TYPE_FIELD，用户自定义的中文备注，仅 App 内显示，
 *              不参与模板替换、不写进导出的 shared_text.txt；旧数据缺省为空）
 */
@Serializable
data class RuleLine(
    val type: String,
    val key: String = "",
    var value: String = "",
    val text: String = "",
    val alias: String = ""
) {
    val isField get() = type == TYPE_FIELD
    val isGroup get() = type == TYPE_GROUP
    val isComment get() = type == TYPE_COMMENT

    companion object {
        const val TYPE_GROUP = "group"     // --委托授权--
        const val TYPE_COMMENT = "comment" // #案件情况
        const val TYPE_FIELD = "field"     // key::value
        const val TYPE_BLANK = "blank"     // 空行

        fun group(title: String) = RuleLine(TYPE_GROUP, text = title)
        fun comment(text: String) = RuleLine(TYPE_COMMENT, text = text)
        fun field(key: String, value: String, alias: String = "") =
            RuleLine(TYPE_FIELD, key = key, value = value, alias = alias)
        fun blank() = RuleLine(TYPE_BLANK)
    }
}

/**
 * 一份「替换规则文档」（即一个案件的全部字段），结构化保存。
 */
@Serializable
data class FieldDoc(
    val lines: List<RuleLine> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 仅取出 key::value 字段，供替换引擎使用（保持文件顺序） */
    fun fieldLines(): List<RuleLine> = lines.filter { it.isField }

    /** 生成 key -> value 映射（同 key 以后者为准，正常文件无重复 key） */
    fun toMap(): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            fieldLines().forEach { put(it.key, it.value) }
        }
}

/**
 * 统一「类型」项（可增删、重命名，持久化保存）。
 *
 * 旧版把「案件类型」（决定字段表单用哪套默认结构）和「文书类型」（决定生成时按文件名
 * 筛选哪批模板）拆成两组，实际是同一件事的两面，用户要选两次且容易选错。
 * 现在合并为一组、只能择一：选中项同时给出模板筛选码与字段结构。
 *
 * @param code       文件名匹配码（数字或字母，如 1/5/8/9）；生成时文件名包含该 code 即入选（对应 PC 的 lx）
 * @param label      显示名（如「民事委托」），可自由重命名
 * @param caseTypeId 该类型对应的字段默认结构 id（[CaseTypeStore] 的资源名）；
 *                   空串表示「不切换字段结构」，沿用当前表单（调查 / 诉讼 / 执行等通用模板）。
 *                   旧持久化数据没有此字段，反序列化时取默认值 ""。
 */
@Serializable
data class DocTypeItem(
    val code: String,
    val label: String,
    val caseTypeId: String = ""
)

/** 文书类型列表（JSON 持久化包装类） */
@Serializable
data class DocTypeList(
    val items: List<DocTypeItem> = emptyList()
)

/**
 * 导入 shared_text.txt 后的合并结果。
 *
 * @param lines   合并完成的完整表单（分组 / 别名 / 顺序沿用当前表单）
 * @param updated 值被文件中同名 key 覆盖的字段数
 * @param added   文件中存在、当前表单缺失而补回的字段数
 */
data class ImportResult(
    val lines: List<RuleLine>,
    val updated: Int,
    val added: Int
)

/**
 * 单个模板的生成结果。
 */
data class GenResult(
    val templateName: String,
    val success: Boolean,
    /** 输出文件名（成功时） */
    val outputName: String? = null,
    /** 展示给用户的保存位置（成功时） */
    val displayPath: String? = null,
    /** 失败原因（失败时） */
    val error: String? = null
)
