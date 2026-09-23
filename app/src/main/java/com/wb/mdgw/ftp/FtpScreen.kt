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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
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
 * FTP 服务页：开关、连接地址、只读本机 IP（可跳转系统设置静态 IP）、可改端口/根目录、使用说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val initial = remember { FtpSettings.load(context) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var rootDir by remember { mutableStateOf(initial.rootDir) }
    var showIpDialog by remember { mutableStateOf(false) }

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
        val cfg = FtpSettings.Config(port, root)
        FtpSettings.save(context, cfg)
        return cfg
    }

    // 跳转到系统 WiFi 的 IP 设置页（部分 ROM 不支持则回退到 WiFi 列表）
    fun openIpSettings() {
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_WIFI_IP_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
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
        containerColor = com.wb.mdgw.BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FTP 服务",
                        style = com.wb.mdgw.BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
                    }
                },
                colors = com.wb.mdgw.BrandTokens.BrandTopAppBarColors,
                bottomBar = {
                    // P2：1dp 金色细线作为顶栏与 body 的视觉分隔
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(com.wb.mdgw.BrandTokens.BrandBronze.copy(alpha = 0.35f))
                    )
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
                .padding(14.dp),                     // P1：与 SimpleScreenFrame 默认 14dp 对齐
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 状态 + 开关：紧凑横条（不再垂直居中占用整张卡片） ----------
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = com.wb.mdgw.BrandTokens.BrandParchment
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态点：8x8dp 朱砂红 / 灰
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (running) com.wb.mdgw.BrandTokens.BrandRed
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "运行中" else "已停止",
                            fontFamily = FontFamily.Serif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (running) MaterialTheme.colorScheme.onBackground
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (running) {
                            Text(
                                address,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                "本机 IP：${
                                    if (detectedIps.isEmpty()) "未检测到网络"
                                    else detectedIps.joinToString("、")
                                }",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    // 右侧 Switch：紧凑主操作
                    Switch(
                        checked = running,
                        onCheckedChange = { if (running) stop() else start() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = com.wb.mdgw.BrandTokens.BrandPaper,
                            checkedTrackColor = com.wb.mdgw.BrandTokens.BrandRed
                        )
                    )
                }
            }

            // ---------- 连接设置：拆成 SettingsCard ----------
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = com.wb.mdgw.BrandTokens.BrandParchment
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        "连接设置",
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    // P4：三个 SettingsRow，每行 8dp 间距
                    SettingsRow(label = "本机 IP", value = detectedIps.joinToString("、").ifEmpty { "未检测到网络" }, actionLabel = "设置") {
                        showIpDialog = true
                    }
                    SettingsDivider()
                    SettingsRow(label = "端口", value = portText)
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口（1024–65535）", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                    )
                    SettingsDivider()
                    SettingsRow(label = "根目录", value = rootDir.substringAfterLast('/'))
                    OutlinedTextField(
                        value = rootDir,
                        onValueChange = { rootDir = it },
                        label = { Text("根目录（绝对路径）", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !running,
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                    )
                    // 快捷目录 chip
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickDir("内部存储", Environment.getExternalStorageDirectory().absolutePath, running) { rootDir = it }
                        QuickDir("/scan", FtpSettings.DEFAULT_ROOT, running) { rootDir = it }
                        QuickDir("Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath, running) { rootDir = it }
                    }
                    if (running) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "服务运行中，端口与根目录已锁定；停止后可修改。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            // ---------- 说明 ----------
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = com.wb.mdgw.BrandTokens.BrandParchment
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "使用说明",
                        fontFamily = FontFamily.Serif,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    InfoLine("手机与电脑/其他设备需连接同一 WiFi（或手机热点）。")
                    InfoLine("匿名登录，用户名 anonymous，密码任意；对根目录拥有读写删全部权限。")
                    InfoLine("电脑：文件资源管理器地址栏输入上面的 ftp:// 地址；或用 FileZilla 选被动模式。")
                    InfoLine("手机：ES 文件浏览器、Solid Explorer 等用 FTP 客户端连接。")
                    InfoLine("如需固定地址，优先在路由器按手机 MAC 绑定；仅限可信局域网使用。")
                }
            }
        }
    }

    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("设置静态 IP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("普通应用无权直接修改网络，需在系统设置中完成：", fontSize = 13.sp)
                    Text("1. 点「去设置」进入当前 WiFi 的 IP 设置；", fontSize = 13.sp)
                    Text("2. 将 IP 设置由「DHCP / 动态」改为「静态」；", fontSize = 13.sp)
                    Text("3. 填写 IP 地址、网关、子网掩码（前缀长度）、DNS 后保存。", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "注意：填错网关或 DNS 会导致无法上网。更省心的方式是在路由器后台按手机 MAC 绑定固定 IP，手机端保持自动获取即可。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showIpDialog = false
                    openIpSettings()
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false }) { Text("取消") }
            }
        )
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

/**
 * P4：设置行 ——
 * 系统设置风格：左标签 + 右值，右侧带 chevron 表示可点击。
 * 整个 Row 用 Row.clickable 调用 onAction，避免按钮抢占视觉重量。
 */
@Composable
private fun SettingsRow(
    label: String,
    value: String,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            if (!actionLabel.isNullOrEmpty()) {
                Text(
                    actionLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 设置行之间的细分隔线 */
@Composable
private fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    )
}
