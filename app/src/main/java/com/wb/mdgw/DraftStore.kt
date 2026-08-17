package com.wb.mdgw

import android.content.Context
import java.io.File

/**
 * Markdown 编辑草稿的本地持久化。
 *
 * 用户在「文档」页编辑过程中会自动（防抖）把当前正文存到应用私有目录，
 * 下一次进入页面（无外部文件传入时）若检测到草稿则提示恢复，
 * 避免误关或崩溃导致的内容丢失。手动「保存」成功后即清除草稿。
 *
 * 设计要点：
 *  - 用私有文件而非 SharedPreferences，避开 SP 对大字符串的尺寸限制（草稿可能很长）；
 *  - 只存「文件名 + 正文」两项，结构极简、读写零依赖，不引入任何序列化库；
 *  - 不持久化 originalUri：跨进程/跨会话后原文件权限可能失效，恢复时按「未关联原文件」
 *    处理，用户保存时会走「另存为」流程，更安全。
 */
object DraftStore {

    private const val DRAFT_FILE = "markdown_draft.txt"
    private const val NAME_FILE = "markdown_draft_name.txt"

    data class MdDraft(val name: String, val text: String)

    /** 保存草稿（覆盖式）。失败静默忽略，绝不影响正常编辑。 */
    fun save(context: Context, name: String, text: String) {
        runCatching {
            context.openFileOutput(DRAFT_FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            context.openFileOutput(NAME_FILE, Context.MODE_PRIVATE).use { it.write(name.toByteArray()) }
        }
    }

    /** 读取草稿；不存在或读取失败返回 null */
    fun load(context: Context): MdDraft? {
        val file = File(context.filesDir, DRAFT_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val text = file.readText()
            val name = File(context.filesDir, NAME_FILE).let { if (it.exists()) it.readText() else "未命名.md" }
            MdDraft(name, text)
        }.getOrNull()
    }

    /** 是否存在草稿文件（用于决定是否弹恢复提示） */
    fun has(context: Context): Boolean {
        val f = File(context.filesDir, DRAFT_FILE)
        return f.exists() && f.length() > 0
    }

    /** 清除草稿（手动保存成功 / 用户放弃恢复时调用） */
    fun clear(context: Context) {
        runCatching { context.deleteFile(DRAFT_FILE) }
        runCatching { context.deleteFile(NAME_FILE) }
    }
}

/**
 * 记录「本次进程是否已对草稿做过恢复/丢弃决策」。
 *
 * 页面在底部 Tab 间切换时会被从组合树移除、再重新组合，
 * 若每次重组都检查草稿就会反复弹「恢复草稿」对话框。
 * 用进程级标志确保一次启动只提示一次；应用被杀重启后标志重置，仍会正常提示。
 */
object DraftSession {
    var handled: Boolean = false
}
