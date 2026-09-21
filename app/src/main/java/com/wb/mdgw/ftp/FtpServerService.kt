package com.wb.mdgw.ftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import com.wb.mdgw.R

/** 服务运行状态（供 UI 观察）。 */
object FtpServerState {
    val running = MutableStateFlow(false)
    val address = MutableStateFlow("")
    val rootDir = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
}

/**
 * FTP 前台服务：常驻通知保活，持有 [FtpServerEngine]。
 * 启动前由 UI 完成权限检查与配置保存；服务直接读取 [FtpSettings]。
 */
class FtpServerService : Service() {

    private var engine: FtpServerEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startEngine()
        }
        return START_STICKY
    }

    private fun startEngine() {
        if (engine != null) return
        // 先满足前台服务时限，立即占位通知，再后台绑定端口
        startForegroundCompat(getString(R.string.app_name), "正在启动 FTP 服务…")
        val config = FtpSettings.load(this)
        Thread {
            try {
                val eng = FtpServerEngine(config)
                eng.start()
                engine = eng
                val ip = FtpSettings.effectiveIp(this)
                val addr = "ftp://$ip:${config.port}"
                FtpServerState.rootDir.value = config.rootDir
                FtpServerState.address.value = addr
                FtpServerState.error.value = null
                FtpServerState.running.value = true
                startForegroundCompat("FTP 服务运行中", "$addr  根目录：${config.rootDir}")
            } catch (e: Exception) {
                FtpServerState.error.value = "启动失败：${e.message ?: "端口可能被占用"}"
                FtpServerState.running.value = false
                stopForegroundCompat()
                stopSelf()
            }
        }.start()
    }

    private fun stopEngine() {
        runCatching { engine?.stop() }
        engine = null
        FtpServerState.running.value = false
        FtpServerState.address.value = ""
        stopForegroundCompat()
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    // ---------- 前台通知 ----------
    private fun startForegroundCompat(title: String, text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FTP 服务", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, FtpServerService::class.java).setAction(ACTION_STOP)
        val stopPi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notification: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.mipmap.ic_launcher, "停止", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "ftp_service"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.wb.mdgw.ftp.STOP"

        fun start(context: Context) {
            val intent = Intent(context, FtpServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FtpServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
