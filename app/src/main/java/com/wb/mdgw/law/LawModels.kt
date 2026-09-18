package com.wb.mdgw.law

/**
 * 法规领域模型（UI 层使用）
 */
data class Law(
    val id: String,
    val title: String,
    val type: LawType = LawType.LAW,
    val typeName: String = "",
    val lawLevel: String = "",
    val issuingAuthority: String = "",
    val publishDate: String = "",
    val effectiveDate: String = "",
    val documentNumber: String = "",
    val summary: String = "",
    val content: String = "",
    val detailUrl: String? = null,
    /** 效力状态: 1=已修改, 2=已废止, 3=现行有效 */
    val status: Int? = null
) {
    /** 效力状态文本 */
    val statusText: String
        get() = when (status) {
            1 -> "已修改"
            2 -> "已废止"
            3 -> "现行有效"
            else -> "未知"
        }
}

/**
 * 法规类型
 */
enum class LawType(val label: String) {
    ALL("全部"),
    LAW("法律"),
    ADMIN("行政法规"),
    LOCAL("地方性法规"),
    RULE("部门规章"),
    OTHER("其他")
}

/**
 * 搜索模式
 */
enum class SearchMode(val label: String) {
    FUZZY("模糊"),
    EXACT("精确")
}

/**
 * 排序方式
 */
enum class SortOrder(val label: String) {
    PUBLISH_DESC("公布日期↓"),
    PUBLISH_ASC("公布日期↑"),
    RELEVANCE("相关度")
}

/**
 * 全局常量
 */
object LawConstants {
    /** 国家法律法规数据库官网 */
    const val OFFICIAL_URL = "https://flk.npc.gov.cn/"

    /** User-Agent（模拟 Edge 浏览器） */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36 EdgA/129.0.0.0"

    /** 每页默认条数 */
    const val PAGE_SIZE = 10
}

/**
 * 搜索结果封装
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
    object Loading : Result<Nothing>()
}
