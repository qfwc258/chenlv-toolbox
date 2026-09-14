package com.wb.mdgw

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志本地存储（**纯本地，绝不外传**）。
 *
 * 设计取舍：本应用主打「文件不出设备」，因此不接入任何第三方崩溃上报 SDK；
 * 崩溃时把完整堆栈写入应用私有目录，用户可在「设置」里主动导出或清除。
 *
 * - 单个崩溃一个文件，文件名带时间戳，便于按时间定位；
 * - 最多保留 [MAX_FILES] 份，超出自动淘汰最旧的，避免无限增长。
 */
object CrashLogStore {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_FILES = 5
    private const val EXPORT_NAME = "崩溃日志.txt"

    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    private val headerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 记录一次崩溃，返回写入的文件（失败返回 null，绝不让日志逻辑二次抛异常）。 */
    fun record(context: Context, thread: Thread, throwable: Throwable): File? {
        return runCatching {
            val time = System.currentTimeMillis()
            val file = File(dir(context), "crash_${nameFormat.format(Date(time))}.log")
            val device = "设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            val text = buildString {
                appendLine("时间：${headerFormat.format(Date(time))}")
                appendLine(device)
                appendLine("应用：V${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("线程：${thread.name}")
                appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
                appendLine()
                appendLine(throwable.stackTraceToString())
                val cause = throwable.cause
                if (cause != null && cause !== throwable) {
                    appendLine()
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    appendLine(cause.stackTraceToString())
                }
            }
            file.writeText(text)
            trim(context)
            file
        }.getOrNull()
    }

    /** 已记录的崩溃文件（按时间倒序，最新在前）。 */
    fun files(context: Context): List<File> =
        runCatching {
            dir(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
        }.getOrNull() ?: emptyList()

    /** 清除全部崩溃日志。 */
    fun clear(context: Context) {
        files(context).forEach { runCatching { it.delete() } }
    }

    /**
     * 把所有崩溃日志合并导出成一个可读文本（用于「设置 → 崩溃日志 → 导出」分享出去排查）。
     * 无日志时返回 null。
     */
    fun export(context: Context): File? {
        val all = files(context)
        if (all.isEmpty()) return null
        val out = File(context.cacheDir, EXPORT_NAME)
        out.bufferedWriter().use { w ->
            w.appendLine("陈律工具箱 崩溃日志（共 ${all.size} 条）")
            w.appendLine("导出时间：${headerFormat.format(Date())}")
            w.appendLine("=".repeat(40))
            all.forEach { f ->
                w.appendLine()
                w.appendLine("---- ${f.name} ----")
                w.appendLine(runCatching { f.readText() }.getOrDefault("(读取失败)"))
            }
        }
        return out
    }

    /** 只保留最近 [MAX_FILES] 份。 */
    private fun trim(context: Context) {
        val all = files(context)
        if (all.size > MAX_FILES) {
            all.drop(MAX_FILES).forEach { runCatching { it.delete() } }
        }
    }
}
