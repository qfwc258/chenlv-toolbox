package com.wb.mdgw.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 便签富文本工具：图片压缩转 base64、HTML 清洗、纯文本摘要。 */
object NoteHtml {

    /** 从 Uri 读取图片，等比压缩到最大边 1280px、JPEG 质量 80，转 base64 data URI */
    fun imageToDataUri(context: Context, uri: Uri, maxEdge: Int = 1280, quality: Int = 80): String? =
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input) ?: return null
            input.close()

            val w = original.width
            val h = original.height
            val scale = if (maxOf(w, h) <= maxEdge) 1f else maxEdge.toFloat() / maxOf(w, h)
            val bitmap = if (scale == 1f) original else
                Bitmap.createScaledBitmap(original, (w * scale).toInt(), (h * scale).toInt(), true)

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }.getOrNull()

    /** 从 HTML 提取纯文本摘要（列表卡片用），去掉标签与 data URI */
    fun plainText(html: String, maxLen: Int = 160): String {
        var s = html
        // 去图片标签
        s = Regex("<img[^>]*>", RegexOption.IGNORE_CASE).replace(s, "")
        // 块级标签换行
        s = Regex("<(br|/p|/div|/li|/h[1-6])[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n")
        // 去其余标签
        s = Regex("<[^>]+>").replace(s, "")
        // 反转义常见实体
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        s = Regex("\\n{3,}").replace(s, "\n\n").trim()
        return if (s.length > maxLen) s.take(maxLen) + "…" else s
    }

    /** 是否含图片 */
    fun hasImage(html: String): Boolean = Regex("<img[^>]*src=[\"']data:image", RegexOption.IGNORE_CASE).containsMatchIn(html)

    fun wrapForEdit(html: String): String = if (html.isBlank()) "<p><br></p>" else html
}
