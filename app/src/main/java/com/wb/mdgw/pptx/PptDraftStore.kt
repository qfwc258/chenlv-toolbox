package com.wb.mdgw.pptx

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * PPTX 编辑草稿的本地持久化（自动保存）。
 *
 * 用户在 PPTX tab 编辑的 Markdown、所选主题、自动分页开关、以及逐页布局模板选择，
 * 都会防抖落盘到本文件；下一次进入 PPTX tab 时自动恢复，避免误关或崩溃丢失劳动成果。
 *
 * 纯本地、零网络；复用 [JsonFileStore] 的 JSON 序列化机制（与 GovDocDraftStore 同一套）。
 */
object PptDraftStore : JsonFileStore<PptDraftStore.PptDraft>() {

    override val fileName: String = "pptx_draft.json"

    override fun serializer(): KSerializer<PptDraft> = PptDraft.serializer()

    @Serializable
    data class PptDraft(
        val markdown: String = "",
        val themeId: String = "navy",
        val customColor: String = "2E5FA3",   // 自定义主色调（themeId == "custom" 时生效）
        val autoPaginate: Boolean = true,
        val waveDeco: Boolean = false,          // 底部波浪装饰开关（已废弃，改用逐页组合 decoration 字段）
        val barDeco: Boolean = false,           // 底部直线色块装饰开关（已废弃，改用逐页组合 decoration 字段）
        val barHeightDenom: Int = 60,           // 直线色块高度分母（1/N 页高，默认 60；越小越厚）
        val bandGap: Int = 24,                  // 版式间距：色块与正文的间距（pt，全局；仅左/上/下色块生效）
        val logoScale: Float = 0.20f,           // Logo 宽度占画布宽比例（0.10~0.30，默认 0.20）
        val logoHAlign: String = "right",       // Logo 水平位置：left / right
        val logoVAlign: String = "bottom",      // Logo 垂直位置：top / bottom
        val defaultLayout: String = "standard",   // 对应 SlideLayout.STANDARD.key
        val layouts: Map<Int, String> = emptyMap(),   // 页索引 -> 布局 key（预设版式，兼容旧草稿）
        val comps: Map<Int, String> = emptyMap(),     // 页索引 -> 组合 key（阶段二自由组合；缺省视为使用预设版式）
        val applyToAll: Boolean = false              // 「全部应用：是/否」开关：进入预览时恢复上次选择
    )

    fun save(context: Context, draft: PptDraft) = write(context, draft)

    fun load(context: Context): PptDraft? = read(context)
}
