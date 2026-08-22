package com.wb.mdgw.pptx

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.core.content.ContextCompat
import com.wb.mdgw.EditPreviewBar
import com.wb.mdgw.MdEditorPane
import com.wb.mdgw.MarkdownSnippets
import com.wb.mdgw.FileUtils
import com.wb.mdgw.ExportResultDialog
import com.wb.mdgw.UI_CARD_RADIUS
import com.wb.mdgw.UI_BTN_RADIUS
import com.wb.mdgw.UI_ACTION_HEIGHT

/** PPTX 文件 MIME（与文件导出/打开/分享保持一致）。 */
private const val PPTX_MIME =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** 将常见异常翻译为用户可读的中文提示。 */
private fun friendlyError(e: Throwable): String = when {
    e.message?.contains("permission", ignoreCase = true) == true -> "权限不足，请授予存储权限后重试"
    e.message?.contains("No space", ignoreCase = true) == true -> "存储空间不足，请清理后重试"
    e.message?.contains("FileNotFound", ignoreCase = true) == true || e.message?.contains("No such file", ignoreCase = true) == true -> "文件不存在，请检查后重试"
    e.message?.contains("Read-only", ignoreCase = true) == true -> "文件为只读，无法写入"
    else -> "操作失败，请稍后重试"
}

/** 预览画布使用的语义颜色常量，避免硬编码。 */
private val COVER_DARK_TEXT = Color(0xFF222222)
private val OVERFLOW_WARN = Color(0xFFC0392B)
private val TABLE_GRID = Color(0xFFC8C8C8)

/**
 * 「样式」弹窗的默认 CSS 模板（仅作参考/起点）。
 * 与出厂排版一致、且不含配色声明——配色仍由顶部「主题」控制；
 * 如需自定义颜色，取消对应 .accent / .cover 等注释行即可。
 */
private const val DEFAULT_CSS = """/* 全局：行距、正文默认字体 */
* { line-height: 1.2; font-family: "微软雅黑"; }

/* 各级标题字号与段后距（颜色由上方「主题」控制） */
h1 { font-size: 28pt; margin-bottom: 12pt; }
h2 { font-size: 24pt; margin-bottom: 12pt; }
h3 { font-size: 20pt; margin-bottom: 12pt; }

/* 正文字号与段后距 */
p { font-size: 16pt; margin-bottom: 8pt; }

/* 引用字号与段前距（margin-top：引用块与上方文本的间距） */
.quote { font-size: 15pt; margin-top: 12pt; }

/* 代码块字号与字体 */
.code { font-size: 13pt; font-family: "Consolas"; }

/* 主色调（强调色 / 引用条）：取消下一行注释即覆盖主题主色
.accent { color: #C0392B; } */

/* 封面底色：取消下一行注释即覆盖封面
.cover { background: #9E2A2B; } */

/* 画布与边距 */
.slide { width: 720pt; height: 405pt; margin: 30pt 40pt; }"""

/**
 * 「PPTX」Tab：MD → 自动智能分页 → 实时预览 → 导出可编辑 PPTX。
 *
 * 双页（编辑 / 预览）：编辑输入 Markdown，预览实时渲染 1:1 幻灯片；顶部切换主题与自动分页，
 * 底部一键导出 PPTX（原生可编辑）、导入 .md、清空。
 */
private enum class SubView { EDIT, PREVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MdPptxScreen(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val draft = remember { PptDraftStore.load(context) }

    var mdTfv by remember { mutableStateOf(TextFieldValue(draft?.markdown?.takeIf { it.isNotBlank() } ?: DEFAULT_MD)) }
    // 字号（各 Tab 独立记忆）与撤销/重做栈
    var fontSize by remember { mutableStateOf(15) }
    val undoStack = remember { ArrayDeque<TextFieldValue>() }
    val redoStack = remember { ArrayDeque<TextFieldValue>() }
    var themeId by remember { mutableStateOf(draft?.themeId ?: PptThemes.ALL[0].id) }
    var customColor by remember { mutableStateOf(draft?.customColor ?: "2E5FA3") }
    var autoPaginate by remember { mutableStateOf(draft?.autoPaginate ?: true) }
    var barHeightDenom by remember { mutableStateOf(draft?.barHeightDenom ?: 60) }   // 直线色块高度分母（1/N 页高）
    var bandGap by remember { mutableStateOf(draft?.bandGap ?: 24) }   // 版式间距：色块与正文间距（pt，全局统一）
    // 所有页面默认版式固定为「上下」（组合 = 上下/无/上左对齐）。预设选择器已移除，
    // 不再从草稿恢复历史 defaultLayout，保证默认组合恒为 需求2 指定值。
    var defaultLayout by remember { mutableStateOf(SlideLayout.STANDARD) }
    // 阶段二：逐页自由组合（结构 × 色块 × 对齐），作为每页版式的唯一控制；间距为全局设置（设置面板）。
    val comps = remember {
        mutableStateMapOf<Int, SlideComposition>().apply {
            draft?.comps?.forEach { (k, v) ->
                SlideComposition.fromKey(v)?.let { put(k, it) }
            }
        }
    }
    var layoutsVersion by remember { mutableStateOf(0) }
    var subView by remember { mutableStateOf(SubView.EDIT) }
    // 沉浸式布局：顶 / 底工具栏默认收起，仅常驻「编辑|预览」切换条
    var topExpanded by remember { mutableStateOf(false) }
    var bottomExpanded by remember { mutableStateOf(false) }

    // 自定义 CSS 样式（公众号式可编辑）：空 = 默认样式（保底）。持久化由 PptStyleStore 负责。
    var cssText by remember { mutableStateOf(PptStyleStore.load(context)) }
    var showStyleDialog by remember { mutableStateOf(false) }

    // 波浪装饰可调参数（浪高 / 透明度 / 层次对比）：默认 = 出厂效果 v1.7.9（保底）。持久化由 PptWaveStore 负责。
    var waveParams by remember { mutableStateOf(PptWaveStore.load(context)) }

    // Logo 装饰参数（大小 / 位置），持久化在 PptDraftStore 中
    var logoScale by remember { mutableStateOf(draft?.logoScale ?: 0.20f) }
    var logoHAlign by remember { mutableStateOf(draft?.logoHAlign ?: "right") }
    var logoVAlign by remember { mutableStateOf(draft?.logoVAlign ?: "bottom") }
    // 「全部应用：是/否」开关：进入预览时恢复上次选择（持久化在 PptDraftStore 中）
    var applyToAll by remember { mutableStateOf(draft?.applyToAll ?: false) }
    // 统一设置弹窗（自动分页 / 波浪 / 波浪参数 / 样式 全部收进弹窗，规避原横条芯片点击失效）
    var showSettings by remember { mutableStateOf(false) }

    val baseTheme = if (themeId == "custom") PptThemes.custom(customColor) else PptThemes.byId(themeId)

    // 解析自定义 CSS 样式（空文本 = 默认样式，保底）。仅显式声明的颜色字段才覆盖主题配色。
    val style = PptCssParser.parse(cssText)
    val theme = run {
        var t = baseTheme
        val ov = style.overrides
        if ("titleColor" in ov) t = t.copy(titleColor = style.titleColor)
        if ("bodyColor" in ov) t = t.copy(bodyColor = style.bodyColor)
        if ("accent" in ov) t = t.copy(accent = style.accent, quoteBg = style.quoteBg)
        else if ("quoteBg" in ov) t = t.copy(quoteBg = style.quoteBg)
        if ("codeBg" in ov) t = t.copy(codeBg = style.codeBg)
        if ("coverBg" in ov) t = t.copy(coverBg = style.coverBg)
        t
    }

    // 实时：解析 → 分页 → 布局（布局随逐页选择/默认布局/自定义主色/波浪参数/直线色块高度/全局间距联动）
    val slides by remember(mdTfv.text, autoPaginate, themeId, customColor, defaultLayout, barHeightDenom, bandGap, cssText, waveParams, logoScale) {
        derivedStateOf {
            // 若任一页面组合开启了波浪装饰，则内容区底边上移以预留波浪空间。
            val anyWave = comps.values.any { it.decoration == BottomDecoration.WAVE }
            val effBottom = PptLayoutEngine.waveAwareContentBottom(anyWave)
            PptLayoutEngine.style = if (effBottom >= 0) style.copy(contentBottomOverride = effBottom) else style
            PptLayoutEngine.waveParams = waveParams
            PptLayoutEngine.logoScale = logoScale
            PptLayoutEngine.logoHAlign = logoHAlign
            PptLayoutEngine.logoVAlign = logoVAlign
            val r = MdAstParser.parse(mdTfv.text)
            val paginated = MdAutoPaginator.paginate(r.blocks, autoPaginate, r.coverTitle)
            PptLayoutEngine.layout(
                paginated, theme, { _ -> defaultLayout },
                // 间距为全局设置（设置面板输入框）：渲染时统一覆盖每页组合的 bandGap
                compOf = { i -> comps[i]?.copy(bandGap = bandGap) },
                barHeightDenom = barHeightDenom
            )
        }
    }

    val scope = rememberCoroutineScope()

    // 自动保存草稿（编辑内容/布局变化后防抖落盘，下次进入自动恢复）
    LaunchedEffect(mdTfv.text, themeId, customColor, autoPaginate, barHeightDenom, bandGap, defaultLayout, logoScale, logoHAlign, logoVAlign, applyToAll, layoutsVersion) {
        delay(500)
        PptDraftStore.save(
            context,
            PptDraftStore.PptDraft(
                markdown = mdTfv.text,
                themeId = themeId,
                customColor = customColor,
                autoPaginate = autoPaginate,
                barHeightDenom = barHeightDenom,
                bandGap = bandGap,
                logoScale = logoScale,
                logoHAlign = logoHAlign,
                logoVAlign = logoVAlign,
                defaultLayout = defaultLayout.key,
                layouts = emptyMap(),
                comps = comps.mapValues { it.value.key },
                applyToAll = applyToAll
            )
        )
    }

    // 自动保存自定义样式 CSS（与内容草稿分开持久化，便于独立「恢复默认」）
    LaunchedEffect(cssText) {
        PptStyleStore.save(context, cssText)
    }

    // 自动保存波浪参数（与内容草稿 / CSS 分开持久化，便于独立「恢复默认」）
    LaunchedEffect(waveParams) {
        PptWaveStore.save(context, waveParams)
    }

    // 导出结果状态（与「公文」Tab 一致：保存到下载 + 弹窗「打开 / 分享」）
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var resultName by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf("") }

