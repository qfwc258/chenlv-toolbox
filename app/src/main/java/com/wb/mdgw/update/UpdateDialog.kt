package com.wb.mdgw.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 检查更新的状态机：检查 → 发现新版本对话框 → 下载进度对话框 → 调起安装。
 *
 * 通过 [rememberUpdateController] 在设置页创建；下载在前台时由本控制器轮询，
 * 用户切到后台后由系统下载器继续，完成由 [UpdateDownloadReceiver] 通知接管。
 */
class UpdateController internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var checking by mutableStateOf(false)
        private set
    var info by mutableStateOf<UpdateChecker.ReleaseInfo?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var fraction by mutableStateOf(-1f)
        private set

    private var pollJob: Job? = null
    private var downloadId: Long = -1L
    private var currentFile: File? = null
    internal var requestInstallPermission: (() -> Unit)? = null

    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            val rel = UpdateManager.checkNow(context)
            checking = false
            when {
                rel == null -> toast("检查更新失败，请检查网络后重试")
                UpdateChecker.isNewer(rel.versionName) -> info = rel
                else -> toast("当前已是最新版本")
            }
        }
    }

    fun dismissInfo() {
        info = null
    }

    fun browserDownload(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法打开浏览器，请稍后重试") }
        info = null
    }

    fun startDownload() {
        val rel = info ?: return
        val id = UpdateManager.startDownload(context, rel.apkUrl)
        if (id < 0) {
            toast("无法开始下载，请改用浏览器下载")
            return
        }
        downloadId = id
        info = null
        downloading = true
        fraction = -1f
        UpdateManager.setObservingDownload(true)
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                when (val p = UpdateManager.queryProgress(context, id)) {
                    is UpdateManager.Progress.Running -> {
                        fraction = p.fraction
                        delay(1000)
                    }
                    is UpdateManager.Progress.Done -> {
                        UpdateManager.setObservingDownload(false)
                        downloading = false
                        currentFile = p.file
                        UpdateManager.clearAvailable(context)
                        triggerInstall()
                        return@launch
                    }
                    is UpdateManager.Progress.Failed -> {
                        UpdateManager.setObservingDownload(false)
                        downloading = false
                        toast(p.reason)
                        return@launch
                    }
                }
            }
        }
    }

    /** 仅关闭进度对话框，下载继续在系统下载器中进行（完成由通知接管） */
    fun runInBackground() {
        pollJob?.cancel()
        UpdateManager.setObservingDownload(false)
        downloading = false
    }

    /** 取消并移除下载 */
    fun cancelDownload() {
        pollJob?.cancel()
        UpdateManager.setObservingDownload(false)
        if (downloadId > 0) {
            runCatching {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(downloadId)
            }
        }
        downloading = false
        fraction = -1f
    }

    private fun triggerInstall() {
        val f = currentFile ?: return
        if (UpdateManager.canInstallApks(context)) {
            runCatching { context.startActivity(UpdateManager.installIntent(context, f)) }
                .onFailure { toast("无法调起安装器") }
        } else {
            // 首次安装：引导授予「安装未知应用」权限，返回后由 afterPermissionReturn 续装
            requestInstallPermission?.invoke()
        }
    }

    internal fun afterPermissionReturn() {
        val f = currentFile ?: return
        if (UpdateManager.canInstallApks(context)) {
            runCatching { context.startActivity(UpdateManager.installIntent(context, f)) }
                .onFailure { toast("无法调起安装器") }
        } else {
            toast("未授予安装权限，无法安装")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberUpdateController(): UpdateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { UpdateController(context, scope) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        controller.afterPermissionReturn()
    }
    DisposableEffect(Unit) {
        controller.requestInstallPermission = {
            permLauncher.launch(UpdateManager.unknownSourcesSettingIntent(context))
        }
        onDispose {
            controller.requestInstallPermission = null
            UpdateManager.setObservingDownload(false)
        }
    }

    controller.info?.let { rel ->
        UpdateAvailableDialog(
            info = rel,
            onDismiss = controller::dismissInfo,
            onDownload = controller::startDownload,
            onBrowser = { controller.browserDownload(rel.apkUrl) },
        )
    }

    if (controller.downloading) {
        DownloadDialog(
            fraction = controller.fraction,
            onBackground = controller::runInBackground,
            onCancel = controller::cancelDownload,
        )
    }

    return controller
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateChecker.ReleaseInfo,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onBrowser: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("发现新版本 v${info.versionName}", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME}" +
                        if (info.apkSize > 0) "　·　安装包 ${UpdateChecker.humanSize(info.apkSize)}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    UpdateChecker.cleanNotes(info.notes),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("立即下载") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBrowser) { Text("浏览器下载", fontSize = 13.sp) }
                TextButton(onClick = onDismiss) { Text("稍后", fontSize = 13.sp) }
            }
        }
    )
}

@Composable
private fun DownloadDialog(
    fraction: Float,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBackground,
        title = { Text("正在下载新版本", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (fraction >= 0f) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(fraction * 100).toInt()}%", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("正在下载，通知栏可查看进度…", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBackground) { Text("后台下载") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消", fontSize = 13.sp) }
        }
    )
}
