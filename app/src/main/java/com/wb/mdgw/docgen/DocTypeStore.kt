package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore
import com.wb.mdgw.SettingsStore

/**
 * 统一「类型」列表持久化（可增删、重命名）。
 *
 * 与旧版的区别：案件类型（字段结构）与文书类型（模板筛选码）合并为同一组，
 * 选中项只有一个，同时决定「生成哪些模板」和「字段表单用哪套默认结构」。
 *
 * 兼容升级：
 *  - 旧 `docgen_types.json` 只有 code / label，反序列化后 [DocTypeItem.caseTypeId] 为空，
 *    [load] 会按 code 补齐映射；若与旧内置 6 项完全一致，则直接换成新的 7 项内置列表。
 *  - 旧设置里的 `docgen_case_type` / `docgen_selected_types` 由 [currentCode] 一次性迁移为单选 code。
 */
object DocTypeStore : JsonFileStore<DocTypeList>() {

    override val fileName: String = "docgen_types.json"

    override fun serializer() = DocTypeList.serializer()

    /** 旧版内置 6 项，仅用于识别「用户从未自定义过类型」的旧数据 */
    private val LEGACY_DEFAULT: List<DocTypeItem> = listOf(
        DocTypeItem("1", "委托"),
        DocTypeItem("2", "调查"),
        DocTypeItem("3", "诉讼"),
        DocTypeItem("4", "执行"),
        DocTypeItem("8", "法援刑"),
        DocTypeItem("9", "法援民")
    )

    /**
     * 内置统一类型（code 为文件名匹配码，label 为显示名，caseTypeId 为字段默认结构）。
     * 委托按民事 / 刑事拆成 1 / 5，法援按刑事 / 民事为 8 / 9；
     * 调查 / 诉讼 / 执行没有专属字段结构，沿用当前表单。
     */
    val DEFAULT: List<DocTypeItem> = listOf(
        DocTypeItem("1", "民事委托", CaseTypeStore.ID_CIVIL_ENTRUST),
        DocTypeItem("2", "调查"),
        DocTypeItem("3", "诉讼"),
        DocTypeItem("4", "执行"),
        DocTypeItem("5", "刑事委托", CaseTypeStore.ID_CRIMINAL_ENTRUST),
        DocTypeItem("8", "刑事法援", CaseTypeStore.ID_CRIMINAL_AID),
        DocTypeItem("9", "民事法援", CaseTypeStore.ID_CIVIL_AID)
    )

    /** 首次运行的默认选中项：与旧版默认案件类型（民事法援）保持一致 */
    const val DEFAULT_CODE = "9"

    /** code → 内置字段结构 id；没有专属结构的类型返回空串 */
    fun caseTypeIdFor(code: String): String = when (code) {
        "1" -> CaseTypeStore.ID_CIVIL_ENTRUST
        "5" -> CaseTypeStore.ID_CRIMINAL_ENTRUST
        "8" -> CaseTypeStore.ID_CRIMINAL_AID
        "9" -> CaseTypeStore.ID_CIVIL_AID
        else -> ""
    }

    /** 读取类型列表；为空 / 解析失败 / 旧内置列表时回退到新的内置 7 项 */
    fun load(context: Context): List<DocTypeItem> {
        val raw = read(context)?.items?.filter { it.code.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: return DEFAULT
        val isPristineLegacy = raw.size == LEGACY_DEFAULT.size && raw.indices.all { i ->
            raw[i].code == LEGACY_DEFAULT[i].code && raw[i].label == LEGACY_DEFAULT[i].label
        }
        if (isPristineLegacy) {
            save(context, DEFAULT)
            return DEFAULT
        }
        // 用户自定义过的旧列表：保留其 code / label，只补齐缺失的字段结构
        return raw.map { item ->
            if (item.caseTypeId.isNotBlank()) item
            else item.copy(caseTypeId = caseTypeIdFor(item.code))
        }
    }

    /** 覆盖保存类型列表 */
    fun save(context: Context, items: List<DocTypeItem>) {
        write(context, DocTypeList(items))
    }

    /**
     * 当前选中的类型 code。优先读新的单选设置；没有时从旧设置迁移：
     * 旧案件类型 → 对应的 code → 旧多选的第一个 code → 内置默认 code。
     */
    fun currentCode(context: Context, items: List<DocTypeItem>): String {
        SettingsStore.docgenSelectedType(context)
            ?.takeIf { code -> items.any { it.code == code } }
            ?.let { return it }
        val legacyCase = SettingsStore.docgenCaseType(context)
        items.firstOrNull { it.caseTypeId.isNotBlank() && it.caseTypeId == legacyCase }
            ?.let { return it.code }
        SettingsStore.docgenSelectedTypes(context)
            .firstOrNull { code -> items.any { it.code == code } }
            ?.let { return it }
        return DEFAULT_CODE.takeIf { code -> items.any { it.code == code } }
            ?: items.first().code
    }

    /** 选中类型对应的字段结构 id（空串 = 不切换字段结构） */
    fun selectedCaseTypeId(items: List<DocTypeItem>, code: String): String =
        items.firstOrNull { it.code == code }?.caseTypeId.orEmpty()
}
