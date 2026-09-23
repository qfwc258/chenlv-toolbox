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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.core.content.ContextCompat
import com.wb.mdgw.AppSettings
import com.wb.mdgw.EditPreviewBar
import com.wb.mdgw.MdEditorPane
import com.wb.mdgw.MarkdownExchange
import com.wb.mdgw.MarkdownExchangeDialog
import com.wb.mdgw.MarkdownSnippets
import com.wb.mdgw.FileUtils
import com.wb.mdgw.ExportResultDialog
import com.wb.mdgw.UI_CARD_RADIUS
import com.wb.mdgw.UI_BTN_RADIUS
import com.wb.mdgw.UI_ACTION_HEIGHT
import com.wb.mdgw.UndoHistoryStore

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
.quote { font-size: 15pt; margin-top: 18pt; }

/* 代码块字号与字体 */
.code { font-size: 13pt; font-family: "Consolas", "微软雅黑"; }

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
    // 字号（各 Tab 独立记忆）与撤销/重做栈（持久化版）
    var fontSize by remember { mutableStateOf(15) }
    val (initialMdUndo, initialMdRedo) = remember(context) {
        UndoHistoryStore.MdPptxUndoStore.load(context)
            ?: (emptyList<UndoHistoryStore.MdUndoSnapshot>() to emptyList())
    }
    var undoStack by remember { mutableStateOf(initialMdUndo) }
    var redoStack by remember { mutableStateOf(initialMdRedo) }
    LaunchedEffect(undoStack, redoStack) {
        withContext(Dispatchers.IO) {
            UndoHistoryStore.MdPptxUndoStore.save(context, undoStack, redoStack)
        }
    }
    // 跨编辑器（WORD / 公众号）互传的 Markdown
    var incomingMd by remember { mutableStateOf<MarkdownExchange.Payload?>(null) }
    // 色调（单一主色驱动整套配色）与自动分页：全局共享状态，「设置」Tab 与本页实时同步
    val tone by AppSettings.pptxTone.collectAsState()
    val autoPaginate by AppSettings.pptxAutoPaginate.collectAsState()
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

    val baseTheme = PptThemes.fromTone(tone)

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
    val slides by remember(mdTfv.text, autoPaginate, tone, defaultLayout, barHeightDenom, bandGap, cssText, waveParams, logoScale) {
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
    LaunchedEffect(mdTfv.text, barHeightDenom, bandGap, defaultLayout, logoScale, logoHAlign, logoVAlign, applyToAll, layoutsVersion) {
        delay(500)
        PptDraftStore.save(
            context,
            PptDraftStore.PptDraft(
                markdown = mdTfv.text,
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

    // ---------- 跨编辑器 Markdown 互传 ----------
    fun sendTo(target: String) {
        MarkdownExchange.send(context, MarkdownExchange.PPTX, target, mdTfv.text)
        android.widget.Toast.makeText(
            context, "已发送，打开${MarkdownExchange.sourceName(target)}即可导入",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    fun openImportExchange() {
        val p = MarkdownExchange.peek(context)
        if (p != null && p.text.isNotBlank()) incomingMd = p
        else android.widget.Toast.makeText(context, "暂无可导入的内容", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun applyIncoming(append: Boolean) {
        val p = incomingMd ?: return
        val merged = if (append) {
            val base = mdTfv.text.trimEnd()
            if (base.isEmpty() || base == DEFAULT_MD) p.text else "$base\n\n${p.text}"
        } else p.text
        mdTfv = TextFieldValue(merged, TextRange(merged.length))
        undoStack = emptyList(); redoStack = emptyList()
        MarkdownExchange.consume(context)
        incomingMd = null
        subView = SubView.EDIT
    }
    LaunchedEffect(Unit) {
        delay(600)
        MarkdownExchange.pendingFor(context, MarkdownExchange.PPTX)?.let { incomingMd = it }
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
                mdTfv = TextFieldValue(txt); undoStack = emptyList(); redoStack = emptyList()
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
                    tone = tone,
                    onTone = { AppSettings.setPptxTone(context, it) },
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
                        mdTfv = TextFieldValue(""); undoStack = emptyList(); redoStack = emptyList()
                    },
                    onSendTo = { sendTo(it) },
                    onImportExchange = { openImportExchange() }
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
                                    undoStack = (undoStack + UndoHistoryStore.MdUndoSnapshot.of(mdTfv)).takeLast(60)
                                    redoStack = emptyList()
                                }
                                mdTfv = newTfv
                            },
                            onInsert = { s ->
                                val r = MarkdownSnippets.apply(mdTfv.text, mdTfv.selection.start, mdTfv.selection.end, s)
                                mdTfv = TextFieldValue(r.text, TextRange(r.caret))
                            },
                            onUndo = {
                                if (undoStack.isNotEmpty()) {
                                    redoStack = (redoStack + UndoHistoryStore.MdUndoSnapshot.of(mdTfv)).takeLast(60)
                                    val snap = undoStack.last()
                                    undoStack = undoStack.dropLast(1)
                                    mdTfv = UndoHistoryStore.MdUndoSnapshot.toTfv(snap)
                                }
                            },
                            canUndo = undoStack.isNotEmpty(),
                            onRedo = {
                                if (redoStack.isNotEmpty()) {
                                    undoStack = (undoStack + UndoHistoryStore.MdUndoSnapshot.of(mdTfv)).takeLast(60)
                                    val snap = redoStack.last()
                                    redoStack = redoStack.dropLast(1)
                                    mdTfv = UndoHistoryStore.MdUndoSnapshot.toTfv(snap)
                                }
                            },
                            canRedo = redoStack.isNotEmpty(),
                            onClear = {
                                mdTfv = TextFieldValue(""); undoStack = emptyList(); redoStack = emptyList()
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
                    onAuto = { AppSettings.setPptxAutoPaginate(context, it) },
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
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

    // ---------- 跨编辑器导入 Markdown ----------
    incomingMd?.let { p ->
        MarkdownExchangeDialog(
            sourceName = MarkdownExchange.sourceName(p.source),
            onReplace = { applyIncoming(false) },
            onAppend = { applyIncoming(true) },
            onDismiss = { MarkdownExchange.consume(context); incomingMd = null }
        )
    }
}

// ────────────────────────────────────────────────
// 顶部控制栏（紧凑一行：主题 + 开关，药丸风格）
// ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PptxTopBar(
    tone: String,
    onTone: (String) -> Unit,
    onSettings: () -> Unit
) {
    // 直接返回 Row 作为 topBar：左侧色调选择器限定 80% 宽度（可横滑，绝不用 weight 撑满），
    // 设置按钮紧贴其右、远离最右边缘 —— 规避「weight(1f)+horizontalScroll 左侧 Row 把右侧按钮推出屏幕、
    // 绘制可见但触摸被裁」的根因（与 WordToolbar 的 Spacer(weight) 模式等价且更稳）。
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：色调选择器（最多占 80% 宽，超出横滑；不占满，保证右侧按钮有足够且完整的点击区）
        Row(
            Modifier
                .fillMaxWidth(0.8f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ColorPalette(tone) { onTone(it) }
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
private fun PptxActionBar(
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onSendTo: (String) -> Unit,
    onImportExchange: () -> Unit
) {
    var importMenuOpen by remember { mutableStateOf(false) }
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
            Box {
                OutlinedButton(
                    onClick = { importMenuOpen = true }, shape = UI_BTN_RADIUS, modifier = btnMod,
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = btnPad
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("导入/互传", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("导入 MD 文件") }, onClick = { importMenuOpen = false; onImport() })
                    DropdownMenuItem(text = { Text("发送到 WORD") }, onClick = { importMenuOpen = false; onSendTo(MarkdownExchange.WORD) })
                    DropdownMenuItem(text = { Text("发送到 公众号") }, onClick = { importMenuOpen = false; onSendTo(MarkdownExchange.WECHAT) })
                    DropdownMenuItem(text = { Text("从其他编辑器导入") }, onClick = { importMenuOpen = false; onImportExchange() })
                }
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
// 色调选择（色板）
// ────────────────────────────────────────────────

/** 主色调色板：点选即设定整套 PPTX 的主色调（章节色块/封面/竖线/表头统一生效）。 */
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
                        // 无障碍：色块没有文字标签，必须显式声明「是什么 + 是否选中」。
                        // Selected 用 set(...) 写法：此处外层有同名参数 selected: String，
                        // 直接 `selected = isSel` 会被解析成给外层参数赋值。
                        .semantics(mergeDescendants = true) {
                            contentDescription = "主题色 $c"
                            role = Role.RadioButton
                            set(SemanticsProperties.Selected, isSel)
                        }
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
            ) { Icon(Icons.Default.ChevronLeft, "上一页", modifier = Modifier.size(20.dp)) }

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
            ) { Icon(Icons.Default.ChevronRight, "下一页", modifier = Modifier.size(20.dp)) }

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
                // 文字色跟随主题：深色模式下原硬编码 Color.Black 会与深色背景融为一体
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.width(92.dp),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
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
