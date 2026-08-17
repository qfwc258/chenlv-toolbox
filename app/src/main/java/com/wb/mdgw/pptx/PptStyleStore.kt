package com.wb.mdgw.pptx

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * PPTX 自定义 CSS 样式的本地持久化（与 PptDraftStore 同一套机制）。
 *
 * 用户在「样式」弹窗中编辑的 CSS 文本会落盘到本文件；下一次进入 PPTX tab 时自动恢复。
 * 空字符串 = 未自定义 = 使用默认样式（保底）。「恢复默认」即清空本文件（delete）。
 *
 * 纯本地、零网络。复用 [JsonFileStore]。
 */
object PptStyleStore : JsonFileStore<PptStyleStore.StyleHolder>() {

    override val fileName: String = "pptx_style.json"

    override fun serializer(): KSerializer<StyleHolder> = StyleHolder.serializer()

    @Serializable
    data class StyleHolder(val css: String = "")

    /** 保存 CSS 文本（空字符串表示恢复默认）。 */
    fun save(context: Context, css: String) = write(context, StyleHolder(css))

    /** 读取已保存的 CSS 文本；无文件或损坏时返回空（即默认样式）。 */
    fun load(context: Context): String = read(context)?.css ?: ""

    /** 是否已自定义（存在非空 CSS）。 */
    fun hasCustom(context: Context): Boolean = load(context).isNotBlank()
}
