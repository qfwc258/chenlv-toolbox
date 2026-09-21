package com.wb.mdgw.ftp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * FTP 服务页：开关、连接地址、可配置 IP/端口/根目录（运行中锁定）、使用说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val initial = remember { FtpSettings.load(context) }
    var ip by remember { mutableStateOf(initial.ip) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var rootDir by remember { mutableStateOf(initial.rootDir) }

    val running by FtpServerState.running.collectAsState()
    val address by FtpServerState.address.collectAsState()
    val stateError by FtpServerState.error.collectAsState()
    val detectedIps = remember { FtpSettings.enumerateIpv4() }

    // 启动失败的错误提示
    LaunchedEffect(stateError) {
        if (stateError != null) {
            snackbar.showSnackbar(stateError!!)
            FtpServerState.error.value = null
        }
    }

    fun persist(): FtpSettings.Config {
        val port = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: 5656
        portText = port.toString()
        val root = rootDir.ifBlank { FtpSettings.DEFAULT_ROOT }
        val cfg = FtpSettings.Config(ip.trim(), port, root)
        FtpSettings.save(context, cfg)
        return cfg
    }

    // Android 11+ 所有文件访问
    val manageStorage = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 从系统设置返回后，已授权则自动启动
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            val cfg = FtpSettings.load(context)
            runCatching { File(cfg.rootDir).mkdirs() }
            FtpServerService.start(context)
        }
    }
    // Android 13+ 通知权限（不阻塞启动）
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun hasStorage(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun requestStorage() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:" + context.packageName)
        }
        runCatching { manageStorage.launch(intent) }.onFailure {
            manageStorage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun start() {
        val cfg = persist()
        // 存储权限是硬性前提，未授权先引导，授权后由 launcher 回调自动启动
        if (!hasStorage()) {
            requestStorage()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        runCatching { File(cfg.rootDir).mkdirs() }
        FtpServerService.start(context)
    }

    fun stop() = FtpServerService.stop(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FTP 服务", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- 状态 + 开关 ----------
            Card(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (running) "运行中" else "已停止",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    if (running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                address,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ftp", address))
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { stop() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("停止服务")
                        }
                    } else {
                        Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("启动服务")
                        }
                    }
                }
            }

            // ---------- 连接设置 ----------
            Card(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("连接设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (running) {
                        Text(
                            "服务运行中，设置已锁定；停止后可修改。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    // 本机 IP
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("本机 IP（留空自动检测）") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (detectedIps.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            detectedIps.take(3).forEach { candidate ->
                                OutlinedButton(
                                    onClick = { ip = candidate },
                                    enabled = !running,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                ) {
                                    Text(candidate, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }

                    // 端口
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口（1024–65535）") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 根目录
                    OutlinedTextField(
                        value = rootDir,
                        onValueChange = { rootDir = it },
                        label = { Text("根目录（绝对路径）") },
                        singleLine = true,
                        enabled = !running,
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickDir("内部存储", Environment.getExternalStorageDirectory().absolutePath, running) { rootDir = it }
                        QuickDir("/scan", FtpSettings.DEFAULT_ROOT, running) { rootDir = it }
                        QuickDir("Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath, running) { rootDir = it }
                    }
                }
            }

            // ---------- 说明 ----------
            Card(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("使用说明", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    InfoLine("手机与电脑/其他设备需连接同一 WiFi（或手机热点）。")
                    InfoLine("匿名登录，用户名 anonymous，密码任意；对根目录拥有读写删全部权限。")
                    InfoLine("电脑：文件资源管理器地址栏输入上面的 ftp:// 地址；或用 FileZilla 选被动模式。")
                    InfoLine("手机：ES 文件浏览器、Solid Explorer 等用 FTP 客户端连接。")
                    InfoLine("仅限可信局域网使用，请勿在公共网络下开启。")
                }
            }
        }
    }
}

@Composable
private fun QuickDir(label: String, path: String, running: Boolean, onClick: (String) -> Unit) {
    OutlinedButton(
        onClick = { onClick(path) },
        enabled = !running,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun InfoLine(text: String) {
    Text("· $text", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