    // 文件命名对话框状态
    var showNameDialog by remember { mutableStateOf(false) }
    var fileNameInput by remember { mutableStateOf(TextFieldValue("陈律工具箱")) }

    /**
     * 导出 PPTX：直接生成字节流并写入「下载 / 陈律文档」，随后弹出结果对话框，
     * 提供「用其他应用打开」与「分享文件」按钮（与公文导出体验一致）。
     *
     * @param baseName 用户指定的文件名（不含扩展名），自动补 .pptx 并清理非法字符。
     */
    fun exportPptx(baseName: String) {
        if (slides.isEmpty()) {
            scope.launch { snackbar.showSnackbar("没有可导出的内容，请先在编辑区输入 Markdown") }
            return
        }
        val clean = baseName.trim().replace(Regex("""[\\/:*?"<>|]"""), "").ifBlank { "陈律工具箱" }
        val name = if (clean.endsWith(".pptx", ignoreCase = true)) clean else "$clean.pptx"
        scope.launch(Dispatchers.IO) {
            try {
                val cur = slides
                withContext(Dispatchers.Main) { exportProgress = "正在生成 PPTX（共 ${cur.size} 页）..." }
                val baos = java.io.ByteArrayOutputStream()
                PptLayoutEngine.waveParams = waveParams
                PptLayoutEngine.logoScale = logoScale
                PptLayoutEngine.logoHAlign = logoHAlign
                PptLayoutEngine.logoVAlign = logoVAlign
                PptExportEngine.exportPptx(cur, theme, style, baos)
                val bytes = baos.toByteArray()
                val sf = FileUtils.saveToDownloads(context, name, bytes, PPTX_MIME)
                withContext(Dispatchers.Main) {
                    exportProgress = ""
                    resultUri = sf.uri
                    resultName = name
                    resultPath = sf.displayPath
                    showResult = true
                    snackbar.showSnackbar("已导出 PPTX（${cur.size} 页）")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    exportProgress = ""
                    snackbar.showSnackbar(friendlyError(e))
                }
            }
        }
    }

