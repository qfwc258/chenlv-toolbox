package com.wb.mdgw.catalog

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 证据 / 法援目录的「上次输入」草稿（JSON 持久化）。
 *
 * 与只存跨案件偏好的 [CatalogPrefs] 不同，这里保存**完整表单内容**（含证据条目、
 * 法援页码与项目名改动），下次进入页面自动恢复。核心生成器（CatalogModels/Builder）
 * 刻意保持零序列化依赖以便纯 JVM 单测，故草稿 DTO 在本文件单独定义、在 UI 层与
 * 表单模型互转。
 */

@Serializable
data class EvidenceDraftItem(
    val name: String = "",
    val pages: String = "",
    val purpose: String = "",
    val source: String = ""
)

@Serializable
data class EvidenceDraft(
    val title: String = "证 据 目 录",
    val submitter: String = "",
    val date: String = "",
    val minRows: String = "10",
    val items: List<EvidenceDraftItem> = emptyList()
)

@Serializable
data class ArchiveDraftItem(
    val name: String = "",
    val page: String = ""
)

@Serializable
data class ArchiveDraft(
    val title: String = "宁乡市法律援助案卷归档目录",
    val items: List<ArchiveDraftItem> = emptyList()
)

object EvidenceDraftStore : JsonFileStore<EvidenceDraft>() {
    override val fileName: String = "catalog_evidence_draft.json"
    override fun serializer() = EvidenceDraft.serializer()
    fun load(context: Context): EvidenceDraft? = read(context)
    fun save(context: Context, data: EvidenceDraft) = write(context, data)
}

object ArchiveDraftStore : JsonFileStore<ArchiveDraft>() {
    override val fileName: String = "catalog_archive_draft.json"
    override fun serializer() = ArchiveDraft.serializer()
    fun load(context: Context): ArchiveDraft? = read(context)
    fun save(context: Context, data: ArchiveDraft) = write(context, data)
}
