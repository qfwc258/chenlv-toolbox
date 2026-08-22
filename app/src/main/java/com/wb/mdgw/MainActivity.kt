package com.wb.mdgw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wb.mdgw.BuildConfig
import com.wb.mdgw.wechat.WeChatScreen
import com.wb.mdgw.pptx.MdPptxScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 支持从「打开方式 / 分享」进入；根据文件类型决定进入哪个模式
        val incoming: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            else -> null
        }

        setContent {
            AppScreen(initialUri = incoming)
        }
    }
}

@Composable
fun MdGwTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkColorScheme(
        primary = Color(0xFFE57373),
        onPrimary = Color(0xFF3E100C),
        primaryContainer = Color(0xFF5C1A13),
        onPrimaryContainer = Color(0xFFF6E3E0),
        secondary = Color(0xFFD4B87D),
        secondaryContainer = Color(0xFF4A3A20),
        surface = Color(0xFF1C1B1A),
        background = Color(0xFF131211),
        surfaceVariant = Color(0xFF2C2A27)
    ) else lightColorScheme(
        primary = Color(0xFFB03A2E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF6E3E0),
        onPrimaryContainer = Color(0xFF5C1A13),
        secondary = Color(0xFF8C6D3F),
        secondaryContainer = Color(0xFFFBF3E7),
        surface = Color(0xFFFDFBF8),
        background = Color(0xFFF6F3EE),
        surfaceVariant = Color(0xFFEDE7DE)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class DocMode { WORD, PDF, WECHAT, PPTX }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
        val detected = remember(initialUri) {
            if (initialUri != null) {
                val name = FileUtils.displayName(context, initialUri).lowercase()
                when {
                    name.endsWith(".pdf") || initialUri.toString().contains("pdf", true) -> DocMode.PDF
                    name.endsWith(".docx") || name.endsWith(".doc") -> DocMode.WORD
                    else -> DocMode.WORD
                }
            } else DocMode.WORD
        }
        var mode by remember { mutableStateOf(detected) }
        val snackbar = remember { SnackbarHostState() }
        var showAbout by remember { mutableStateOf(false) }
        var darkMode by remember { mutableStateOf(SettingsStore.isDarkMode(context)) }
        // 顶部应用功能条折叠状态：默认收起，为编辑/预览区腾出高度
        var appBarExpanded by remember { mutableStateOf(false) }

        MdGwTheme(darkTheme = darkMode) {
        Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                NavigationBarItem(
                    selected = mode == DocMode.WORD,
                    onClick = { mode = DocMode.WORD },
                    icon = { Icon(Icons.Default.Article, contentDescription = null) },
                    label = { Text("WORD", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                )
                NavigationBarItem(
                    selected = mode == DocMode.PDF,
                    onClick = { mode = DocMode.PDF },
                    icon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                    label = { Text("PDF", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                )
                NavigationBarItem(
                    selected = mode == DocMode.WECHAT,
                    onClick = { mode = DocMode.WECHAT },
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = null) },
                    label = { Text("公众号", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                )
                NavigationBarItem(
                    selected = mode == DocMode.PPTX,
                    onClick = { mode = DocMode.PPTX },
                    icon = { Icon(Icons.Default.Slideshow, contentDescription = null) },
                    label = { Text("PPTX", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 顶部应用功能条：可折叠，默认收起（仅留一条细触发条），展开显示应用名/深色/关于
            AppGlobalBar(
                expanded = appBarExpanded,
                onToggle = { appBarExpanded = !appBarExpanded },
                darkMode = darkMode,
                onToggleDark = {
                    darkMode = !darkMode
                    SettingsStore.saveDarkMode(context, darkMode)
                },
                onAbout = { showAbout = true }
            )
            // 四屏同时存活，仅切换可见性，避免切 Tab 丢失编辑状态。
            // initialUri 仅首次传递给对应模式，之后不再触发。
            // 注：外层为 Column 作用域，ColumnScope.AnimatedVisibility 扩展与顶层同名函数冲突，
            //     故此处全部使用全限定名（与 MdPptxScreen 的既有处理一致）。
            Box(Modifier.fillMaxSize().weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = mode == DocMode.WORD,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    WordScreen(
                        snackbar = snackbar,
                        initialUri = initialUri.takeIf { detected == DocMode.WORD }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = mode == DocMode.PDF,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    PdfScreen(
                        initialUri = initialUri.takeIf { detected == DocMode.PDF },
                        snackbar = snackbar
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = mode == DocMode.WECHAT,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    WeChatScreen(snackbar = snackbar)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = mode == DocMode.PPTX,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    MdPptxScreen(snackbar = snackbar)
                }
            }
        }
    }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/**
 * 顶部应用功能条：可折叠，默认收起 —— 收起时仅右侧留一条细触发条（为编辑/预览区腾出高度），
 * 展开后显示应用名、深色模式与「关于」。替代原先常驻的红色标题栏。
 */
@Composable
private fun AppGlobalBar(
    expanded: Boolean,
    onToggle: () -> Unit,
    darkMode: Boolean,
    onToggleDark: () -> Unit,
    onAbout: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Gavel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "陈律工具箱",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onToggleDark, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (darkMode) "浅色模式" else "深色模式",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onAbout, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "关于",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        // 常驻触发条：收起时仅一条细窄胶囊，点它展开/收起应用功能
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onToggle,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "功能",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起应用功能" else "展开应用功能",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("陈律工具箱", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("版本  V${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("构建号  ${BuildConfig.VERSION_CODE}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("开发者：陈伟律师", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "联系电话：139 7589 2485",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:13975892485"))
                                )
                            }
                        }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "集 Markdown 编辑、公文生成、PDF 处理、微信公众号排版与 PPT 制作于一体的移动办公工具。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    )
}