    /** 「打开 / 分享」按钮：复用与公文一致的文件 Uri 操作。 */
    fun openOrShare(open: Boolean) {
        val uri = resultUri ?: return
        runCatching {
            context.startActivity(
                if (open) FileUtils.openIntent(uri, PPTX_MIME)
                else FileUtils.shareIntent(uri, resultName, PPTX_MIME)
            )
        }.onFailure {
            scope.launch { snackbar.showSnackbar(friendlyError(it)) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val txt = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            withContext(Dispatchers.Main) {
                mdTfv = TextFieldValue(txt); undoStack.clear(); redoStack.clear()
            }
        }
    }

    Scaffold(
        topBar = {
            // 顶部工具栏（主题/调色板/设置）：可折叠，默认收起（点切换条 ⌄ 展开）
            androidx.compose.animation.AnimatedVisibility(
                visible = topExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                PptxTopBar(
                    themeId = themeId,
                    onTheme = { themeId = it },
                    customColor = customColor,
                    onCustomColor = { customColor = it },
                    onSettings = { showSettings = true }
                )
            }
        },
        bottomBar = {
            // 底部操作栏（导出/导入/清空）：可折叠，默认收起（点切换条 ⌃ 展开）
            androidx.compose.animation.AnimatedVisibility(
                visible = bottomExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                PptxActionBar(
                    onExport = {
                        if (slides.isEmpty()) {
                            scope.launch { snackbar.showSnackbar("没有可导出的内容，请先在编辑区输入 Markdown") }
                        } else {
                            fileNameInput = TextFieldValue("陈律工具箱")
                            showNameDialog = true
                        }
                    },
                    onImport = { importLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
                    onClear = {
                        mdTfv = TextFieldValue(""); undoStack.clear(); redoStack.clear()
                    }
                )
            }
        }
    ) { pad ->
        // 根 Box：内容层 + 设置覆盖层（覆盖层为 Box 子项，填充内容区并绘制在最上，
        // 彻底规避「放 Column 内与内容争夺高度被压成 0」以及「AlertDialog 不显示」两类问题）
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // 常驻切换条：折叠顶栏开关 + 编辑/预览 + 折叠底栏开关（唯一常驻控件）
                EditPreviewBar(
                    selectedIndex = subView.ordinal,
                    onSelect = { subView = SubView.values()[it] },
                    topExpanded = topExpanded,
                    onToggleTop = { topExpanded = !topExpanded },
                    bottomExpanded = bottomExpanded,
                    onToggleBottom = { bottomExpanded = !bottomExpanded },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                )

                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = subView == SubView.EDIT,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        MdEditorPane(
                            tfv = mdTfv,
                            fontSize = fontSize,
                            onFontSizeChange = { fontSize = it },
                            onChange = { newTfv ->
                                if (newTfv.text != mdTfv.text) {
                                    undoStack.addLast(mdTfv)
                                    if (undoStack.size > 60) undoStack.removeFirst()
                                    redoStack.clear()
                                }
                                mdTfv = newTfv
                            },
                            onInsert = { s ->
                                val r = MarkdownSnippets.apply(mdTfv.text, mdTfv.selection.start, mdTfv.selection.end, s)
                                mdTfv = TextFieldValue(r.text, TextRange(r.caret))
                            },
                            onUndo = {
                                if (undoStack.isNotEmpty()) {
                                    redoStack.addLast(mdTfv)
                                    mdTfv = undoStack.removeLast()
                                }
                            },
                            canUndo = undoStack.isNotEmpty(),
                            onRedo = {
                                if (redoStack.isNotEmpty()) {
                                    undoStack.addLast(mdTfv)
                                    mdTfv = redoStack.removeLast()
                                }
                            },
                            canRedo = redoStack.isNotEmpty(),
                            onClear = {
                                mdTfv = TextFieldValue(""); undoStack.clear(); redoStack.clear()
                            },
                            title = "幻灯片",
                            hint = "将生成 ${slides.size} 页 · 版式：${defaultLayout.label}",
                            toolbarExpanded = topExpanded
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = subView == SubView.PREVIEW,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        PreviewPager(
                            slides = slides,
                            theme = theme,
                            snackbar = snackbar,
                            comps = comps,
                            defaultLayout = defaultLayout,
                            applyToAll = applyToAll,
                            onApplyToAllChange = { applyToAll = it },
                            onCompositionChange = { i, c ->
                                comps[i] = c
                                layoutsVersion++   // 触发草稿防抖保存
                            }
                        )
                    }
                }

                // 导出进度条
                if (exportProgress.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = UI_BTN_RADIUS,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(exportProgress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            // 设置覆盖层（内联 Box，不依赖 Dialog window；填充内容区并绘制在内容之上）
            if (showSettings) {
                PptxSettingsDialog(
                    autoPaginate = autoPaginate,
                    onAuto = { autoPaginate = it },
                    params = waveParams,
                    onParamsChange = { waveParams = it },
                    barHeightDenom = barHeightDenom,
                    onBarHeightDenom = { barHeightDenom = it },
                    bandGap = bandGap,
                    onBandGap = { bandGap = it },
                    logoScale = logoScale,
                    onLogoScale = { logoScale = it },
                    logoHAlign = logoHAlign,
                    onLogoHAlign = { logoHAlign = it },
                    logoVAlign = logoVAlign,
                    onLogoVAlign = { logoVAlign = it },
                    onStyle = { showSettings = false; showStyleDialog = true },
                    onDismiss = { showSettings = false }
                )
            }

            // 自定义样式（CSS）编辑覆盖层：同样是内联覆盖层，规避此前 AlertDialog 不显示问题
            if (showStyleDialog) {
                PptxStyleDialog(
                    initialCss = cssText,
                    defaultCss = DEFAULT_CSS,
                    context = context,
                    onApply = {
                        cssText = it
                        PptStyleStore.save(context, it)
                        showStyleDialog = false
                    },
                    onReset = {
                        cssText = ""
                        PptStyleStore.clear(context)
                        showStyleDialog = false
                    },
                    onDismiss = { showStyleDialog = false }
                )
            }
        }
    }

    // ---------- 导出结果（与「公文」Tab 一致：打开 / 分享） ----------
    ExportResultDialog(
        visible = showResult && resultUri != null,
        onDismiss = { showResult = false },
        title = "PPTX 已生成",
        fileName = resultName,
        savePath = resultPath,
        fileIcon = Icons.Default.Description,
        onOpen = { openOrShare(open = true) },
        onShare = { openOrShare(open = false) }
    )

    // ---------- 文件命名对话框（导出前填写文件名） ----------
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            icon = { Icon(Icons.Filled.Save, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) },
            title = { Text("命名并导出 PPTX", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        placeholder = { Text("例如：民事答辩状") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(".pptx 扩展名会自动添加；不支持 \\ / : * ? \" < > | 等字符", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    exportPptx(fileNameInput.text)
                }) { Text("导出") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("取消") } }
        )
    // ---------- 导出命名对话框（文件名）已在上方 ----------
}
}

// ────────────────────────────────────────────────
// 统一设置弹窗（自动分页 / 波浪 / 波浪参数 / 样式）
// ────────────────────────────────────────────────

/**
 * 设置面板：内联覆盖层（Box + Surface 卡片），不依赖 Dialog window。
 * 作为 Scaffold content 根 Box 的子项，必然显示在内容区之上，规避此前 AlertDialog 不显示问题。
 * 内含：自动分页 / 版式间距 / 波浪参数 / 直线色块高度 / 自定义样式(CSS)。
 * 底部装饰的开关已移至每页的「版式组合」选择器中，此处仅保留全局视觉参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PptxSettingsDialog(
    autoPaginate: Boolean,
    onAuto: (Boolean) -> Unit,
    params: PptWaveParams,
    onParamsChange: (PptWaveParams) -> Unit,
    barHeightDenom: Int,
    onBarHeightDenom: (Int) -> Unit,
    bandGap: Int,
    onBandGap: (Int) -> Unit,
    logoScale: Float,
    onLogoScale: (Float) -> Unit,
    logoHAlign: String,
    onLogoHAlign: (String) -> Unit,
    logoVAlign: String,
    onLogoVAlign: (String) -> Unit,
    onStyle: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) { onDismiss() }
    // 内联覆盖层：不依赖 Dialog window，作为 content 根 Box 的子项必然显示在内容区之上，
    // 彻底规避此前 AlertDialog 在该组合上下文下「点击已触发、弹窗却不显示」的问题。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                onClick = onDismiss,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                // 卡片本身消费点击，避免点卡片内空白处穿透到遮罩误关
                .clickable(
                    onClick = {},
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("设置", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                SectionLabel("排版")
                // 自动分页
                SettingToggleRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "自动分页",
                    desc = "按内容自动拆分多页",
                    checked = autoPaginate,
                    onCheckedChange = onAuto
                )
                // 版式间距（全局）：色块与正文的间距，数值直接输入
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Height, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("版式间距", fontSize = 14.sp)
                        Text(
                            "色块与正文的间距，全局生效",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GapNumField(value = bandGap, onChange = onBandGap)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionLabel("底部装饰参数（全局 · 在版式组合中逐页开关）")

                // 波浪参数（始终显示）：高度 / 透明度 / 层次对比，100% = 出厂效果
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "波浪参数（100% = 出厂效果）",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        WaveParamSlider(label = "波浪高度", value = params.heightScale, rangeMin = 0.4f, rangeMax = 1.6f, step = 0.05f) { onParamsChange(params.copy(heightScale = it)) }
                        WaveParamSlider(label = "波浪透明度", value = params.opacityScale, rangeMin = 0.3f, rangeMax = 1.2f, step = 0.05f) { onParamsChange(params.copy(opacityScale = it)) }
                        WaveParamSlider(label = "层次对比", value = params.contrast, rangeMin = 0.0f, rangeMax = 1.6f, step = 0.05f) { onParamsChange(params.copy(contrast = it)) }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 直线色块参数（始终显示）：高度可调（默认 1/60 页高），颜色跟随主题主色调
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("直线色块高度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "1/${barHeightDenom} 页高",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = barHeightDenom.toFloat(),
                            onValueChange = { onBarHeightDenom(it.roundToInt()) },
                            valueRange = 30f..100f,
                            steps = ((100f - 30f) / 5f - 1f).toInt().coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "越小越厚（1/30~1/100）；颜色跟随主题主色调，满屏宽、贴齐页底",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Logo 参数（始终显示）：大小 / 水平位置 / 垂直位置
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "Logo 参数（右下角 · 在版式组合中逐页开关）",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // 大小
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("大小", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "${(logoScale * 100).roundToInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = logoScale,
                            onValueChange = onLogoScale,
                            valueRange = 0.10f..0.30f,
                            steps = ((0.30f - 0.10f) / 0.02f - 1f).toInt().coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        // 水平位置
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("水平", fontSize = 13.sp, modifier = Modifier.weight(0.3f))
                            Pill("左", logoHAlign == "left") { onLogoHAlign("left") }
                            Pill("右", logoHAlign == "right") { onLogoHAlign("right") }
                        }
                        Spacer(Modifier.height(4.dp))
                        // 垂直位置
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("垂直", fontSize = 13.sp, modifier = Modifier.weight(0.3f))
                            Pill("上", logoVAlign == "top") { onLogoVAlign("top") }
                            Pill("下", logoVAlign == "bottom") { onLogoVAlign("bottom") }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                SectionLabel("样式")
                // 自定义样式
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Palette, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自定义样式 (CSS)", fontSize = 14.sp)
                        Text("行距 / 颜色 / 字体 / 字号…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onStyle) { Text("编辑", fontWeight = FontWeight.Bold) }
                }

                // 底部操作
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 恢复默认：波浪参数 / 色块高度 / 版式间距 一并复位
                    TextButton(onClick = {
                        onParamsChange(PptWaveParams())
                        onBarHeightDenom(60)
                        onBandGap(24)
                    }) { Text("恢复默认") }
                    Button(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text("完成") }
                }
            }
        }
    }
}

/** 设置弹窗中的分区小标题（紧凑、主色、字距收窄）。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * 版式间距数值输入框：仅数字、限 0~96pt，输入即生效。
 * 文本与状态双向同步：外部复位（恢复默认 / 草稿恢复）时跟随刷新，手动清空不回写状态。
 */
@Composable
private fun GapNumField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                val digits = t.filter { it.isDigit() }.take(2)   // 两位上限，覆盖 0~96
                text = digits                                    // 允许清空（暂不回写，保留原值）
                digits.toIntOrNull()?.let { n ->
                    val clamped = n.coerceIn(0, 96)              // 超限钳制，显示与状态保持一致
                    if (clamped != n) text = clamped.toString()
                    onChange(clamped)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 14.sp, textAlign = TextAlign.Center
            ),
            modifier = Modifier.width(88.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "pt", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 设置弹窗中的「图标 + 标题/副标题 + 开关」紧凑行。 */
@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 自定义样式（CSS）编辑：内联覆盖层，不依赖 Dialog window，规避此前 AlertDialog 不显示问题。
 * 支持：修改 / 持久（PptStyleStore）/ 恢复默认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PptxStyleDialog(
    initialCss: String,
    defaultCss: String,
    context: android.content.Context,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var edit by remember(initialCss) {
        mutableStateOf(if (initialCss.isBlank()) defaultCss else initialCss)
    }
    BackHandler(enabled = true) { onDismiss() }
    // 内联覆盖层：与设置面板一致的 Box 遮罩 + Surface 卡片，必然显示在内容区之上
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                onClick = onDismiss,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 560.dp)
                // 卡片本身消费点击，避免点卡片内空白处穿透到遮罩误关
                .clickable(
                    onClick = {},
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("自定义样式（CSS）", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                Text(
                    "支持行距 / 段距 / 颜色 / 字体 / 字号 / 画布等，实时作用于预览与导出。留空或「恢复默认」即恢复出厂样式。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = edit,
                    onValueChange = { edit = it },
                    label = { Text("CSS 样式") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    singleLine = false
                )

                // 底部操作
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) { Text("恢复默认") }
                    Button(onClick = { onApply(edit.trim()) }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text("应用") }
                }
            }
        }
    }
}

