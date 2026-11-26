package com.wb.mdgw.elementdoc

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * 加载并索引 assets/elementdoc/spc-2025-templates.json（法〔2025〕82号 113 份官方原表）。
 *
 * 语料纯本地、只读，首次加载后驻留内存单例；按领域 → 案由分组，支持角色筛选与关键字搜索。
 */
object SpcCorpusStore {

    private const val ASSET_PATH = "elementdoc/spc-2025-templates.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cached: SpcCorpus? = null

    /** 官方高频精选案由（对齐参照仓库 POPULAR_TEMPLATE_NAMES） */
    private val POPULAR_NAMES = setOf(
        "买卖合同纠纷民事起诉状", "买卖合同纠纷民事答辩状",
        "民间借贷纠纷民事起诉状", "民间借贷纠纷民事答辩状",
        "劳动争议纠纷民事起诉状", "房屋租赁合同纠纷民事起诉状",
        "建设工程施工合同纠纷民事起诉状", "金融借款合同纠纷民事起诉状",
        "机动车交通事故责任纠纷民事起诉状", "物业服务合同纠纷民事起诉状",
        "离婚纠纷民事起诉状", "侵害著作权及邻接权纠纷民事起诉状",
        "侵害商标权纠纷民事起诉状", "侵害发明专利权纠纷民事起诉状",
        "行政处罚行政起诉状", "强制执行申请书"
    )

    /** 读取全量语料（带内存缓存） */
    fun load(context: Context): SpcCorpus {
        cached?.let { return it }
        val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val corpus = json.decodeFromString<SpcCorpus>(text)
        cached = corpus
        return corpus
    }

    /** 按 templateId 精确取一份 */
    fun find(context: Context, templateId: String): SpcTemplate? =
        load(context).templates.firstOrNull { it.id == templateId }

    /** 领域列表（含每领域文书数），按官方顺序 */
    fun domains(context: Context): List<DomainInfo> {
        val all = load(context).templates
        return SpcDomain.ORDER.mapNotNull { id ->
            val items = all.filter { it.domainId == id }
            if (items.isEmpty()) null
            else DomainInfo(id, items.first().domainName, items.size)
        }
    }

    /** 某领域下的文书，可按角色筛选；keyword 命中案由/标题/领域名 */
    fun templates(
        context: Context,
        domainId: String?,
        role: String? = null,
        keyword: String = ""
    ): List<SpcTemplate> {
        var list = load(context).templates
        if (!domainId.isNullOrBlank() && domainId != "popular") {
            list = list.filter { it.domainId == domainId }
        }
        if (!role.isNullOrBlank() && role != "all") {
            list = list.filter { it.docRole == role }
        }
        val kw = keyword.trim()
        if (kw.isNotEmpty()) {
            list = list.filter {
                it.causeName.contains(kw, true) ||
                    it.shortCause.contains(kw, true) ||
                    it.displayName.contains(kw, true) ||
                    it.domainName.contains(kw, true)
            }
        }
        return list
    }

    /** 高频精选 */
    fun popular(context: Context): List<SpcTemplate> =
        load(context).templates.filter { it.displayName in POPULAR_NAMES }

    /** 按案由聚合（领域内同一 causeName 的不同文书角色放一起） */
    fun groupByCause(list: List<SpcTemplate>): List<CauseGroup> {
        val linked = LinkedHashMap<String, MutableList<SpcTemplate>>()
        for (t in list) {
            linked.getOrPut(t.causeName) { mutableListOf() }.add(t)
        }
        return linked.map { (cause, items) -> CauseGroup(cause, items.first().domainName, items) }
    }

    data class DomainInfo(val id: String, val name: String, val count: Int)
    data class CauseGroup(val cause: String, val domainName: String, val templates: List<SpcTemplate>)
}
