package com.wb.mdgw.elementdoc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 最高人民法院《法〔2025〕82号》要素式文书数据模型。
 *
 * 数据来自 qwertyzhu/cn-lawyer-ai-toolbox（Apache-2.0）内置官方原表结构化数据，
 * 9 大领域 · 67 类纠纷 · 113 份官方起诉状/答辩状/申请书/陈述书原表。
 *
 * 本文件只定义与 public/data/spc-2025-templates.json 对齐的序列化结构，
 * 保持纯 JVM / 无 Android 依赖，便于单元测试。
 */

/** 顶层语料：官方标准号、发布/生效日期、113 份模板 */
@Serializable
data class SpcCorpus(
    val standard: String = "",
    @SerialName("issuedDate") val issuedDate: String = "",
    @SerialName("effectiveDate") val effectiveDate: String = "",
    val source: String = "",
    val count: Int = 0,
    val templates: List<SpcTemplate> = emptyList()
)

/** 一份官方要素式文书原表 */
@Serializable
data class SpcTemplate(
    val id: String = "",
    @SerialName("domainId") val domainId: String = "",
    @SerialName("domainName") val domainName: String = "",
    @SerialName("causeName") val causeName: String = "",
    @SerialName("shortCause") val shortCause: String = "",
    @SerialName("docRole") val docRole: String = "",
    @SerialName("docTitle") val docTitle: String = "",
    @SerialName("docSubtitle") val docSubtitle: String = "",
    @SerialName("displayName") val displayName: String = "",
    @SerialName("sourceFile") val sourceFile: String = "",
    val rows: List<SpcRow> = emptyList(),
    @SerialName("signerLine") val signerLine: String = "",
    @SerialName("dateLine") val dateLine: String = "",
    val footnotes: List<String> = emptyList(),
    val attachments: List<SpcAttachment> = emptyList()
) {
    /** 文书角色中文标签 */
    val roleLabel: String get() = SpcRole.labelOf(docRole)
}

/** 原表中的一行（notice/section/guide/field/full_input/grid） */
@Serializable
data class SpcRow(
    val id: String = "",
    val kind: String = "",
    val label: String = "",
    @SerialName("templateText") val templateText: String = "",
    val cells: List<String> = emptyList(),
    @SerialName("vMergeContinue") val vMergeContinue: Boolean = false,
    @SerialName("roleTag") val roleTag: String = "",
    val repeatable: Boolean = false
)

/** 签名后的附件表（如证据清单、质证表） */
@Serializable
data class SpcAttachment(
    val label: String = "",
    val title: String = "",
    val subtitle: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    @SerialName("footerLines") val footerLines: List<String> = emptyList()
)

/** 文书角色常量 */
object SpcRole {
    const val CLAIM = "claim"
    const val DEFENSE = "defense"
    const val APPLICATION = "application"
    const val STATEMENT = "statement"

    fun labelOf(role: String): String = when (role) {
        CLAIM -> "起诉状 / 自诉状"
        DEFENSE -> "答辩状"
        APPLICATION -> "申请书"
        STATEMENT -> "意见陈述书"
        else -> "其他"
    }
}

/** 领域常量（id 与官方数据 domainId 对齐） */
object SpcDomain {
    const val CIVIL_COMMERCIAL = "civil_commercial"
    const val IP_CIVIL = "ip_civil"
    const val IP_ADMIN = "ip_admin"
    const val ADMIN = "admin"
    const val EXECUTION = "execution"
    const val MARITIME = "maritime"
    const val ENV_RESOURCE = "env_resource"
    const val STATE_COMP = "state_comp"
    const val CRIMINAL_PRIVATE = "criminal_private"

    /** 领域显示顺序（官方来源顺序） */
    val ORDER = listOf(
        CIVIL_COMMERCIAL, IP_CIVIL, IP_ADMIN, ADMIN, EXECUTION,
        MARITIME, ENV_RESOURCE, STATE_COMP, CRIMINAL_PRIVATE
    )
}

// ---------------- 已填文档模型（对齐参照仓库 ResolvedSpcDocument） ----------------

/** 已解析的一行：值/勾选/表格由 kind 决定 */
data class ResolvedRow(
    val id: String,
    val kind: String,
    val label: String = "",
    val value: String = "",
    val cells: List<String> = emptyList(),
    val vMergeContinue: Boolean = false,
    val roleTag: String = ""
)

data class ResolvedAttachment(
    val label: String = "",
    val title: String = "",
    val subtitle: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val footerLines: List<String> = emptyList()
)

/** 可交给 docx 渲染器的完整已填文档 */
data class ResolvedSpcDocument(
    val templateId: String = "",
    val docTitle: String = "",
    val docSubtitle: String = "",
    val displayName: String = "",
    val rows: List<ResolvedRow> = emptyList(),
    val signerLine: String = "",
    val signerName: String = "",
    val dateLine: String = "",
    val signDate: String = "",
    val footnotes: List<String> = emptyList(),
    val attachments: List<ResolvedAttachment> = emptyList()
)
