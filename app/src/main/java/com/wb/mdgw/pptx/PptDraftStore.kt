package com.wb.mdgw.pptx

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PPTX 编辑草稿的本地持久化（自动保存）。
 *
 * 用户在 PPTX tab 编辑的 Markdown、所选主题、自动分页开关、以及逐页布局模板选择，
 * 都会防抖落盘到本文件；下一次进入 PPTX tab 时自动恢复，避免误关或崩溃丢失劳动成果。
 *
 * 纯本地、零网络；用 kotlinx.serialization 序列化（与 GovDocDraftStore 同一套机制）。
 */
object PptDraftStore {

    private const val DRAFT_FILE = "pptx_draft.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    data class PptDraft(
        val markdown: String = "",
        val themeId: String = "navy",
        val customColor: String = "2E5FA3",   // 自定义主色调（themeId == "custom" 时生效）
        val autoPaginate: Boolean = true,
        val waveDeco: Boolean = false,          // 底部波浪装饰开关
        val barDeco: Boolean = false,           // 底部直线色块装饰开关（与波浪并列）
        val barHeightDenom: Int = 60,           // 直线色块高度分母（1/N 页高，默认 60；越小越厚）
        val bandGap: Int = 24,                  // 版式间距：色块与正文的间距（pt，全局；仅左/上/下色块生效）
        val defaultLayout: String = "standard",   // 对应 SlideLayout.STANDARD.key
        val layouts: Map<Int, String> = emptyMap(),   // 页索引 -> 布局 key（预设版式，兼容旧草稿）
        val comps: Map<Int, String> = emptyMap()      // 页索引 -> 组合 key（阶段二自由组合；缺省视为使用预设版式）
    )

    fun save(context: Context, draft: PptDraft) {
        runCatching {
            val txt = json.encodeToString(PptDraft.serializer(), draft)
            context.openFileOutput(DRAFT_FILE, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    fun load(context: Context): PptDraft? {
        val file = File(context.filesDir, DRAFT_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { json.decodeFromString<PptDraft>(file.readText()) }.getOrNull()
    }

    fun has(context: Context): Boolean {
        val f = File(context.filesDir, DRAFT_FILE)
        return f.exists() && f.length() > 0
    }

    fun clear(context: Context) {
        runCatching { context.deleteFile(DRAFT_FILE) }
    }
}
