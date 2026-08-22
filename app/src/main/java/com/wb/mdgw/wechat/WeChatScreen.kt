package com.wb.mdgw.wechat

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.SnackbarHostState
import com.wb.mdgw.AppSettings
import com.wb.mdgw.EditPreviewBar
import com.wb.mdgw.MarkdownSnippets
import com.wb.mdgw.MdEditorPane
import com.wb.mdgw.UI_CARD_RADIUS
import com.wb.mdgw.UI_BTN_RADIUS
import com.wb.mdgw.UI_ACTION_HEIGHT
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay

/**
 * 陈律工具箱「公众号」Tab：Markdown → 微信公众号排版。
 *
 * 布局（去除原 MD2WeChat 的左右「分屏」，改为子 Tab 切换）：
 *   顶部控制栏  -> 主题下拉 + 编辑CSS + 恢复默认
 *   中部主体    -> 编辑 / 预览 两个子 Tab（无分屏）
 *   底部功能栏  -> 转换排版 / 一键复制 / 清空 / 导入MD
 *   弹窗        -> 自定义 CSS 编辑（见 CssEditDialog）
 *
 * 状态管理：使用 Composable remember（不引入 ViewModel 依赖）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeChatScreen(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val converter = remember { MdWechatConverter(context.applicationContext) }

    // 草稿：进入时静默恢复上次的正文（主题/自定义 CSS 由全局共享设置承载）
    val draft = remember { WeChatDraftStore.load(context) }

    var mdTfv by remember { mutableStateOf(TextFieldValue(draft?.markdown ?: "")) }
    // 字号（各 Tab 独立记忆）与撤销/重做栈
    var fontSize by remember { mutableStateOf(15) }
    val undoStack = remember { ArrayDeque<TextFieldValue>() }
    val redoStack = remember { ArrayDeque<TextFieldValue>() }
    // 主题与自定义 CSS：全局共享状态，「设置」Tab 与本页实时同步
    val themeKey by AppSettings.wechatTheme.collectAsState()
    val effectiveCss by AppSettings.wechatCss.collectAsState()
    var subView by remember { mutableStateOf(SubView.EDIT) }
    // 沉浸式布局：顶 / 底工具栏默认收起，仅常驻「编辑|预览」切换条
    var topExpanded by remember { mutableStateOf(false) }
    var bottomExpanded by remember { mutableStateOf(false) }
    var showCss by remember { mutableStateOf(false) }
    // 预览 WebView 引用：复制按钮依赖它执行「原生全选+复制」，与输入法复制同源
    var previewWebView by remember { mutableStateOf<WebView?>(null) }
    // WebView 是否已加载完成（onPageFinished 后才可执行原生复制，否则 DOM 不完整）
    var webViewLoaded by remember { mutableStateOf(false) }
    // 编辑态点「复制」时，先切到预览并等加载完成再复制
    var pendingCopy by remember { mutableStateOf(false) }

    // 预览用完整文档（<head><style>）；复制用纯内联片段（零 <head>/<style>/class）
    val previewHtml = remember(mdTfv.text, effectiveCss) {
        converter.convertForPreview(mdTfv.text, effectiveCss)
    }
    val copyHtml = remember(mdTfv.text, effectiveCss) {
        converter.convertForCopy(mdTfv.text, effectiveCss)
    }

    // 公众号草稿防抖自动保存：正文 / 主题 / 自定义 CSS 任一变化即落盘，下次进入静默恢复
    LaunchedEffect(mdTfv.text, themeKey, effectiveCss) {
        delay(500)
        WeChatDraftStore.save(
            context,
            WeChatDraftStore.WeChatDraft(
                markdown = mdTfv.text,
                themeKey = themeKey,
                customCss = effectiveCss
            )
        )
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readTextFromUri(context, uri)
        if (text != null) {
            mdTfv = TextFieldValue(text)
            Toast.makeText(context, "已导入文件", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
        }
    }

    // 编辑态点「复制」后切到预览，等 WebView 加载完成再执行原生复制（与输入法同源）
    LaunchedEffect(subView, webViewLoaded, pendingCopy) {
        if (pendingCopy && subView == SubView.PREVIEW && webViewLoaded && previewWebView != null) {
            pendingCopy = false
            copyViaWebView(previewWebView, copyHtml, context)
        }
    }

    Scaffold(
        topBar = {
            // 主题工具栏：可折叠，默认收起（点切换条 ⌄ 展开）
            AnimatedVisibility(
                visible = topExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    ThemeToolbar(
                        themeKey = themeKey,
                        customized = ThemeStorage.hasCustom(context, themeKey),
                        onSelectTheme = { AppSettings.setWechatTheme(context, it) },
                        onEditCss = { showCss = true },
                        onReset = {
                            AppSettings.resetWechatCss(context)
                            Toast.makeText(context, "已恢复当前主题默认样式", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        },
        bottomBar = {
            // 底部操作栏：可折叠，默认收起（点切换条 ⌃ 展开）
            AnimatedVisibility(
                visible = bottomExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ActionBar(
                    onCopy = {
                        // 预览态且已加载完成：直接走 WebView 原生「全选+复制」，与输入法复制同源；
                        // 编辑态（WebView 未挂载）：先切到预览，等加载完成后再复制。
                        if (subView == SubView.PREVIEW && webViewLoaded && previewWebView != null) {
                            copyViaWebView(previewWebView, copyHtml, context)
                        } else {
                            pendingCopy = true
                            subView = SubView.PREVIEW
                        }
                    },
                    onClear = {
                        mdTfv = TextFieldValue(""); undoStack.clear(); redoStack.clear()
                    },
                    onExample = {
                        mdTfv = TextFieldValue(DEFAULT_MD); undoStack.clear(); redoStack.clear()
                    },
                    onImport = { pickLauncher.launch("text/*") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
            Box(Modifier.fillMaxSize().weight(1f).padding(horizontal = 8.dp)) {
                // 编辑与预览互斥显示：编辑态不挂载 WebView，避免 WebView surface 浮到上层
                // 导致编辑框与预览叠在一起。复制时（见 LaunchedEffect）会先切到预览态。
                when (subView) {
                    SubView.EDIT -> MdEditorPane(
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
                        title = "公众号排版",
                        hint = "预览 / 复制将实时排版",
                        toolbarExpanded = topExpanded
                    )
                    SubView.PREVIEW -> PreviewPane(
                        previewHtml,
                        onWebViewReady = { previewWebView = it },
                        onLoaded = { webViewLoaded = it },
                        // 与编辑区（MdEditorPane 内部 4dp 内边距）对齐，保持同宽最大化
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                }
            }
        }
    }

    if (showCss) {
        CssEditDialog(
            initialCss = effectiveCss,
            onDismiss = { showCss = false },
            onSave = {
                AppSettings.setWechatCss(context, it)
                showCss = false
            }
        )
    }
}

private enum class SubView { EDIT, PREVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeToolbar(
    themeKey: String,
    customized: Boolean,
    onSelectTheme: (String) -> Unit,
    onEditCss: () -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = ThemePreset.getThemeName(themeKey)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 主题紧凑下拉：替代原带 label 的高 TextField，省出编辑区高度
        Box(Modifier.weight(1f)) {
            Surface(
                onClick = { expanded = true },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = UI_BTN_RADIUS,
                modifier = Modifier.height(36.dp).fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        currentName,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                ThemePreset.THEMES.forEach { theme ->
                    DropdownMenuItem(
                        text = { Text(theme.name) },
                        onClick = {
                            onSelectTheme(theme.key)
                            expanded = false
                        }
                    )
                }
            }
        }
        IconButton(
            onClick = onEditCss,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "编辑CSS",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(
            onClick = onReset,
            enabled = customized,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                Icons.Default.RestartAlt,
                contentDescription = "恢复默认",
                tint = if (customized) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ActionBar(
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onExample: () -> Unit,
    onImport: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
        shape = UI_CARD_RADIUS, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val btnMod = Modifier.weight(1f).height(UI_ACTION_HEIGHT)
            val btnPad = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            Button(onClick = onCopy, shape = UI_BTN_RADIUS, modifier = btnMod, contentPadding = btnPad) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("复制", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
            // 低频操作（载入示例 / 导入 MD）收进「更多」菜单，主操作更聚焦
            Box(modifier = btnMod) {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { expanded = true }, shape = UI_BTN_RADIUS,
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxSize(), contentPadding = btnPad
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("更多", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("载入示例") },
                        onClick = { expanded = false; onExample() }
                    )
                    DropdownMenuItem(
                        text = { Text("导入 MD 文件") },
                        onClick = { expanded = false; onImport() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(
    html: String,
    onWebViewReady: (WebView?) -> Unit,
    onLoaded: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    if (html.isBlank()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "预览区域（输入内容后实时渲染）",
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // 启用 JS：复制按钮需执行 document.execCommand('copy') 触发原生富文本复制，
                // 与输入法全选-复制完全同源。预览 HTML 已在 MdWechatConverter 中剥离 script/iframe 等危险标签。
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.defaultTextEncodingName = "UTF-8"
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.builtInZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // DOM 渲染完成后再视为就绪，复制才完整
                        onWebViewReady(this@apply)
                        onLoaded(true)
                    }
                }
            }
        },
        update = { webView ->
            // 重新加载前先标记未就绪，避免拿到半渲染的 DOM 进行复制
            onLoaded(false)
            webView.loadDataWithBaseURL(
                null,
                html,
                "text/html",
                "UTF-8",
                null
            )
        },
        onRelease = {
            onWebViewReady(null)
            onLoaded(false)
        },
        modifier = modifier
    )
}

/**
 * 通过预览 WebView 执行「原生全选 + 复制」，产出与输入法复制完全一致的富文本 HTML。
 * 仅在 WebView 不可用或原生复制失败时，回退到 convertForCopy 的纯内联片段。
 */
