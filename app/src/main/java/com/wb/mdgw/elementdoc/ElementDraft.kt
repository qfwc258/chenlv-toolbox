package com.wb.mdgw.elementdoc

import kotlinx.serialization.Serializable

/**
 * 要素式文书填表草稿状态（对齐参照仓库 OfficialSpcDraftState 核心字段）。
 *
 * 纯 JVM 数据类，可序列化到 JSON：rowValues 保存每行当前文本（含勾选符），
 * gridValues 保存表格行，attachmentRows 保存附件表格，extraRows 为自定义追加行。
 */
@Serializable
data class ElementDraft(
    val templateId: String = "",
    /** templateId 下用户命名的案件（用于多份草稿区分），如当事人名/案号 */
    val caseName: String = "",
    /** 原表每一行 id -> 当前文本（未改动则缺省，渲染时回退模板原文） */
    val rowValues: Map<String, String> = emptyMap(),
    /** grid 行 id -> 单元格值 */
    val gridValues: Map<String, List<String>> = emptyMap(),
    /** 附件 index -> 二维表格 */
    val attachmentRows: Map<Int, List<List<String>>> = emptyMap(),
    /** 锚定在原行 id 之后追加的自定义要素行 */
    val extraRows: List<ExtraRow> = emptyList(),
    /** 用户隐藏的原行 id（逐行隐藏/恢复） */
    val excludedRowIds: Set<String> = emptySet(),
    /** 自动隐藏完全未填的当事人重复行 */
    val hideUntouchedPartyRows: Boolean = false,
    /** 是否附带签名后附件表 */
    val includeAttachments: Boolean = true,
    val signerName: String = "",
    val signDate: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    @Serializable
    data class ExtraRow(
        val uid: String = "",
        val sourceRowId: String = "",
        val label: String = "",
        val value: String = "",
        val roleTag: String = ""
    )
}
