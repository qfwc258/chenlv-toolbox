package com.wb.mdgw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.wb.mdgw.BuildConfig
import com.wb.mdgw.wechat.WeChatScreen
import com.wb.mdgw.pptx.MdPptxScreen
import com.wb.mdgw.shot.ShotScreen
import com.wb.mdgw.law.Law
import com.wb.mdgw.law.LawDetailScreen
import com.wb.mdgw.law.LawSearchScreen
import com.wb.mdgw.docgen.DocGenScreen
import com.wb.mdgw.ftp.FtpScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 支持从「打开方式 / 分享」进入；根据文件类型决定进入哪个功能
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

/** 顶层路由：主页宫格 + 各功能页（平铺，不再有底部 Tab） */
private enum class Route {
    HOME, WORD, PDF, WECHAT, PPTX, SETTINGS, SCREENSHOT, LAW_SEARCH, DOC_GEN, DOCUMENTS, FTP
}

/** 宫格功能项 */
private data class Feature(
    val route: Route,
    val title: String,
    val icon: ImageVector
)

private val HOME_FEATURES = listOf(
    Feature(Route.WORD, "WORD 文档", Icons.Default.Article),
    Feature(Route.PPTX, "PPTX 制作", Icons.Default.Slideshow),
    Feature(Route.PDF, "PDF 处理", Icons.Default.PictureAsPdf),
    Feature(Route.WECHAT, "公众号排版", Icons.Default.ChatBubble),
    Feature(Route.DOC_GEN, "生成文书", Icons.Default.EditNote),
    Feature(Route.SCREENSHOT, "截图排版", Icons.Default.Camera),
    Feature(Route.LAW_SEARCH, "法律查询", Icons.Default.Gavel),
    Feature(Route.DOCUMENTS, "我的文档", Icons.Default.Folder),
    Feature(Route.FTP, "FTP 服务", Icons.Default.Dns)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
    // 冷启动恢复一次全局共享设置（幂等）
    remember { AppSettings.init(context) }

    // 从「打开方式 / 分享」进入时直达对应功能，否则落到宫格主页
    val initialRoute = remember(initialUri) {
        if (initialUri == null) {
            Route.HOME
        } else {
            val name = FileUtils.displayName(context, initialUri).lowercase()
            val mime = runCatching { context.contentResolver.getType(initialUri) }.getOrNull()
            when {
                mime?.startsWith("image/") == true -> Route.SCREENSHOT
                name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") -> Route.SCREENSHOT
                name.endsWith(".pdf") || initialUri.toString().contains("pdf", true) -> Route.PDF
                name.endsWith(".docx") || name.endsWith(".doc") -> Route.WORD
                else -> Route.WORD
            }
        }
    }

    var route by remember { mutableStateOf(initialRoute) }
    var selectedLaw by remember { mutableStateOf<Law?>(null) }
    // 跨页流转：截图排版等产物一键带入「PDF 处理」（加页码/盖章）
    var pendingPdfUri by remember { mutableStateOf<Uri?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val darkMode by AppSettings.darkMode.collectAsState()

    // 返回逻辑：法条详情 → 关闭详情；其它功能页 → 回主页；主页不拦截（退出 App）
    BackHandler(enabled = selectedLaw != null) { selectedLaw = null }
    BackHandler(enabled = selectedLaw == null && route != Route.HOME) { route = Route.HOME }

    MdGwTheme(darkTheme = darkMode) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            // 各屏同时存活，仅切换可见性，避免返回主页丢失编辑状态（草稿、撤销栈）
            Box(Modifier.padding(pad).fillMaxSize()) {
                // 主页宫格
                AnimatedVisibility(route == Route.HOME, enter = fadeIn(), exit = fadeOut()) {
                    HomeScreen(onOpen = { route = it })
                }

                // WORD / PPTX / 公众号：全屏编辑器，用系统返回键回主页（保留最大编辑区）
                AnimatedVisibility(route == Route.WORD, enter = fadeIn(), exit = fadeOut()) {
                    WordScreen(
                        snackbar = snackbar,
                        initialUri = initialUri.takeIf { initialRoute == Route.WORD }
                    )
                }
                AnimatedVisibility(route == Route.PPTX, enter = fadeIn(), exit = fadeOut()) {
                    MdPptxScreen(snackbar = snackbar)
                }
                AnimatedVisibility(route == Route.WECHAT, enter = fadeIn(), exit = fadeOut()) {
                    WeChatScreen(snackbar = snackbar)
                }

                // 自带顶部栏的子页
                AnimatedVisibility(route == Route.DOC_GEN, enter = fadeIn(), exit = fadeOut()) {
                    DocGenScreen(onBack = { route = Route.HOME })
                }

                // 无自带标题栏的页面：统一套一个带返回的标题栏
                AnimatedVisibility(route == Route.PDF, enter = fadeIn(), exit = fadeOut()) {
                    SimpleScreenFrame("PDF 处理", onBack = { route = Route.HOME }) {
                        PdfScreen(
                            initialUri = pendingPdfUri ?: initialUri.takeIf { initialRoute == Route.PDF },
                            snackbar = snackbar
                        )
                    }
                }
                AnimatedVisibility(route == Route.SCREENSHOT, enter = fadeIn(), exit = fadeOut()) {
                    SimpleScreenFrame("截图排版", onBack = { route = Route.HOME }) {
                        ShotScreen(
                            snackbar = snackbar,
                            initialUri = initialUri.takeIf { initialRoute == Route.SCREENSHOT },
                            onOpenPdf = { uri ->
                                pendingPdfUri = uri
                                route = Route.PDF
                            }
                        )
                    }
                }
                AnimatedVisibility(route == Route.LAW_SEARCH, enter = fadeIn(), exit = fadeOut()) {
                    SimpleScreenFrame("法律查询", onBack = { route = Route.HOME }) {
                        LawSearchScreen(onLawClick = { law -> selectedLaw = law })
                    }
                }
                AnimatedVisibility(route == Route.DOCUMENTS, enter = fadeIn(), exit = fadeOut()) {
                    DocumentsScreen(
                        onBack = { route = Route.HOME },
                        snackbar = snackbar,
                        onOpenPdf = { uri ->
                            pendingPdfUri = uri
                            route = Route.PDF
                        }
                    )
                }
                AnimatedVisibility(route == Route.SETTINGS, enter = fadeIn(), exit = fadeOut()) {
                    SimpleScreenFrame("设置", onBack = { route = Route.HOME }) {
                        SettingsScreen()
                    }
                }
                AnimatedVisibility(route == Route.FTP, enter = fadeIn(), exit = fadeOut()) {
                    FtpScreen(onBack = { route = Route.HOME })
                }

                // 法条详情页（覆盖层）
                if (selectedLaw != null) {
                    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                        LawDetailScreen(
                            law = selectedLaw!!,
                            onBack = { selectedLaw = null }
                        )
                    }
                }
            }
        }
    }
}

/** 功能主页：一行三列宫格，可随功能数量自动换行扩展 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpen: (Route) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("陈律工具箱", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("常用工具一站式聚合", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }, actions = {
                IconButton(onClick = { onOpen(Route.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            })
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(HOME_FEATURES, key = { it.route.name }) { f ->
                FeatureCard(feature = f, onClick = { onOpen(f.route) })
            }
        }
    }
}

/** 宫格卡片：图标 + 标题，简洁居中 */
@Composable
private fun FeatureCard(feature: Feature, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                feature.icon,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                feature.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 给没有自带标题栏的页面套一个统一的顶部返回栏 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScreenFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { content() }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
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
                    "集 Markdown 编辑、公文生成、PDF 处理、微信公众号排版、PPT 制作、批量文书与法律查询于一体的移动办公工具。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
    )
}
