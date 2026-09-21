package com.wb.mdgw.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 监听系统 DownloadManager 下载完成。
 *
 * 仅处理本应用发起的更新下载；App 在前台时交给 UI 直接弹安装，
 * App 在后台/已退出时发一条「点击安装」通知（点击进 [InstallActivity]）。
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    companion object {
        private const val NOTIF_ID = 20260922
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L || id != UpdateManager.savedDownloadId(context)) return

        when (val p = UpdateManager.queryProgress(context, id)) {
            is UpdateManager.Progress.Done -> {
                if (UpdateManager.observingDownload) return // 前台 UI 正在轮询，自行处理
                UpdateManager.ensureChannel(context)
                val installIntent = Intent(context, InstallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pi = android.app.PendingIntent.getActivity(
                    context, 0, installIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                val notif = NotificationCompat.Builder(context, UpdateManager.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("陈律工具箱 已下载")
                    .setContentText("点击安装新版本")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                runCatching {
                    NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
                }
            }
            is UpdateManager.Progress.Failed -> {
                if (UpdateManager.observingDownload) return
                UpdateManager.ensureChannel(context)
                val notif = NotificationCompat.Builder(context, UpdateManager.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("更新下载失败")
                    .setContentText(p.reason)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                runCatching {
                    NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
                }
            }
            else -> Unit
        }
    }
}
