package com.wb.mdgw.catalog

/**
 * 目录文书（证据目录 / 案卷归档目录）的数据模型。
 *
 * 这两类文书都是「结构固定、数据重复」的表格：
 *  - 证据目录：列固定、证据条目动态增减（[EvidenceItem]）；
 *  - 归档目录：21 个固定项目（名称可改），只填页码（[ArchiveItem]）。
 *
 * 本文件刻意保持零 Android / 零序列化依赖，便于纯 JVM 单测覆盖 docx 生成。
 */

/** 单元格水平对齐（映射到 OOXML 的 w:jc） */
enum class CatalogAlign(val xml: String) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right")
}

/**
 * 表格列定义。
 *
 * @param header    表头文字（可含空格，与原模板一致）
 * @param widthDxa  列宽（缇 dxa，1 cm ≈ 567 缇），直接复刻原模板固定列宽
 * @param dataAlign 数据单元格对齐方式（表头始终居中）
 */
data class CatalogColumn(
    val header: String,
    val widthDxa: Int,
    val dataAlign: CatalogAlign = CatalogAlign.CENTER
)

/** 证据目录：一条证据（编号由生成器按顺序自动填写） */
data class EvidenceItem(
    val name: String = "",      // 证据名称
    val pages: String = "",     // 页数，如 "1" / "2-5"
    val purpose: String = "",   // 证明目的（可多行）
    val source: String = ""     // 证据来源
)

/** 证据目录表单数据 */
data class EvidenceForm(
    val title: String = "证 据 目 录",
    val submitter: String = "", // 提交人（落款）
    val date: String = "",      // 落款日期，如 "2026 年 9 月 15 日"
    val minRows: Int = 10,      // 数据区至少保留的行数（不足补空行，编号仍连续）
    val items: List<EvidenceItem> = emptyList()
)

/** 归档目录：一个固定项目（名称可改）+ 其页码 */
data class ArchiveItem(
    val name: String,
    val page: String = ""
)

/** 归档目录表单数据 */
data class ArchiveForm(
    val title: String = "宁乡市法律援助案卷归档目录",
    val items: List<ArchiveItem> = emptyList()
)
