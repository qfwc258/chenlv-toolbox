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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inventory
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
import androidx.compose.ui.text.font.FontFamily
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
import com.wb.mdgw.catalog.CatalogKind
import com.wb.mdgw.catalog.CatalogScreen
import com.wb.mdgw.tableform.TableFormScreen
import com.wb.mdgw.ftp.FtpScreen
import com.wb.mdgw.injury.InjuryScreen
import com.wb.mdgw.update.UpdateManager

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
    // 颜色 + 自定义字版（BrandTokens.BrandTypography 覆盖 Material3 默认 Typography），
    // 不破坏现有 MaterialTheme.colorScheme.* 引用。
    MaterialTheme(colorScheme = colors, typography = BrandTokens.BrandTypography, content = content)
}

/** 顶层路由：主页宫格 + 各功能页（平铺，不再有底部 Tab） */
private enum class Route {
    HOME, WORD, PDF, WECHAT, PPTX, SETTINGS, SCREENSHOT, LAW_SEARCH, DOC_GEN,
    EVIDENCE_CATALOG, ARCHIVE_CATALOG, TABLE_FORM, DOCUMENTS, FTP, INJURY
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
    Feature(Route.EVIDENCE_CATALOG, "证据目录", Icons.Default.Article),
    Feature(Route.ARCHIVE_CATALOG, "法援目录", Icons.Default.Inventory),
    Feature(Route.TABLE_FORM, "表格填报", Icons.Default.GridView),
    Feature(Route.SCREENSHOT, "截图排版", Icons.Default.Camera),
    Feature(Route.LAW_SEARCH, "法律查询", Icons.Default.Gavel),
    Feature(Route.INJURY, "工伤赔偿", Icons.Default.Calculate),
    Feature(Route.DOCUMENTS, "我的文档", Icons.Default.Folder),
    Feature(Route.FTP, "FTP 服务", Icons.Default.Dns)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(initialUri: Uri? = null) {
    val context = LocalContext.current
    // 冷启动恢复一次全局共享设置（幂等）
    remember { AppSettings.init(context) }

    // 每日最多一次自动检查更新：仅刷新设置页红点，不弹窗打扰
    LaunchedEffect(Unit) {
        UpdateManager.refreshState(context)
        UpdateManager.maybeAutoCheck(context)
    }

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

                // WORD / PPTX / 公众号：全屏编辑器，常驻切换条左侧「主页」按钮可回主页
                AnimatedVisibility(route == Route.WORD, enter = fadeIn(), exit = fadeOut()) {
                    WordScreen(
                        snackbar = snackbar,
                        onBack = { route = Route.HOME },
                        initialUri = initialUri.takeIf { initialRoute == Route.WORD }
                    )
                }
                AnimatedVisibility(route == Route.PPTX, enter = fadeIn(), exit = fadeOut()) {
                    MdPptxScreen(snackbar = snackbar, onBack = { route = Route.HOME })
                }
                AnimatedVisibility(route == Route.WECHAT, enter = fadeIn(), exit = fadeOut()) {
                    WeChatScreen(snackbar = snackbar, onBack = { route = Route.HOME })
                }

                // 自带顶部栏的子页
                AnimatedVisibility(route == Route.DOC_GEN, enter = fadeIn(), exit = fadeOut()) {
                    DocGenScreen(onBack = { route = Route.HOME })
                }

                // 目录文书：证据目录 / 案卷归档目录（自带顶部栏）
                AnimatedVisibility(route == Route.EVIDENCE_CATALOG, enter = fadeIn(), exit = fadeOut()) {
                    CatalogScreen(kind = CatalogKind.EVIDENCE, onBack = { route = Route.HOME })
                }
                AnimatedVisibility(route == Route.ARCHIVE_CATALOG, enter = fadeIn(), exit = fadeOut()) {
                    CatalogScreen(kind = CatalogKind.ARCHIVE, onBack = { route = Route.HOME })
                }
                // 通用表格填报（导入任意含表格的 docx，手机填报，原位回填）
                AnimatedVisibility(route == Route.TABLE_FORM, enter = fadeIn(), exit = fadeOut()) {
                    TableFormScreen(onBack = { route = Route.HOME })
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
                AnimatedVisibility(route == Route.INJURY, enter = fadeIn(), exit = fadeOut()) {
                    SimpleScreenFrame("工伤赔偿", onBack = { route = Route.HOME }) {
                        InjuryScreen()
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

/** 功能主页：平铺宫格（不分组） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpen: (Route) -> Unit) {
    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "陈律工具箱",
                            style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)
                        )
                        Text(
                            "常用工具一站式聚合",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpen(Route.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = BrandTokens.BrandTopAppBarColors
            )
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 不分组：13 个功能按 HOME_FEATURES 声明顺序平铺
            items(HOME_FEATURES, key = { it.route.name }) { f ->
                FeatureCard(feature = f, onClick = { onOpen(f.route) })
            }
        }
    }
}

/** 分组标签保留 composable 定义（暂未使用，后续若需要分组可快速启用），
 *  实际不再被调用，避免删除已有引用造成编译错误。 */
@Suppress("unused")
private val EDITORIAL_ROUTES = setOf(
    Route.WORD, Route.WECHAT, Route.PPTX, Route.DOC_GEN,
    Route.TABLE_FORM, Route.SCREENSHOT
)
@Suppress("unused")
private val TOOL_ROUTES = setOf(
    Route.PDF, Route.LAW_SEARCH, Route.INJURY
)
@Suppress("unused")
private val SYS_ROUTES = setOf(
    Route.DOCUMENTS, Route.EVIDENCE_CATALOG, Route.ARCHIVE_CATALOG, Route.FTP
)

/**
 * 分组标题：左侧小色条 + 衬线标题 + 右侧计数
 * 用品牌色区分三组，比纯文本「文书编辑 / TOOLS / SYS」更有品牌感
 */
@Composable
private fun HomeSectionLabel(
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    count: Int
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 上方 16dp 留白让上一组卡片与本组标签之间有明显的视觉断开；
            // 下方 6dp 留白让标签与卡片之间不太空。
            .padding(start = 2.dp, end = 2.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 14.dp)
                .background(accent, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(6.dp))
        // 计数：用稍小字号 + 较低对比度，传达「信息密度」但不抢主标题风头
        Text(
            "· $count",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 宫格卡片：暖纸色背景 + 衬线标题 + 金色左线 + 微妙阴影
 * 关键变化（vs 旧版）：
 *  - 卡片背景从「surfaceVariant 半透明」改为「BrandParchment」宣纸色，纸张感更强
 *  - 加 1dp 金色左线点缀，对应「公文行格线」意象
 *  - 阴影由 Material3 Card 默认 elevation 提升到 2dp，立体感更强
 *  - 标题改用 Serif 字体 + Semibold，与品牌头呼应
 *  - 图标底色铺一层朱砂红 8% 透明圆形背景，让图标更有「印章」感
 */
@Composable
private fun FeatureCard(feature: Feature, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 从 104dp 降到 92dp：内容（40dp 图标 + 8dp 间距 + ~18dp 标题）= 66dp，
            // 剩余 26dp 留白比例约 30%，比原 46dp/45% 更紧凑，一屏多看一行。
            .height(92.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),     // 直角微圆，比 Material3 默认 12dp 略小，更稳重
        colors = CardDefaults.cardColors(
            containerColor = BrandTokens.BrandParchment
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 1.dp
        )
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧 1dp 金色细线
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(BrandTokens.BrandBronze.copy(alpha = 0.6f))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标：朱砂红 8% 透明圆形背景 + 朱砂红图标 = 印章意象
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            color = BrandTokens.BrandSealRed,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        feature.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    feature.title,
                    fontFamily = FontFamily.Serif,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/** 给没有自带标题栏的页面套一个统一的顶部返回栏（品牌版）
 *
 *  body 容器已经统一加了 14dp padding（与主页 LazyVerticalGrid 一致），子屏不需要再写。
 *  如需更紧凑/更松，可通过 `bodyPadding` 覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScreenFrame(
    title: String,
    onBack: () -> Unit,
    bodyPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(14.dp),
    content: @Composable () -> Unit
) {
    Scaffold(
        // 容器色改成宣纸色暖白，与主页卡片、WordScreen 视觉一致
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)  // P2：20sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回主页")
                    }
                },
                colors = BrandTokens.BrandTopAppBarColors
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // P1：统一 body padding 14dp，子屏无需重复写
            Box(Modifier.padding(bodyPadding).fillMaxSize()) { content() }
        }
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
                        .clip(RoundedCornerShape(8.dp))
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