/** 单个波浪参数滑块（标签 + 百分比读数 + Material3 Slider）。 */
@Composable
private fun WaveParamSlider(
    label: String,
    value: Float,
    rangeMin: Float,
    rangeMax: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}%",
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = rangeMin..rangeMax,
            steps = ((rangeMax - rangeMin) / step - 1f).toInt().coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ────────────────────────────────────────────────
// 顶部控制栏（紧凑一行：主题 + 开关，药丸风格）
// ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PptxTopBar(
    themeId: String,
    onTheme: (String) -> Unit,
    customColor: String,
    onCustomColor: (String) -> Unit,
    onSettings: () -> Unit
) {
    // 直接返回 Row 作为 topBar：左侧主题+调色板限定 80% 宽度（可横滑，绝不用 weight 撑满），
    // 设置按钮紧贴其右、远离最右边缘 —— 规避「weight(1f)+horizontalScroll 左侧 Row 把右侧按钮推出屏幕、
    // 绘制可见但触摸被裁」的根因（与 WordToolbar 的 Spacer(weight) 模式等价且更稳）。
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：主题选择 + 调色板（最多占 80% 宽，超出横滑；不占满，保证右侧按钮有足够且完整的点击区）
        Row(
            Modifier
                .fillMaxWidth(0.8f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemeChip(themeId) { onTheme(it) }
            if (themeId == "custom") {
                ColorPalette(customColor) { onCustomColor(it) }
            }
        }

        Spacer(Modifier.width(10.dp))

        // 唯一交互按钮 = 设置（展开全部控件弹窗）。使用 FilledTonalIconButton，与 Word tab 一致。
        FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "设置",
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

// ────────────────────────────────────────────────
// 底部操作栏（悬浮卡片：导出 / 导入 / 清空，等宽 + 图标）
// ────────────────────────────────────────────────

@Composable
private fun PptxActionBar(onExport: () -> Unit, onImport: () -> Unit, onClear: () -> Unit) {
    Surface(
        tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
        shape = UI_CARD_RADIUS, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val btnMod = Modifier.weight(1f).height(UI_ACTION_HEIGHT)
            val btnPad = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            Button(onClick = onExport, shape = UI_BTN_RADIUS, modifier = btnMod, contentPadding = btnPad) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("导出PPTX", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onImport, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("导入", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onClear, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("清空", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ────────────────────────────────────────────────
// 主题选择（紧凑药丸按钮 + 下拉菜单）
// ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChip(selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = if (selectedId == "custom") "自定义" else PptThemes.byId(selectedId).name
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(selectedName, fontSize = 11.sp, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp)) },
            modifier = Modifier.height(30.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PptThemes.ALL.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name, fontSize = 13.sp) },
                    onClick = { onSelect(t.id); expanded = false },
                    leadingIcon = if (t.id == selectedId) {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            DropdownMenuItem(
                text = { Text("自定义主色", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) },
                onClick = { onSelect("custom"); expanded = false }
            )
        }
    }
}

/** 自定义主色调色板：点选即设定整套 PPTX 的主色调（章节色块/封面/竖线/表头统一生效）。 */
@Composable
private fun ColorPalette(selected: String, onPick: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            PptThemes.CUSTOM_PALETTE.forEach { c ->
                val isSel = c.equals(selected, true)
                val color = hexToColor(c)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (isSel) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier.border(1.2.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                            }
                        )
                        .clickable(remember { MutableInteractionSource() }, null) {
                            onPick(c)
                        }
                ) {
                    // 选中态显示白色勾选标记
                    if (isSel) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp).align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────
// 预览（1:1 还原导出效果）
// ────────────────────────────────────────────────

@Composable
private fun PreviewPager(
    slides: List<PptLayoutEngine.LaidOutSlide>,
    theme: PptTheme,
    snackbar: SnackbarHostState,
    comps: SnapshotStateMap<Int, SlideComposition>,
    defaultLayout: SlideLayout,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    onCompositionChange: (Int, SlideComposition) -> Unit
) {
    var current by remember { mutableStateOf(0) }
    // applyToAll 由父级持有 + 持久化，PreviewPager 只消费
    // 页数与选中页同步（分页变化或删除时收敛）
    LaunchedEffect(slides.size) {
        current = current.coerceIn(0, slides.lastIndex.coerceAtLeast(0))
    }
    // 跳转输入框（显示当前页码，回车跳转到指定页）
    var jumpText by remember { mutableStateOf((current + 1).toString()) }
    LaunchedEffect(current) { jumpText = (current + 1).toString() }
    val curLayout = defaultLayout
    val scope = rememberCoroutineScope()

    // 应用/读取统一的组合变更：内部修改 comps 后通过 onCompositionChange
    // 把每一次变更冒泡给父级，由父级统一负责布局重算与草稿防抖保存。
    val apply: (Int, SlideComposition) -> Unit = { i, c -> onCompositionChange(i, c) }

    Column(Modifier.fillMaxSize()) {
        if (slides.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无内容", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            // 预览画布：直接撑满可用宽度，16:9 画布按宽度最大化（无圆角卡片内边距）
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                SlideCanvas(slides[current], theme, Modifier.fillMaxSize())
            }
        }

        // ── 组合选择器：结构 × 色块 × 对齐（每页版式的唯一控制；间距已移入设置面板）──
        if (slides.isNotEmpty()) {
            CompositionSelector(
                comp = comps[current] ?: CompositionResolver.compositionOf(curLayout),
                applyToAll = applyToAll,
                onApplyToAllChange = { onApplyToAllChange(it) },
                theme = theme,
                onCompositionChange = { newComp ->
                    if (applyToAll) {
                        // 全部应用：跳过特殊页（封面/目录/章节/结尾），避免破坏其专属版式
                        // 仅对 PageRole.NONE 的内容页统一组合，保证视觉一致
                        var applied = 0
                        var skipped = 0
                        for (i in slides.indices) {
                            val sRole = slides[i].composition?.role ?: PageRole.NONE
                            if (sRole != PageRole.NONE) { skipped++; continue }
                            apply(i, newComp)
                            applied++
                        }
                        // 给用户一个反馈：让用户知道哪些页没被覆盖
                        if (skipped > 0) {
                            scope.launch {
                                snackbar.showSnackbar("已应用 $applied 页；跳过 $skipped 张特殊页（封面/目录/章节/结尾）")
                            }
                        }
                    } else {
                        apply(current, newComp)
                    }
                }
            )
        }

        // ── 翻页器（圆形箭头 + 胶囊页码 + 下划线跳转框）──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 左翻页
            IconButton(
                onClick = { if (current > 0) current-- },
                modifier = Modifier.size(36.dp),
                enabled = current > 0
            ) { Icon(Icons.Default.ChevronLeft, null, modifier = Modifier.size(20.dp)) }

            // 页码胶囊
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = UI_BTN_RADIUS,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Text(
                    "${current + 1} / ${slides.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // 右翻页
            IconButton(
                onClick = { if (current < slides.lastIndex) current++ },
                modifier = Modifier.size(36.dp),
                enabled = current < slides.lastIndex
            ) { Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp)) }

            // 跳转输入框（下划线样式，置于右箭头右侧，保留间距）
            Spacer(Modifier.width(12.dp))
            TextField(
                value = jumpText,
                onValueChange = { jumpText = it.filter { c -> c.isDigit() }.take(4) },
                placeholder = { Text("⇢", fontSize = 13.sp, color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val target = jumpText.toIntOrNull()
                    if (target != null && slides.isNotEmpty()) {
                        current = (target - 1).coerceIn(0, slides.lastIndex)
                    }
                }),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, textAlign = TextAlign.Center, color = Color.Black),
                modifier = Modifier.width(92.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedTextColor = Color.Black,
                    focusedTextColor = Color.Black,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    unfocusedPlaceholderColor = Color.Gray,
                    focusedPlaceholderColor = Color.Gray
                )
            )
        }
    }
}

