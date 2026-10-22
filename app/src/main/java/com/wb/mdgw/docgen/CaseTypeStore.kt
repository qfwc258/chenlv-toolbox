package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 案件类型项（民事法援 / 刑事法援 / 民事委托 / 刑事委托，可增删、重命名）。
 *
 * @param id    稳定标识，同时是默认结构资源名 `assets/docgen/defaults/<id>.txt`；
 *              内置四类用有意义的英文 id，用户新增的用 `custom_<时间戳>`（无对应资源，回退到通用结构）
 * @param label 显示名，可自由重命名（重命名不影响 id，因此不会丢默认结构）
 */
@Serializable
data class CaseTypeItem(
    val id: String,
    val label: String
)

/** 案件类型列表（JSON 持久化包装类） */
@Serializable
data class CaseTypeList(
    val items: List<CaseTypeItem> = emptyList()
)

/**
 * 案件类型列表持久化（docgen_case_types.json）。
 *
 * 与 [DocTypeStore] 是两个维度：这里决定「字段表单是哪一套默认结构」，
 * [DocTypeStore] 的 code 只用于生成时按文件名筛选模板。
 *
 * 用户未自定义 / 文件缺失时返回内置四类。
 */
object CaseTypeStore : JsonFileStore<CaseTypeList>() {

    override val fileName: String = "docgen_case_types.json"

    override fun serializer() = CaseTypeList.serializer()

    const val ID_CIVIL_AID = "civil_legal_aid"
    const val ID_CRIMINAL_AID = "criminal_legal_aid"
    const val ID_CIVIL_ENTRUST = "civil_entrust"
    const val ID_CRIMINAL_ENTRUST = "criminal_entrust"

    /** 内置四类；民事法援排在首位，也是升级后首次运行的当前类型 */
    val DEFAULT: List<CaseTypeItem> = listOf(
        CaseTypeItem(ID_CIVIL_AID, "民事法援"),
        CaseTypeItem(ID_CRIMINAL_AID, "刑事法援"),
        CaseTypeItem(ID_CIVIL_ENTRUST, "民事委托"),
        CaseTypeItem(ID_CRIMINAL_ENTRUST, "刑事委托")
    )

    const val DEFAULT_ID = ID_CIVIL_AID

    /** 读取类型列表；id 非法（可能被手工改坏，会被拼进资源路径）/ 列表为空时回退内置四类 */
    fun load(context: Context): List<CaseTypeItem> =
        read(context)?.items?.filter { isValidId(it.id) }?.takeIf { it.isNotEmpty() } ?: DEFAULT

    /** 覆盖保存类型列表 */
    fun save(context: Context, items: List<CaseTypeItem>) {
        write(context, CaseTypeList(items))
    }

    /** id → 显示名；未登记 id 原样返回，保证 UI 不会显示空白 */
    fun labelOf(items: List<CaseTypeItem>, id: String): String =
        items.firstOrNull { it.id == id }?.label
            ?: DEFAULT.firstOrNull { it.id == id }?.label
            ?: id

    private val ID_REGEX = Regex("^[A-Za-z0-9_]{1,32}$")

    /** id 是否可作为资源文件名使用 */
    fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

    /** 为用户新增的类型生成 id；同一毫秒内重复时追加序号 */
    fun newId(existing: Set<String>): String {
        val base = "custom_" + System.currentTimeMillis()
        var i = 0
        var candidate = base
        while (candidate in existing) candidate = "${base}_${++i}"
        return candidate
    }
}
