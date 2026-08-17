package com.wb.mdgw

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {

    /** 读取 uri 指向的文本，自动识别 UTF-8 BOM / GBK */
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
        return decodeText(bytes)
    }

    fun decodeText(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // 出现替换字符说明不是合法 UTF-8，回退 GBK
        return if (utf8.contains('\uFFFD')) {
            try {
                String(bytes, charset("GBK"))
            } catch (e: Exception) {
                utf8
            }
        } else utf8
    }

    /** 读取 uri 指向的二进制文件原始字节（PDF 等不需要文本解码的场景） */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
    }

    /** 获取显示文件名 */
    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name ?: "未命名.md"
    }

    fun baseName(fileName: String): String {
        val n = fileName.substringAfterLast('/')
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    private const val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val PDF_MIME = "application/pdf"

    /**
     * 保存结果的可访问文件。
     * @param uri         可直接用于「打开 / 分享」的 Uri（MediaStore 内容 Uri 或 FileProvider Uri）
     * @param displayPath 展示给用户看的保存位置
     */
    data class SavedFile(val uri: Uri, val displayPath: String)

    /**
     * 保存结果文件，采用「双保险」：
     * 1) **主副本**写入应用缓存目录（FileProvider 可访问）——「打开 / 分享」按钮 100% 使用它，
     *    不依赖 MediaStore，任何机型都能稳定打开；
     * 2) **公共副本**（best-effort）写入系统「下载」目录，方便用户在文件管理器里找到。
     *    即使公共写入失败，主副本仍可正常打开 / 分享，文件绝不会"凭空消失"。
     *
     * @return uri 指向可稳定打开 / 分享的主副本；displayPath 为对用户有意义的保存位置说明
     */
    fun saveToDownloads(context: Context, fileName: String, data: ByteArray, mimeType: String = DOCX_MIME): SavedFile {
        val safeName = sanitize(fileName)
        // 主副本：缓存目录，最可靠
        val cacheUri = writeCache(context, safeName, data)
        // 公共副本：下载目录，best-effort
        val publicPath = tryWritePublic(context, safeName, data, mimeType)
        val display = publicPath ?: "已生成（应用缓存），点击下方按钮可直接打开 / 分享"
        return SavedFile(cacheUri, display)
    }

    /** 生成文件统一存放的目录名（位于系统「下载」下的同名子目录） */
    private const val OUTPUT_DIR = "陈律文档"

    /** 公共「下载 / 陈律文档」目录写入（best-effort）：成功返回展示路径，失败返回 null */
    private fun tryWritePublic(context: Context, safeName: String, data: ByteArray, mimeType: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + OUTPUT_DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: return null
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "$OUTPUT_DIR/$safeName"
            } else {
                val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(pub, OUTPUT_DIR)
                if (!(dir.exists() || dir.mkdirs())) return null
                val f = File(dir, safeName)
                f.writeBytes(data)
                f.absolutePath
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 写入应用缓存，用于分享 / 打开（mime 由后续的 open/share intent 决定） */
    fun writeCache(context: Context, fileName: String, data: ByteArray): Uri {
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, sanitize(fileName))
        f.writeBytes(data)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    fun shareIntent(uri: Uri, fileName: String, mimeType: String = DOCX_MIME): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享 $fileName")
    }

    fun openIntent(uri: Uri, mimeType: String = DOCX_MIME): Intent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(view, "选择应用打开")
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    /**
     * 把文本写回原文件 Uri（best-effort）。
     * 仅当该 Uri 拥有写权限（如通过 SAF 取得 persistable write 权限）时成功，
     * 否则返回 false，调用方应再保存一份副本。
     */
    fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 把文本作为文件保存到「下载 / 陈律文档」，返回可直接打开/分享的 Uri。 */
    fun saveTextAsFile(context: Context, fileName: String, text: String): SavedFile {
        val data = text.toByteArray(Charsets.UTF_8)
        val mime = if (fileName.lowercase().endsWith(".md")) "text/markdown" else "text/plain"
        return saveToDownloads(context, fileName, data, mime)
    }
}
