package com.wb.mdgw.wechat

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * 公众号排版草稿的本地持久化（自动保存）。
 *
 * 用户在公众号 Tab 编辑的 Markdown、所选主题与自定义 CSS 会防抖落盘到本文件，
 * 下一次进入时静默自动恢复（与 PPTX 草稿同模式），避免误关或崩溃丢失劳动成果。
 * 纯本地、零网络；复用 [JsonFileStore] 的 JSON 序列化机制（与 PptDraftStore 同一套）。
 */
object WeChatDraftStore : JsonFileStore<WeChatDraftStore.WeChatDraft>() {

    override val fileName: String = "wechat_draft.json"

    override fun serializer(): KSerializer<WeChatDraft> = WeChatDraft.serializer()

    @Serializable
    data class WeChatDraft(
        val markdown: String = "",
        val themeKey: String = "",
        val customCss: String = ""
    )

    fun save(context: Context, draft: WeChatDraft) = write(context, draft)

    fun load(context: Context): WeChatDraft? = read(context)
}