/**
 * 阶段二组合选择器：结构 × 色块 × 对齐 × 装饰，作为每页版式的唯一控制。
 *
 * 预览区有空间时各轴选项直接展开（免去来回切换），顶部保留「全部应用」开关：
 *  - 自上而下依次为 结构 / 色块 / 对齐 / 栏宽(仅多栏) / 装饰，每条横向滚动；
 *  - 色块项带主题色迷你图示；装饰项支持长按 Pill 弹 Tooltip 说明效果。
 *
 * 「全部应用=是」时跳过特殊页（封面/目录/章节/结尾），由父级 PreviewPager 处理。
 */

/** 是否为多栏结构（左右/三栏/四栏）：`栏宽` 轴只在此时启用。 */
private val SlideComposition.isMultiCol: Boolean
    get() = structure == Structure.TWO_COL || structure == Structure.THREE_COL || structure == Structure.FOUR_COL

@Composable
private fun CompositionSelector(
    comp: SlideComposition,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    theme: PptTheme,
    onCompositionChange: (SlideComposition) -> Unit
) {
    val isMultiCol = comp.isMultiCol
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = UI_CARD_RADIUS,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // 顶部行：版式标题 + 右侧「全部应用」（预览区有空间，各轴选项直接展开）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "版式布局",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "全部应用",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Pill(if (applyToAll) "是" else "否", applyToAll, compact = true) {
                    onApplyToAllChange(!applyToAll)
                }
            }

            // 下方：全部轴选项直接展开，免去来回切换
            AxisSection("结构") {
                ToolRow {
                    Structure.values().forEach { s ->
                        Pill(s.label, comp.structure == s) { onCompositionChange(comp.copy(structure = s)) }
                    }
                }
            }
            AxisSection("色块") {
                ToolRow {
                    ColorBlock.values().forEach { c ->
                        val (iconColor, _) = colorBlockVisual(c)   // 形状信息已通过 iconShape 单独传入
                        PillWithIcon(
                            text = c.label,
                            iconColor = iconColor,
                            iconShape = c,
                            selected = comp.colorBlock == c,
                            compact = true,
                            theme = theme
                        ) { onCompositionChange(comp.copy(colorBlock = c)) }
                    }
                }
            }
            AxisSection("对齐") {
                ToolRow {
                    AlignmentCell("上左", comp.valign == VAlign.TOP && comp.halign == HAlign.LEFT) {
                        onCompositionChange(comp.copy(valign = VAlign.TOP, halign = HAlign.LEFT))
                    }
                    AlignmentCell("上中", comp.valign == VAlign.TOP && comp.halign == HAlign.CENTER) {
                        onCompositionChange(comp.copy(valign = VAlign.TOP, halign = HAlign.CENTER))
                    }
                    AlignmentCell("中左", comp.valign == VAlign.CENTER && comp.halign == HAlign.LEFT) {
                        onCompositionChange(comp.copy(valign = VAlign.CENTER, halign = HAlign.LEFT))
                    }
                    AlignmentCell("中中", comp.valign == VAlign.CENTER && comp.halign == HAlign.CENTER) {
                        onCompositionChange(comp.copy(valign = VAlign.CENTER, halign = HAlign.CENTER))
                    }
                }
            }
            if (isMultiCol) {
                AxisSection("栏宽") {
                    ColumnWidthBar(comp.colRatio) {
                        onCompositionChange(comp.copy(colRatio = it))
                    }
                }
            }
            AxisSection("装饰") {
                if (comp.hasBigBlock) {
                    Text(
                        "有色块时不可用",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                } else {
                    ToolRow {
                        BottomDecoration.values().forEach { d ->
                            PillWithTooltip(
                                text = d.label,
                                tip = decorationTip(d),
                                selected = comp.decoration == d
                            ) { onCompositionChange(comp.copy(decoration = d)) }
                        }
                    }
                }
            }
        }
    }
}

