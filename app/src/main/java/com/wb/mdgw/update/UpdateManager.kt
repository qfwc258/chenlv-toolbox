package com.wb.mdgw.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.wb.mdgw.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 应用自更新：每日自动检查（仅红点提示）、系统 DownloadManager 下载、
 * FileProvider 调起系统安装器。
 *
 * 下载交给系统 DownloadManager：通知栏自带进度、退出 App 也继续、免存储权限。
 * 后台下载完成由 [UpdateDownloadReceiver] 发「点击安装」通知；前台则由 UI 轮询后直接安装。
 */
object UpdateManager {

    private const val PREFS = "update_prefs"
    private const val KEY_LAST_AUTO = "last_auto_check_ms"
    private const val KEY_AVAIL_VERSION = "available_version"
    private const val KEY_AVAIL_NOTES = "available_notes"
    private const val KEY_AVAIL_URL = "available_url"
    private const val KEY_AVAIL_TAG = "available_tag"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val AUTO_CHECK_INTERVAL = 24L * 60 * 60 * 1000 // 每天一次
    const val CHANNEL_ID = "update_channel"
    const val APK_FILE_NAME = "chenlv-toolbox-update.apk"

    private val _hasUpdate = MutableStateFlow(false)
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()

    /** 前台 UI 是否正在轮询下载（为 true 时完成广播不再重复发通知） */
    @Volatile
    var observingDownload: Boolean = false
        private set

    fun setObservingDownload(value: Boolean) {
        observingDownload = value
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 自动检查状态（设置页红点） ----------

    fun availableVersion(ctx: Context): String? =
        prefs(ctx).getString(KEY_AVAIL_VERSION, null)?.takeIf {
            UpdateChecker.isNewer(it, BuildConfig.VERSION_NAME)
        }

    fun availableNotes(ctx: Context): String =
        prefs(ctx).getString(KEY_AVAIL_NOTES, "").orEmpty()

    /** 用持久化结果刷新红点状态（App 启动 / 检查后调用） */
    fun refreshState(ctx: Context) {
        _hasUpdate.value = availableVersion(ctx) != null
    }

    /** 每天最多自动检查一次；有新版仅落盘 + 亮红点，不弹窗打扰 */
    suspend fun maybeAutoCheck(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = prefs(ctx).getLong(KEY_LAST_AUTO, 0L)
        if (now - last < AUTO_CHECK_INTERVAL) return
        prefs(ctx).edit().putLong(KEY_LAST_AUTO, now).apply()
        val info = UpdateChecker.fetchLatest() ?: return
        if (UpdateChecker.isNewer(info.versionName)) {
            prefs(ctx).edit()
                .putString(KEY_AVAIL_VERSION, info.versionName)
                .putString(KEY_AVAIL_NOTES, info.notes)
                .putString(KEY_AVAIL_URL, info.apkUrl)
                .putString(KEY_AVAIL_TAG, info.tagName)
                .apply()
        } else {
            clearAvailable(ctx)
        }
        refreshState(ctx)
    }

    /** 手动检查：强制请求并更新红点；返回最新 Release（失败为 null） */
    suspend fun checkNow(ctx: Context): UpdateChecker.ReleaseInfo? {
        val info = UpdateChecker.fetchLatest()
        if (info != null) {
            prefs(ctx).edit().putLong(KEY_LAST_AUTO, System.currentTimeMillis()).apply()
            if (UpdateChecker.isNewer(info.versionName)) {
                prefs(ctx).edit()
                    .putString(KEY_AVAIL_VERSION, info.versionName)
                    .putString(KEY_AVAIL_NOTES, info.notes)
                    .putString(KEY_AVAIL_URL, info.apkUrl)
                    .putString(KEY_AVAIL_TAG, info.tagName)
                    .apply()
            } else {
                clearAvailable(ctx)
            }
            refreshState(ctx)
        }
        return info
    }

    fun clearAvailable(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_AVAIL_VERSION).remove(KEY_AVAIL_NOTES)
            .remove(KEY_AVAIL_URL).remove(KEY_AVAIL_TAG)
            .apply()
        refreshState(ctx)
    }

    // ---------- 下载 ----------

    fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), APK_FILE_NAME)

    /** 发起系统下载，返回 downloadId；失败返回 -1 */
    fun startDownload(ctx: Context, url: String): Long = runCatching {
        runCatching { apkFile(ctx).delete() }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("陈律工具箱 更新")
            setDescription("正在下载新版本 APK…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            // 落到应用专属外部目录，FileProvider 已覆盖，免存储权限
            setDestinationInExternalFilesDir(ctx, null, APK_FILE_NAME)
        }
        val id = dm.enqueue(request)
        prefs(ctx).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
        id
    }.getOrDefault(-1L)

    fun savedDownloadId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_DOWNLOAD_ID, -1L)

    sealed class Progress {
        data class Running(val fraction: Float = -1f) : Progress()
        data class Done(val file: File) : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /** 查询下载进度与状态 */
    fun queryProgress(ctx: Context, id: Long): Progress {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return Progress.Running()
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val f = apkFile(ctx)
                    if (f.exists() && f.length() > 0) Progress.Done(f)
                    else Progress.Failed("下载文件校验失败")
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Progress.Failed("下载失败（错误码 $reason），可改用浏览器下载")
                }
                else -> {
                    val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val fraction = if (total > 0) (soFar.toFloat() / total).coerceIn(0f, 1f) else -1f
                    Progress.Running(fraction)
                }
            }
        }
        return Progress.Running()
    }

    // ---------- 安装 ----------

    fun canInstallApks(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(ctx: Context, file: File): Intent {
        val authority = "${ctx.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(ctx, authority, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ---------- 通知 ----------

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "新版本下载完成提示" }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
