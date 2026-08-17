package com.wb.mdgw.wechat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.regex.Pattern

/**
 * 富文本复制工具。
 *
 * 关键点：使用 ClipData.newHtmlText(label, plainText, html) 复制，
 * 同时携带「纯文本」与「HTML」两份数据。HTML 来自 MdWechatConverter.convertForCopy()，
 * 已是 100% 内联、零 <head>/<style>/class 的纯净片段，
 * 公众号后台粘贴时会读取 HTML 部分，从而 100% 还原排版、不丢样式。
 */
object CopyUtils {

    /**
     * @param context  上下文
     * @param html     已内联样式的公众号 HTML 片段
     * @param plain    纯文本兜底（为空时自动由 html 生成）
     */
    fun copyRichText(context: Context, html: String, plain: String? = null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = if (plain.isNullOrBlank()) htmlToPlain(html) else plain
        val clip = ClipData.newHtmlText("公众号排版", text, html)
        cm.setPrimaryClip(clip)
    }

    /** 从内联 HTML 生成可读纯文本（用于复制兜底 / 调试） */
    private fun htmlToPlain(html: String): String {
        return Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE)
            .matcher(html).replaceAll("\n")
            .replace("</p>", "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .trim()
    }
}