/** 版式轴小标题 + 其下一条选项行（直接展开时用）。 */
@Composable
private fun AxisSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            title,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        content()
    }
}

/** 单条横向滚动的选项行：承载某一个轴的全部 Pickable 项。 */
@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 「栏宽」轴：主栏占比滑块 + 「智能」复位。
 * - 滑块拖动 → 手动比例（非 null）；点「智能」→ 回到按内容自动配比（null，默认）。
 */
@Composable
private fun ColumnWidthBar(colRatio: Int?, onRatioChange: (Int?) -> Unit) {
    val current = colRatio ?: 50   // null(智能) 时用 50 作为滑块位置占位
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill(if (colRatio == null) "智能" else "手动", colRatio == null, compact = true) {
                onRatioChange(null)
            }
            Text(
                if (colRatio == null) "按内容分栏" else "主栏 ${colRatio}%",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Slider(
            value = current.toFloat(),
            onValueChange = { onRatioChange(it.roundToInt()) },
            valueRange = 20f..80f,
            steps = 11,
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
    }
}

/** 色块枚举 → (迷你图示色, 形状)。迷你图示在 Pill 左侧画出主题色的小色块。 */
private fun colorBlockVisual(c: ColorBlock): Pair<String, Boolean> = when (c) {
    ColorBlock.NONE -> "#E0E0E0" to false   // 灰白底（无色块）
    ColorBlock.COVER -> "theme" to false     // 主题色整块
    ColorBlock.LEFT -> "theme" to true       // 主题色左侧竖条
    ColorBlock.TOP -> "theme" to false       // 主题色顶部横条
    ColorBlock.BOTTOM -> "theme" to false    // 主题色底部横条
    ColorBlock.RIGHT -> "theme" to true      // 主题色右侧竖条
}

/** Pill 文字 + 主题色迷你图示（用于「色块」行）。
 *  [iconShape] 决定色块的几何形态：整块/竖条/横条/无；颜色取自 [theme.accent]。 */
@Composable
private fun PillWithIcon(
    text: String,
    iconColor: String,
    iconShape: ColorBlock,
    selected: Boolean,
    compact: Boolean = false,
    theme: PptTheme,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    // 解码主题色（"#RRGGBB" → Color）
    val accentColor = remember(theme.accent) { parseHexColor(theme.accent) }
    val grayColor = remember { parseHexColor("#E0E0E0") }
    val drawColor = if (iconColor == "theme") accentColor else grayColor
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.height(if (compact) 24.dp else 28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            // 左侧 8dp 宽 × 14dp 高的迷你色块图示（按形状决定占满 / 竖条 / 横条）
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 14.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        when (iconShape) {
                            ColorBlock.NONE -> grayColor
                            ColorBlock.COVER -> drawColor
                            ColorBlock.TOP -> drawColor
                            ColorBlock.BOTTOM -> drawColor
                            ColorBlock.LEFT -> drawColor
                            ColorBlock.RIGHT -> drawColor
                        }
                    )
            )
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 10.sp, color = fg, maxLines = 1)
        }
    }
}

/** 对齐网格中的单格：36dp 宽的方形 Pill，含 2 字对齐标签。 */
@Composable
private fun AlignmentCell(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.size(width = 38.dp, height = 26.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 10.sp, color = fg, maxLines = 1)
        }
    }
}

/** 装饰选项的悬浮提示文本。 */
private fun decorationTip(d: BottomDecoration): String = when (d) {
    BottomDecoration.NONE -> "不画任何装饰"
    BottomDecoration.WAVE -> "底部一条波浪曲线"
    BottomDecoration.BAR -> "底部一条直线色块"
    BottomDecoration.LOGO -> "右下角放置 Logo"
}

/** Pill + 长按弹 Tooltip（用于「装饰」行：4 个选项效果不直观时给文字说明）。 */
@Composable
private fun PillWithTooltip(text: String, tip: String, selected: Boolean, onClick: () -> Unit) {
    var showTip by remember { mutableStateOf(false) }
    Box {
        Pill(text, selected, onClick = onClick)
        // 透明长按热区：让长按事件能落到 Pill 上（Surface 已支持 onClick，但长按需要用 Modifier.pointerInput）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { showTip = true }
                    )
                }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = showTip,
            onDismissRequest = { showTip = false }
        ) {
            androidx.compose.material3.Text(tip, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
        }
    }
}

/** 解析 "#RRGGBB" → androidx.compose.ui.graphics.Color。 */
private fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color {
    val s = hex.removePrefix("#")
    val v = s.toLong(16)
    return androidx.compose.ui.graphics.Color(
        red = ((v shr 16) and 0xFF) / 255f,
        green = ((v shr 8) and 0xFF) / 255f,
        blue = (v and 0xFF) / 255f,
        alpha = 1f
    )
}

