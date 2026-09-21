package com.wb.mdgw.docgen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 从设备固定目录扫描到的一个模板文件（docx / pdf）。
 *
 * 与旧的「逐个 SAF 导入到私有目录」不同，本类直接引用 [file]，
 * 对齐 PC 端脚本直接读取 `mb/` 目录的用法。
 */
data class DocTemplateFile(
    val file: File,
    val fileName: String,
    val ext: String
) {
    val isDocx: Boolean get() = ext == "docx"
    val isPdf: Boolean get() = ext == "pdf"

    fun readBytes(): ByteArray? = runCatching { file.readBytes() }.getOrNull()
}

/**
 * 文书模板目录读取（默认 `/sdcard/pylaw/mb`，可在 App 内修改）。
 *
 * 权限：
 *  - Android 11+（API 30）：需要「所有文件访问」[Environment.isExternalStorageManager]；
 *  - Android 10：依赖 `requestLegacyExternalStorage=true` + 读存储权限；
 *  - Android 9 及以下：运行时读存储权限。
 */
object DocTemplateDir {

    /** 是否已具备读取固定目录的权限 */
    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 扫描 [dirPath] 下的 docx / pdf，按文件名升序返回。
     * 目录不存在 / 不是目录 / 不可读时返回空列表（不抛异常）。
     */
    fun scan(dirPath: String): List<DocTemplateFile> {
        val dir = File(dirPath.trim())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .map { it to it.extension.lowercase() }
            .filter { it.second == "docx" || it.second == "pdf" }
            .sortedBy { it.first.name }
            .map { DocTemplateFile(it.first, it.first.name, it.second) }
            .toList()
    }
}
