package com.wb.mdgw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
                // 全局功能：深色模式 + 关于，并入导航栏右侧（不增加导航栏高度，顶部零占用）
                IconButton(
                    onClick = {
                        darkMode = !darkMode
                        SettingsStore.saveDarkMode(context, darkMode)
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        if (darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (darkMode) "浅色模式" else "深色模式",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "关于",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    ) { pad ->
        // 顶部不再放置任何常驻控件：编辑/预览区直接顶到状态栏下方，最大化可用高度。
        // 四屏同时存活，仅切换可见性，避免切 Tab 丢失编辑状态。
        // initialUri 仅首次传递给对应模式，之后不再触发。
        // 注：全限定名 AnimatedVisibility 规避与 ColumnScope 扩展的同名歧义（编译安全）。
        Box(Modifier.padding(pad).fillMaxSize()) {
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

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
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