/** 紧凑胶囊按钮（仅文字，选中态实色填充）。
 *  [compact]=true 时按键更小（高度 22dp、无垂直内边距），
 *  让「色块」6 个选项能在窄屏上排开不换行；默认保持原 28dp 标准规格。 */
@Composable
private fun Pill(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.height(if (compact) 22.dp else 28.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun SlideCanvas(slide: PptLayoutEngine.LaidOutSlide, theme: PptTheme, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 在可用区域内按 16:9 适配（取限制维度），并居中
        val availW = maxWidth.value
        val availH = maxHeight.value
        val wByH = availH * 720f / 405f
        val w = if (availW <= wByH) availW else wByH
        val h = w * 405f / 720f
        val scale = w / 720f
        val density = LocalDensity.current.density
        val accentBg = slide.deco?.accentBg == true
        val bgColor = when {
            slide.cover -> theme.coverBg
            accentBg -> theme.accent
            else -> theme.bg
        }
        Box(Modifier.width(w.dp).height(h.dp).background(hexToColor(bgColor))) {
            // 底部波浪装饰（内容页、目录页、结尾页，开关打开时）——置于最底层，透明度叠加出柔和层次
            if (slide.deco?.wave == true) {
                val waveColor = slide.deco.waveColor ?: theme.accent
                val layers = PptLayoutEngine.generateWaveLayers(waveColor)
                Canvas(Modifier.fillMaxSize()) {
                    // 直接传入 Canvas 实际 dp 尺寸（不再用 scale 变换）
                    // 引擎存的是 0~1 归一化坐标，在此处 × 实际尺寸 → 波浪底边必然贴齐 Canvas 底部
                    for (layer in layers) {
                        val path = PptLayoutEngine.waveLayerToPath(layer, size.width, size.height)
                        // 应用该层透明度，叠加出柔和渐层（与导出端同源）
                        drawPath(path.asComposePath(), color = hexToColor(layer.color).copy(alpha = layer.alpha))
                    }
                }
            }
            // 底部直线色块装饰（与波浪并列、可独立开关）：满屏宽、贴齐页底、高度 = 画布高 1/N（默认 1/60），颜色跟随主题主色调
            if (slide.deco?.bottomBar == true) {
                val barColorStr = theme.accent
                val barHpt = (slide.deco.bottomBarH.takeIf { it > 0 } ?: (PptLayoutEngine.style.canvasH / 60)).toFloat()
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                0,
                                ((PptLayoutEngine.style.canvasH - barHpt) * scale * density).roundToInt()
                            )
                        }
                        .width((PptLayoutEngine.style.canvasW * scale).dp)
                        .height((barHpt * scale).dp)
                        .background(hexToColor(barColorStr))
                )
            }
            // Logo 装饰（右下角）：红色斜角块 + LAWYER.C 文字
            if (slide.deco?.logo == true) {
                val logoRed = Color(0xFFD31B29)
                val ls = PptLayoutEngine.logoScale
                val logoW = (PptLayoutEngine.style.canvasW * scale * ls).dp
                val logoH = (logoW.value * 180f / 640f).dp
                val lh = PptLayoutEngine.logoHAlign
                val lv = PptLayoutEngine.logoVAlign
                val logoX = if (lh == "right") (PptLayoutEngine.style.canvasW * scale * (1f - ls)).dp else 0.dp
                val logoY = if (lv == "bottom") (PptLayoutEngine.style.canvasH * scale - logoH.value).dp else 0.dp
                val textMeasurer = rememberTextMeasurer()
                Box(
                    Modifier
                        .offset(x = logoX, y = logoY)
                        .size(logoW, logoH)
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cw = size.width
                        val ch = size.height
                        // 红色斜角四边形
                        val redShape = ComposePath().apply {
                            moveTo(0f, 0f)
                            lineTo(cw * 0.195f, ch * 0.02f)
                            lineTo(cw * 0.170f, ch)
                            lineTo(0f, ch)
                            close()
                        }
                        drawPath(path = redShape, color = logoRed)
                        // LAWYER.C 文字
                        val textStyle = TextStyle(
                            fontSize = (logoW.value * 104f / 640f).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val measured = textMeasurer.measure("LAWYER.C", textStyle)
                        val textOffset = Offset(x = cw * 0.21f, y = ch * 0.14f)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = textOffset,
                            drawStyle = Stroke(width = cw * 0.003f),
                            color = Color(0xFFCFCFCF)
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = textOffset,
                            color = Color.White
                        )
                    }
                }
            }
            // 标题 / 引用左侧强调竖条、封面色条、强调线等装饰矩形
            val barColor = slide.deco?.barColor ?: theme.accent
            slide.deco?.bars?.forEach { b ->
                Box(
                    Modifier.offset {
                        IntOffset((b.x * scale * density).roundToInt(), (b.y * scale * density).roundToInt())
                    }.width((b.w * scale).dp).height((b.h * scale).dp)
                        .background(hexToColor(barColor))
                )
            }
            // 引用块（Markdown `>`）浅色圆角背景底色，用主题 quoteBg 色（与 H3 竖条 accent 区分）
            slide.deco?.quoteBg?.forEach { b ->
                Box(
                    Modifier.offset {
                        IntOffset((b.x * scale * density).roundToInt(), (b.y * scale * density).roundToInt())
                    }.width((b.w * scale).dp).height((b.h * scale).dp)
                        .background(hexToColor(theme.quoteBg), shape = RoundedCornerShape(6.dp))
                )
            }
            slide.units.forEach { unit -> UnitBox(unit, theme, scale, slide.cover, accentBg) }
        }
    }
}