private fun copyViaWebView(webView: WebView?, fallbackHtml: String, context: Context) {
    val wv = webView
    if (wv == null) {
        CopyUtils.copyRichText(context, fallbackHtml)
        Toast.makeText(context, "公众号排版已复制，直接粘贴公众号助手", Toast.LENGTH_LONG).show()
        return
    }
    wv.requestFocus()
    val js = """
        (function(){
          try {
            var sel = window.getSelection();
            sel.removeAllRanges();
            var range = document.createRange();
            range.selectNodeContents(document.body);
            sel.addRange(range);
            var ok = document.execCommand('copy');
            sel.removeAllRanges();
            return ok ? 'true' : 'false';
          } catch(e) { return 'false'; }
        })();
    """.trimIndent()
    wv.evaluateJavascript(js) { result ->
        if (result != "true") {
            // 原生复制失败（如 WebView 失焦被拦截），回退到自包含内联片段
            CopyUtils.copyRichText(context, fallbackHtml)
        }
        Toast.makeText(
            context,
            "公众号排版已复制，直接粘贴公众号助手",
            Toast.LENGTH_LONG
        ).show()
    }
}

/** 从 Uri 读取 .md 文本 */
private fun readTextFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** 初始示例：法律普法风格，覆盖标题/引用/列表/表格/代码块，便于直观预览 */
private val DEFAULT_MD = """
    # 民法典普法小课堂

    **法律深蓝普法主题**示例：把 Markdown 一键排成公众号精美图文，粘贴零错乱。

    ## 一、今日要点

    根据《民法典》相关规定，以下情形需要特别注意：

    - 合同签订应当采用**书面形式**
    - 格式条款需尽到提示说明义务
    - 诉讼时效一般为 *三年*

    > 法律提示：遇到纠纷建议第一时间保留证据，并及时咨询专业律师。

    ## 二、常见情形对比

    | 情形 | 处理方式 | 法律后果 |
    | --- | --- | --- |
    | 口头约定 | 举证困难 | 可能不被支持 |
    | 书面合同 | 清晰可查 | 优先保护 |
    | 公证文书 | 效力最强 | 直接采信 |

    ## 三、相关法条（示例）

    ```text
    第五百零九条 当事人应当按照约定全面履行自己的义务。
    ```

    更多内容可访问 [示例官网](https://example.com) 了解。

    ---

    *本文为普法示例，不构成正式法律意见。*
""".trimIndent()
