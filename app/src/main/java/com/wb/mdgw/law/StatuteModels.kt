package com.wb.mdgw.law

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 参照源码 qwertyzhu/cn-lawyer-ai-toolbox 的离线法规库数据模型。
 * 源数据：public/data/laws-offline.json（民法典 1260 条 + 40 份配套司法解释）。
 */

@Serializable
data class StatuteCorpus(
    val generatedAt: String = "",
    val civilCount: Int = 0,
    val docCount: Int = 0,
    val totalArticles: Int = 0,
    val civilArticles: List<CivilArticle> = emptyList(),
    val companionDocs: List<CompanionDoc> = emptyList(),
    val dataAsOf: String = "",
    val builtAt: String = ""
)

@Serializable
data class InterpRef(
    @SerialName("docTitle") val docTitle: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("snippet") val snippet: String = ""
)

@Serializable
data class CivilArticle(
    val no: Int,
    val label: String = "",
    val book: String = "",
    val chapter: String = "",
    val section: String = "",
    val summary: String = "",
    val text: String = "",
    val proviso: Boolean = false,
    val defaultRule: Boolean = false,
    val litTips: List<String> = emptyList(),
    val nonLitTips: List<String> = emptyList(),
    val interpRefs: List<InterpRef> = emptyList()
)

@Serializable
data class CompanionDocItem(
    val no: Int,
    val label: String = "",
    val section: String = "",
    val text: String = "",
    val linkedCivil: List<Int> = emptyList()
)

@Serializable
data class CompanionDoc(
    val id: String,
    val kind: String = "",
    val shortTitle: String = "",
    val fullTitle: String = "",
    val docNo: String = "",
    val count: Int = 0,
    val items: List<CompanionDocItem> = emptyList()
)

/** 搜索引擎归一化后的单一条目 */
data class StatuteEntry(
    val key: String,
    val lawTitle: String,
    val fullLawTitle: String,
    val articleNo: Int,
    val articleLabel: String,
    val breadcrumb: String,
    val summary: String = "",
    val text: String = "",
    val litTips: List<String> = emptyList(),
    val nonLitTips: List<String> = emptyList(),
    val interpRefs: List<InterpRef> = emptyList(),
    val isCivil: Boolean = false,
    val isInterp: Boolean = false
)

/** 核对清单 */
@Serializable
data class ChecklistSection(
    val heading: String = "",
    val items: List<String> = emptyList()
)

@Serializable
data class PracticeChecklist(
    val id: String = "",
    val tag: String = "",
    val title: String = "",
    val subtitle: String = "",
    val csvHref: String = "",
    val sections: List<ChecklistSection> = emptyList()
)