@Composable
private fun BoxScope.UnitBox(unit: PptLayoutEngine.LaidOutUnit, theme: PptTheme, scale: Float, cover: Boolean, accentBg: Boolean) {
    val x = (unit.x * scale).dp
    val y = (unit.y * scale).dp
    val uw = (unit.w * scale).dp
    val uh = (unit.h * scale).dp
    val density = LocalDensity.current.density

    // 封面背景可能偏亮（如简约灰白），按背景明暗自适应前景色；强调背景(accentBg)始终白字
    val coverText = if (isLight(theme.coverBg)) COVER_DARK_TEXT else Color.White
    val baseColor = when {
        unit.color != null -> hexToColor(unit.color)   // 显式颜色覆盖（如目录标题反白）
        accentBg -> Color.White
        cover -> coverText
        unit.type == BlockType.H1 || unit.type == BlockType.H2 || unit.type == BlockType.H3 ||
        unit.type == BlockType.H4 || unit.type == BlockType.H5 || unit.type == BlockType.H6 -> hexToColor(theme.titleColor)
        else -> hexToColor(theme.bodyColor)
    }

    // 布局引擎用字符宽度估算的 uh 可能偏离 Compose 实际渲染高度（CJK 混排尤其明显）。
    // 若强制设 height(uh)+clip，估算偏小时文字被裁切，估算偏大时留白过多。
    // 改为不限制高度、不裁切：让 Compose 自身测量决定实际渲染尺寸，
    // 文字永远完整显示；位置 (x,y) 仍由引擎控制（分页/行间距仍基于估算）。
    // gapAfter 段后距以底部 padding 形式体现，保证段间视觉间隔与引擎一致。
    val gapDp = (unit.gapAfter * scale).dp.coerceAtLeast(0.dp)
    Box(
        Modifier.offset { IntOffset((x.value * density).roundToInt(), (y.value * density).roundToInt()) }
            .width(uw).padding(bottom = gapDp)
    ) {
        when (unit.type) {
            BlockType.CODE -> Box(Modifier.background(hexToColor(theme.codeBg)).padding(4.dp)) {
                Text(
                    unit.fragments.joinToString("") { it.text },
                    fontFamily = FontFamily.Monospace,
                    fontSize = (PptLayoutEngine.style.fsCode * scale).sp,
                    lineHeight = (PptLayoutEngine.style.fsCode * PptLayoutEngine.style.lineMult * scale).sp,
                    color = baseColor
                )
            }
            BlockType.BULLET_LIST, BlockType.ORDERED_LIST -> Column(Modifier.fillMaxSize()) {
                unit.listItems.forEachIndexed { i, item ->
                    // 前缀：顶层用列表类型前缀；嵌套层用缩进+短横线
                    // 有序列表：优先使用 MD 原文编号（item.number），无则 fallback 到自动编号
                    val prefix = when {
                        item.indent == 0 && unit.ordered -> "${item.number ?: (unit.listStart + i + 1)}. "
                        item.indent == 0 -> "•  "
                        else -> "  ${"  ".repeat(item.indent - 1)}- "
                    }
                    val annotated = toAnnotatedString(
                        listOf(InlineFragment(prefix)) + item.fragments,
                        baseColor, theme
                    )
                    Text(
                        annotated,
                        fontSize = (PptLayoutEngine.style.fsBody * scale).sp,
                        lineHeight = (PptLayoutEngine.style.fsBody * PptLayoutEngine.style.lineMult * scale).sp,
                        modifier = if (item.indent > 0) Modifier.padding(start = (item.indent * 18 * scale).dp) else Modifier
                    )
                }
            }
            BlockType.TABLE -> unit.table?.let { tr ->
                val cellColor = if (accentBg) Color.White else if (cover) coverText else baseColor
                Column(Modifier.fillMaxSize()) {
                    if (tr.header.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().height((tr.headerH * scale).dp)
                                .background(hexToColor(theme.accent))
                        ) {
                            tr.header.forEachIndexed { j, frags ->
                                Box(
                                    Modifier.width(((tr.colW.getOrNull(j) ?: 0) * scale).dp).fillMaxHeight()
                                        .padding((PptLayoutEngine.style.tablePad * scale).dp),
                                    contentAlignment = tableAlignToContent(tr.colAlign.getOrNull(j) ?: TableAlign.LEFT)
                                ) {
                                    Text(
                                        toAnnotatedString(frags, Color.White, theme),
                                        fontSize = (tr.headerFs * scale).sp,
                                        lineHeight = (tr.headerFs * PptLayoutEngine.style.lineMult * scale).sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    tr.rows.forEachIndexed { i, row ->
                        Row(
                            Modifier.fillMaxWidth().height((tr.rowHs[i] * scale).dp)
                                .border(BorderStroke((0.75f * scale).dp, TABLE_GRID))
                        ) {
                            row.forEachIndexed { j, frags ->
                                Box(
                                    Modifier.width(((tr.colW.getOrNull(j) ?: 0) * scale).dp).fillMaxHeight()
                                        .padding((PptLayoutEngine.style.tablePad * scale).dp),
                                    contentAlignment = tableAlignToContent(tr.colAlign.getOrNull(j) ?: TableAlign.LEFT)
                                ) {
                                    Text(
                                        toAnnotatedString(frags, cellColor, theme),
                                        fontSize = (tr.cellFs * scale).sp,
                                        lineHeight = (tr.cellFs * PptLayoutEngine.style.lineMult * scale).sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // 居中对齐的 H3：竖线以内联方式绘制，与文字作为一个整体在文本框内水平居中
                val isCenterH3 = unit.type == BlockType.H3 && unit.align == PptLayoutEngine.Align.CENTER
                if (isCenterH3) {
                    val barW = (3 * scale).dp.coerceAtLeast(2.dp)
                    val gapW = (6 * scale).dp.coerceAtLeast(4.dp)
                    // 整体居中：Row 不撑满宽度，内容（竖线+间距+文字）自然排列后由 Box 居中放置
                    Row(
                        Modifier.wrapContentWidth().height((unit.h * scale).dp.coerceAtLeast(8.dp))
                            .align(Alignment.Center),   // 在父 Box 内水平居中
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(barW).fillMaxHeight().background(hexToColor(theme.accent)))
                        Spacer(Modifier.width(gapW))
                        Text(
                            toAnnotatedString(unit.fragments, baseColor, theme),
                            fontSize = (unit.fontSize * scale).sp,
                            lineHeight = (unit.fontSize * PptLayoutEngine.style.lineMult * scale).sp,
                            fontWeight = if (unit.bold) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    Text(
                        toAnnotatedString(unit.fragments, baseColor, theme),
                        fontSize = (unit.fontSize * scale).sp,
                        lineHeight = (unit.fontSize * PptLayoutEngine.style.lineMult * scale).sp,
                        fontWeight = if (unit.bold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (unit.align == PptLayoutEngine.Align.CENTER) TextAlign.Center else TextAlign.Left,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (unit.overflow) {
            Text(
                "⚠ 内容超长",
                fontSize = 10.sp,
                color = OVERFLOW_WARN,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

/** 片段 → 带样式的 AnnotatedString（粗体/斜体/删除线/链接色）。 */
private fun toAnnotatedString(fragments: List<InlineFragment>, baseColor: Color, theme: PptTheme): AnnotatedString {
    val b = AnnotatedString.Builder()
    fragments.forEach { f ->
        val style = SpanStyle(
            color = if (f.link != null) hexToColor(theme.accent) else baseColor,
            fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (f.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (f.strike) TextDecoration.LineThrough else TextDecoration.None
        )
        b.pushStyle(style)
        b.append(f.text)
        b.pop()
    }
    return b.toAnnotatedString()
}

/** 表格列对齐 → Compose 内容对齐。 */
private fun tableAlignToContent(a: TableAlign): Alignment = when (a) {
    TableAlign.CENTER -> Alignment.Center
    TableAlign.RIGHT -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

// ────────────────────────────────────────────────
// 默认示例 Markdown
// ────────────────────────────────────────────────

private const val DEFAULT_MD = """---
title: 民事答辩状要点
---

# 民事答辩状要点

## 一、案件基本事实

- 原告主张的借款关系缺乏书面凭证
- 被告已通过转账偿还部分款项
- 关键时间节点如下文所列

## 二、法律依据

> 根据《民法典》第六百七十九条，自然人之间借款合同自贷款人提供借款时成立。

### 利息计算说明

相关利息计算规则已在计算工具中单独列示，本处不再展开。

---

## 三、答辩意见

1. 请求驳回原告不合理诉请
2. 已偿还款项应予抵扣
3. 诉讼费用由法院依法分担
"""
