package com.wb.mdgw.pptx

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PPTX 自定义 CSS 样式的本地持久化（与 PptDraftStore 同机制）。
 *
 * 用户在「样式」弹窗中编辑的 CSS 文本会落盘到本文件；下一次进入 PPTX tab 时自动恢复。
 * 空字符串 = 未自定义 = 使用默认样式（保底）。「恢复默认」即清空本文件（delete）。
 *
 * 纯本地、零网络。
 */
object PptStyleStore {

    private const val STYLE_FILE = "pptx_style.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    private data class StyleHolder(val css: String = "")

    /** 保存 CSS 文本（空字符串表示恢复默认）。 */
    fun save(context: Context, css: String) {
        runCatching {
            val txt = json.encodeToString(StyleHolder.serializer(), StyleHolder(css))
            context.openFileOutput(STYLE_FILE, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    /** 读取已保存的 CSS 文本；无文件或损坏时返回空（即默认样式）。 */
    fun load(context: Context): String {
        val file = File(context.filesDir, STYLE_FILE)
        if (!file.exists() || file.length() == 0L) return ""
        return runCatching { json.decodeFromString<StyleHolder>(file.readText()).css }.getOrDefault("")
    }

    /** 是否已自定义（存在非空 CSS）。 */
    fun hasCustom(context: Context): Boolean = load(context).isNotBlank()

    /** 恢复默认：删除样式文件。 */
    fun clear(context: Context) {
        runCatching { context.deleteFile(STYLE_FILE) }
    }
}
