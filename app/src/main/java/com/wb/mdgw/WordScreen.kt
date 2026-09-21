package com.wb.mdgw

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// 顶部声明：WebView 编辑收集项 + 解析函数
// 放在文件最前（在所有调用方之前），彻底解决 Unresolved reference。
// 之前放在文件末尾 / Composable 内部 / 局部函数都被 Kotlin 编译器某些
// 路径下误判为不可见。
// ============================================================
data class EditEntry(val blockIndex: Int, val row: Int, val col: Int, val text: String)

// 设计 token 复用 com.wb.mdgw.UiTokens 的公共令牌（UI_SECTION_RADIUS / UI_CARD_RADIUS /
// UI_BTN_RADIUS / UI_ACTION_HEIGHT），避免在各 tab 屏幕内重复定义（参见第8项 de-god）。

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/**
 * 整合后的「WORD」Tab：Markdown 编辑 → 一键转公文 → 预览 / 就地编辑 → 导出 Word · PDF。
 *
 * UI 参照「公众号」Tab 的骨架：
 *   顶部控制栏  -> 公文规范下拉 + 保存 + 撤销/重做 + 设置
 *   中部主体    -> 编辑 / 预览 两个子 Tab（无分屏，互斥显示）
 *   底部操作栏  -> 生成公文 / 存 Word / 转 PDF / 导入
 *   弹窗        -> 公文设置 / 导出结果 / 导出命名 / 草稿恢复 / 就地编辑
 *
 * 状态全部收敛到本屏：源 Markdown 与公文模型都在本地管理，无需跨页共享。
 */
private enum class SubView { EDIT, PREVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordScreen(
    snackbar: SnackbarHostState,
    initialUri: Uri? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- 源 Markdown（TextFieldValue 承载正文与光标） ----------
    var fileName by remember { mutableStateOf("未命名.md") }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var originalUri by remember { mutableStateOf<Uri?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(15) }
    var busy by remember { mutableStateOf(false) }
    var subView by remember { mutableStateOf(SubView.EDIT) }
    // 沉浸式布局：顶部工具面板 / 底部操作面板默认收起，仅常驻「编辑|预览」切换条
    var topExpanded by remember { mutableStateOf(false) }
    var bottomExpanded by remember { mutableStateOf(false) }
    // 上次用于生成公文的源 Markdown；切到「预览」时若与当前不一致则自动重新生成
    var lastGenSource by remember { mutableStateOf("") }

    // 自动保存草稿（源 Markdown）
    var autoSaved by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<DraftStore.MdDraft?>(null) }

    // 撤销 / 重做（仅源 Markdown 正文）
    val undoStack = remember { ArrayDeque<TextFieldValue>() }
    val redoStack = remember { ArrayDeque<TextFieldValue>() }

    // 跨编辑器（PPTX / 公众号）互传的 Markdown
    var incomingMd by remember { mutableStateOf<MarkdownExchange.Payload?>(null) }

    // ---------- 公文模型（生成后预览 / 就地编辑 / 导出） ----------
    var govDoc by remember { mutableStateOf<GovDoc?>(null) }
    var govBusy by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf("") }
    var fidelityNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var findReplaceOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    // 公文撤销 / 重做（内存快照，覆盖生成 / 编辑 / 打开 / 关闭）
    var govUndoStack by remember { mutableStateOf<List<GovDoc?>>(emptyList()) }
    var govRedoStack by remember { mutableStateOf<List<GovDoc?>>(emptyList()) }
    val GOV_MAX_HISTORY = 40
    var govDirty by remember { mutableStateOf(false) }
    var govAutoSaved by remember { mutableStateOf(false) }
    var govEditVersion by remember { mutableStateOf(0) }
    var showRestoreGov by remember { mutableStateOf(false) }
    var pendingGovDraft by remember { mutableStateOf<GovDoc?>(null) }

    // WebView 引用，用于导出前收集编辑内容
    var webView by remember { mutableStateOf<WebView?>(null) }

    // ---------- 公文设置（来自全局「设置」Tab，单一数据源，改动即时同步） ----------
    val selectedSpec by AppSettings.wordSpec.collectAsState()
    val smartQuotes by AppSettings.smartQuotes.collectAsState()
    val pageNumber by AppSettings.pageNumber.collectAsState()
    val titleFont by AppSettings.titleFont.collectAsState()

    // ---------- 结果 / 导出弹窗 ----------
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var resultName by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf("") }
    var resultIsPdf by remember { mutableStateOf(false) }
    var resultExt by remember { mutableStateOf("docx") }
    var showResult by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("") }
    var pendingKind by remember { mutableStateOf("docx") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var exportSuffix by remember { mutableStateOf("txt") }

    // ---------- 模板管理 ----------
    var showTemplates by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf<List<WordTemplate>>(emptyList()) }
    var showTemplateEdit by remember { mutableStateOf(false) }
    var templateEdit by remember { mutableStateOf<WordTemplate?>(null) }
    var templateName by remember { mutableStateOf("") }
    var templateExt by remember { mutableStateOf("md") }
    var templateContent by remember { mutableStateOf("") }

    val charCount = tfv.text.length
    val lineCount = if (tfv.text.isEmpty()) 0 else tfv.text.lineSequence().count()

    // ============================================================
    // 业务逻辑
    // ============================================================
    fun commitGov(next: GovDoc?) {
        if (next == govDoc) return
        govUndoStack = (govUndoStack + govDoc).takeLast(GOV_MAX_HISTORY)
        govRedoStack = emptyList()
        govDoc = next
    }
    fun govUndo() {
        if (govUndoStack.isEmpty()) return
        val prev = govUndoStack.last()
        govRedoStack = (govRedoStack + govDoc).takeLast(GOV_MAX_HISTORY)
        govUndoStack = govUndoStack.dropLast(1)
        govDoc = prev
    }
    fun govRedo() {
        if (govRedoStack.isEmpty()) return
        val next = govRedoStack.last()
        govUndoStack = (govUndoStack + govDoc).takeLast(GOV_MAX_HISTORY)
        govRedoStack = govRedoStack.dropLast(1)
        govDoc = next
    }

    fun insertSnippet(s: MarkdownSnippets.Snippet) {
        if (busy || govBusy) return
        undoStack.addLast(tfv)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        val r = MarkdownSnippets.apply(tfv.text, tfv.selection.start, tfv.selection.end, s)
        tfv = TextFieldValue(r.text, TextRange(r.caret))
        dirty = true
        autoSaved = false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(tfv)
        tfv = undoStack.removeLast()
        dirty = true
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(tfv)
        tfv = redoStack.removeLast()
        dirty = true
    }

    // ---------- 跨编辑器 Markdown 互传 ----------
    fun sendTo(target: String) {
        MarkdownExchange.send(context, MarkdownExchange.WORD, target, tfv.text)
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
            val base = tfv.text.trimEnd()
            if (base.isEmpty()) p.text else "$base\n\n${p.text}"
        } else p.text
        tfv = TextFieldValue(merged, TextRange(merged.length))
        undoStack.clear(); redoStack.clear(); dirty = true; autoSaved = false
        MarkdownExchange.consume(context)
        incomingMd = null
        subView = SubView.EDIT
    }

    // ---------- 打开文件（.docx / .md / .txt） ----------
    fun openFile(uri: Uri) {
        scope.launch {
            busy = true
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.IO) {
                    val name = FileUtils.displayName(context, uri)
                    val lower = name.lowercase()
                    if (lower.endsWith(".docx") || lower.endsWith(".doc")) {
                        name to DocxReader.read(FileUtils.readBytes(context, uri), selectedSpec)
                    } else {
                        name to FileUtils.readText(context, uri)
                    }
                }
            }.onSuccess { (name, payload) ->
                if (payload is GovDoc) {
                    val md = payload.toMarkdown()
                    commitGov(payload)
                    fidelityNotes = payload.originalDocx?.let { DocxFidelity.scan(it) } ?: emptyList()
                    fileName = name; sourceName = name
                    tfv = TextFieldValue(md); undoStack.clear(); redoStack.clear(); lastGenSource = md
                    originalUri = uri; dirty = false; autoSaved = false
                    govDirty = false; govAutoSaved = false
                    DraftStore.clear(context); GovDocDraftStore.clear(context); resultUri = null
                    subView = SubView.PREVIEW
                    val extra = if (fidelityNotes.isNotEmpty()) "（含特殊内容，已原样保留）" else ""
                    snackbar.showSnackbar("已打开：$name$extra，已生成对应 Markdown")
                } else {
                    val content = payload as String
                    fileName = name
                    tfv = TextFieldValue(content); undoStack.clear(); redoStack.clear()
                    originalUri = uri; dirty = false; autoSaved = false
                    DraftStore.clear(context); resultUri = null; govDoc = null
                    subView = SubView.EDIT
                    snackbar.showSnackbar("已打开：$name")
                }
            }.onFailure {
                snackbar.showSnackbar("打开失败：${it.message ?: "未知错误"}")
            }
            busy = false
        }
    }

    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { openFile(it) }
    }

    // 应用入口（文件管理器分享/打开）传入的 initialUri 首次自动载入
    LaunchedEffect(initialUri) { initialUri?.let { openFile(it) } }

    fun doSave() {
        if (tfv.text.isEmpty() && originalUri == null && govDoc == null) {
            scope.launch { snackbar.showSnackbar("没有可保存的内容") }
            return
        }
        // Word 文档源：默认以 .docx 另存（原文件仅读权限，无法覆盖写回）
        val base = FileUtils.baseName(fileName).ifBlank { "未命名" }
        saveName = if (govDoc?.originalDocx != null) "$base.docx" else base
        showSaveDialog = true
    }

    fun performSave(name: String) {
        // 区分来源：Word 文档源以 .docx 另存（先将 WebView 就地编辑同步进公文模型再生成）；
        // Markdown 源保持原 .md 逻辑（可写回原文件或另存）。
        val isDocxSource = govDoc?.originalDocx != null
        val ext = if (isDocxSource) "docx" else "md"
        val clean = name.trim().ifBlank { "未命名" }
        val outName = if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
        scope.launch {
            busy = true
            if (isDocxSource) {
                // 同步预览区 WebView 的就地编辑 → 生成新 docx 字节 → 另存（原文件只读，无法写回）
                val updated = govDoc
                val bytes = updated?.toDocx()
                if (bytes == null) {
                    busy = false; showSaveDialog = false
                    snackbar.showSnackbar("没有可导出的内容")
                    return@launch
                }
                val sf = FileUtils.saveToDownloads(context, outName, bytes, DOCX_MIME)
                resultUri = sf.uri; resultName = outName; resultPath = sf.displayPath; resultIsPdf = false
                fileName = outName; dirty = false; autoSaved = false
                GovDocDraftStore.clear(context)
                busy = false; showSaveDialog = false
                snackbar.showSnackbar("已另存为 Word 文档：$outName")
            } else {
                var wroteBack = false
                if (originalUri != null && outName.equals(fileName, ignoreCase = true)) {
                    wroteBack = FileUtils.writeTextToUri(context, originalUri!!, tfv.text)
                }
                if (wroteBack) {
                    resultUri = originalUri; resultName = outName; resultPath = "已写回原文件"
                } else {
                    val sf = FileUtils.saveTextAsFile(context, outName, tfv.text)
                    resultUri = sf.uri; resultName = outName; resultPath = sf.displayPath
                }
                fileName = outName; dirty = false; autoSaved = false
                DraftStore.clear(context)
                busy = false; showSaveDialog = false
                snackbar.showSnackbar(if (wroteBack) "已保存回原文件：$outName" else "已保存到 陈律文档/$outName")
            }
        }
    }

    fun doConvert() {
        scope.launch {
            if (tfv.text.isBlank()) { snackbar.showSnackbar("没有可转换的内容"); return@launch }
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val gov = MdToGongwen.convert(
                        tfv.text,
                        MdToGongwen.Options(spec = selectedSpec, mainTitleFont = titleFont, smartQuotes = smartQuotes, pageNumber = pageNumber)
                    )
                    gov to (FileUtils.baseName(fileName).ifBlank { "公文" })
                }
            }.onSuccess { (gov, base) ->
                commitGov(gov)
                govDirty = false; govAutoSaved = false
                GovDocDraftStore.clear(context)
                busy = false
                // 无缝：生成后切到「预览」子页，直接看公文效果
                subView = SubView.PREVIEW
                snackbar.showSnackbar("已生成公文：${gov.title.ifBlank { base }}，规范：${selectedSpec.specName}")
            }.onFailure {
                busy = false
                snackbar.showSnackbar("转换失败：${it.message ?: "未知错误"}")
            }
        }
    }

    // ---------- 公文：就地编辑 / 检索（打开由统一 openFile 处理） ----------
    LaunchedEffect(Unit) {
        if (initialUri == null && govDoc == null && !GovDocDraftSession.handled) {
            GovDocDraftSession.handled = true
            GovDocDraftStore.load(context)?.let { g -> pendingGovDraft = g; showRestoreGov = true }
        }
    }
    // 进入时检测来自其他编辑器的 Markdown（延迟以错开草稿恢复弹窗）
    LaunchedEffect(Unit) {
        delay(600)
        MarkdownExchange.pendingFor(context, MarkdownExchange.WORD)?.let { incomingMd = it }
    }
    // 公文防抖自动保存
    LaunchedEffect(govEditVersion) {
        if (govEditVersion == 0 || !govDirty) return@LaunchedEffect
        delay(1500)
        govDoc?.let { GovDocDraftStore.save(context, it) }
        govAutoSaved = true
    }

    fun runsOf(t: EditTarget): List<TextRun> {
        val d = govDoc ?: return emptyList()
        val b = d.blocks.getOrNull(t.blockIndex) ?: return emptyList()
        return when {
            b is Block.Para -> b.runs
            b is Block.Table && t.row >= 0 -> (b.rows.getOrNull(t.row)?.getOrNull(t.col) ?: emptyList())
            else -> emptyList()
        }
    }
    fun startEdit(t: EditTarget) { editing = t }
    fun applyEditSingle(fullText: String) {
        val d = govDoc ?: return
        val t = editing ?: return
        val blocks = d.blocks.toMutableList()
        val newEdits = mutableSetOf<EditTarget>()
        when (val b = blocks.getOrNull(t.blockIndex)) {
            is Block.Para -> {
                val dist = distributeRunsRespectingFormat(b.runs, fullText)
                val newRuns = b.runs.mapIndexed { k, r -> r.copy(text = dist[k]) }
                blocks[t.blockIndex] = Block.Para(newRuns, b.props)
                newRuns.indices.forEach { newEdits += EditTarget(t.blockIndex, runIndex = it) }
            }
            is Block.Table -> {
                if (t.row < 0) return
                val cell = b.rows.getOrNull(t.row)?.getOrNull(t.col) ?: return
                val dist = distributeRunsRespectingFormat(cell, fullText)
                val newCell = cell.mapIndexed { k, r -> r.copy(text = dist[k]) }
                val newRows = b.rows.mapIndexed { ri, row -> if (ri != t.row) row else row.mapIndexed { ci, c -> if (ci != t.col) c else newCell } }
                blocks[t.blockIndex] = Block.Table(newRows)
                newCell.indices.forEach { newEdits += EditTarget(t.blockIndex, t.row, t.col, runIndex = it) }
            }
            null -> return
        }
        commitGov(d.copy(blocks = blocks, edits = d.edits + newEdits))
        govDirty = true; govAutoSaved = false; govEditVersion++; editing = null
    }
    fun applyEditGroups(groupTexts: List<String>) {
        val d = govDoc ?: return
        val t = editing ?: return
        val runs = runsOf(t)
        val groups = groupRuns(runs)
        if (groupTexts.size != groups.size) return
        val newRunTexts = MutableList(runs.size) { "" }
        for ((gi, g) in groups.withIndex()) {
            val lens = g.runIndices.map { runs[it].text.length }
            val counts = proportionalSplit(lens, groupTexts[gi].length)
            var cursor = 0
            for ((j, idx) in g.runIndices.withIndex()) {
                val end = (cursor + counts[j]).coerceAtMost(groupTexts[gi].length)
                newRunTexts[idx] = groupTexts[gi].substring(cursor, end)
                cursor = end
            }
            if (cursor < groupTexts[gi].length) {
                val lastIdx = g.runIndices.last()
                newRunTexts[lastIdx] = newRunTexts[lastIdx] + groupTexts[gi].substring(cursor)
            }
        }
        val blocks = d.blocks.toMutableList()
        val newEdits = mutableSetOf<EditTarget>()
        when (val b = blocks.getOrNull(t.blockIndex)) {
            is Block.Para -> {
                val newRuns = b.runs.mapIndexed { k, r -> r.copy(text = newRunTexts[k]) }
                blocks[t.blockIndex] = Block.Para(newRuns, b.props)
                newRuns.indices.forEach { newEdits += EditTarget(t.blockIndex, runIndex = it) }
            }
            is Block.Table -> {
                if (t.row < 0) return
                val cell = b.rows.getOrNull(t.row)?.getOrNull(t.col) ?: return
                val newCell = cell.mapIndexed { k, r -> r.copy(text = newRunTexts[k]) }
                val newRows = b.rows.mapIndexed { ri, row -> if (ri != t.row) row else row.mapIndexed { ci, c -> if (ci != t.col) c else newCell } }
                blocks[t.blockIndex] = Block.Table(newRows)
                newCell.indices.forEach { newEdits += EditTarget(t.blockIndex, t.row, t.col, runIndex = it) }
            }
            null -> return
        }
        commitGov(d.copy(blocks = blocks, edits = d.edits + newEdits))
        govDirty = true; govAutoSaved = false; govEditVersion++; editing = null
    }

    fun startExport(kind: String) {
        // 文本类导出（md / txt / 自定义后缀）：内容来自源 Markdown，无需先生成公文
        if (kind == "md" || kind == "txt" || kind == "custom") {
            if (tfv.text.isBlank()) { scope.launch { snackbar.showSnackbar("没有可导出的内容，请先输入 Markdown") }; return }
            pendingKind = kind
            exportName = FileUtils.baseName(fileName).ifBlank { "文档" }
            showExportDialog = true
            return
        }
        val d = govDoc ?: return
        if (d.blocks.isEmpty()) { scope.launch { snackbar.showSnackbar("没有可导出的内容") }; return }
        pendingKind = kind
        exportName = FileUtils.baseName(d.title).ifBlank { "公文" }
        showExportDialog = true
    }

    /** 全局查找替换：在每个 run 内做文本替换（不跨 run），返回替换次数。 */
    fun findReplace(find: String, replace: String): Int {
        val d = govDoc ?: return 0
        if (find.isEmpty()) return 0
        var count = 0
        fun repl(runs: List<TextRun>): List<TextRun> = runs.map { r ->
            val replaced = r.text.replace(find, replace)
            if (replaced != r.text) { count++; r.copy(text = replaced) } else r
        }
        val newBlocks = d.blocks.map { b ->
            when (b) {
                is Block.Para -> Block.Para(repl(b.runs), b.props)
                is Block.Table -> Block.Table(b.rows.map { row -> row.map { repl(it) } })
            }
        }
        if (count > 0) {
            commitGov(d.copy(blocks = newBlocks, formatTouched = true))
            govDirty = true; govAutoSaved = false; govEditVersion++
        }
        return count
    }

    /**
     * 「预览」即自动生成公文：切到预览子页时，若源 Markdown 非空且与上次生成不一致，
     * 则后台把当前 Markdown 转为公文模型并刷新预览；已是最新则直接显示（保留就地编辑）。
     */
    fun switchToPreview() {
        subView = SubView.PREVIEW
        // 打开的 Word 文档：以原文件为唯一真源，就地编辑即可 100% 保留原字体 / 下划线 / 表格。
        // 禁止用编辑区 Markdown 重建公文——那会丢掉原文档的字体与样式（导出改走 DocxWriter 重建）。
        if (govDoc?.originalDocx != null) return
        if (tfv.text.isBlank()) return
        if (govDoc != null && tfv.text == lastGenSource) return  // 已是最新，无需重生成
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    MdToGongwen.convert(
                        tfv.text,
                        MdToGongwen.Options(spec = selectedSpec, mainTitleFont = titleFont, smartQuotes = smartQuotes, pageNumber = pageNumber)
                    )
                }
            }.onSuccess { gov ->
                commitGov(gov)
                govDirty = false; govAutoSaved = false
                GovDocDraftStore.clear(context)
                lastGenSource = tfv.text
                busy = false
            }.onFailure {
                busy = false
                snackbar.showSnackbar("生成失败：${it.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 「转PDF」：优先用已生成的公文；若预览区还没有公文，则先从当前 Markdown 生成再导出 PDF，
     * 保证按钮始终可用（不再灰显）。
     */
    /**
     * 「导出DOCX」：优先用已生成的公文；若预览区还没有公文，则先从当前 Markdown 生成再导出 DOCX，
     * 保证按钮始终可用（不再灰显），与「转PDF」行为一致。
     */
    fun exportDocx() {
        if (tfv.text.isBlank()) {
            scope.launch { snackbar.showSnackbar("没有可导出的内容，请先输入 Markdown 或生成公文") }
            return
        }
        if (govDoc != null) { startExport("docx"); return }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    MdToGongwen.convert(
                        tfv.text,
                        MdToGongwen.Options(spec = selectedSpec, mainTitleFont = titleFont, smartQuotes = smartQuotes, pageNumber = pageNumber)
                    )
                }
            }.onSuccess { gov ->
                commitGov(gov)
                govDirty = false; govAutoSaved = false
                GovDocDraftStore.clear(context)
                busy = false
                subView = SubView.PREVIEW
                startExport("docx")
            }.onFailure {
                busy = false
                snackbar.showSnackbar("生成失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun exportPdf() {
        if (tfv.text.isBlank()) {
            scope.launch { snackbar.showSnackbar("没有可导出的内容，请先输入 Markdown 或生成公文") }
            return
        }
        if (govDoc != null) { startExport("pdf"); return }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    MdToGongwen.convert(
                        tfv.text,
                        MdToGongwen.Options(spec = selectedSpec, mainTitleFont = titleFont, smartQuotes = smartQuotes, pageNumber = pageNumber)
                    )
                }
            }.onSuccess { gov ->
                commitGov(gov)
                govDirty = false; govAutoSaved = false
                GovDocDraftStore.clear(context)
                busy = false
                subView = SubView.PREVIEW
                startExport("pdf")
            }.onFailure {
                busy = false
                snackbar.showSnackbar("生成失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun doExport(kind: String, name: String? = null, customExt: String? = null) {
        scope.launch {
            // 文本类导出（md / txt / 自定义后缀）：源 Markdown 原样写入，mime 由 saveTextAsFile 自动判断
            if (kind == "md" || kind == "txt" || kind == "custom") {
                if (tfv.text.isBlank()) { snackbar.showSnackbar("没有可导出的内容"); return@launch }
                govBusy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val ext = when (kind) {
                            "md" -> "md"
                            "txt" -> "txt"
                            else -> customExt?.trim()?.trimStart('.')?.ifBlank { "txt" } ?: "txt"
                        }
                        val clean = (name?.trim()?.ifBlank { null } ?: FileUtils.baseName(fileName).ifBlank { "文档" })
                        val outName = if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
                        val sf = FileUtils.saveTextAsFile(context, outName, tfv.text)
                        Triple(sf, outName, ext)
                    }
                }.onSuccess { (sf, outName, ext) ->
                    resultUri = sf.uri; resultName = outName; resultPath = sf.displayPath
                    resultIsPdf = false; resultExt = ext; showResult = true
                    snackbar.showSnackbar("已导出：$outName")
                }.onFailure { snackbar.showSnackbar("导出失败：${it.message ?: "未知错误"}") }
                govBusy = false
                return@launch
            }
            val d = govDoc
            if (d == null) { snackbar.showSnackbar("没有可导出的内容"); return@launch }
            if (d.blocks.isEmpty()) { snackbar.showSnackbar("没有可导出的内容"); return@launch }
            govBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val ext = if (kind == "pdf") "pdf" else "docx"
                    val clean = (name?.trim()?.ifBlank { null } ?: FileUtils.baseName(d.title).ifBlank { "公文" })
                    val outName = if (clean.endsWith(".$ext", ignoreCase = true)) clean else "$clean.$ext"
                    if (kind == "pdf") Triple(d.toPdf(), outName, FileUtils.PDF_MIME)
                    else Triple(d.toDocx(), outName, DOCX_MIME)
                }
            }.onSuccess { (bytes, outName, mime) ->
                val sf = FileUtils.saveToDownloads(context, outName, bytes, mime)
                resultUri = sf.uri; resultName = outName; resultPath = sf.displayPath
                resultIsPdf = kind == "pdf"; resultExt = if (kind == "pdf") "pdf" else "docx"; showResult = true
                GovDocDraftStore.clear(context)
                govDirty = false; govAutoSaved = false
                snackbar.showSnackbar("已导出：$outName")
            }.onFailure { snackbar.showSnackbar("导出失败：${it.message ?: "未知错误"}") }
            govBusy = false
        }
    }
    fun openOrShare(open: Boolean) {
        val uri = resultUri ?: return
        val mime = when {
            resultIsPdf -> FileUtils.PDF_MIME
            resultExt == "docx" || resultExt == "doc" -> DOCX_MIME
            resultExt == "md" -> "text/markdown"
            else -> "text/plain"
        }
        runCatching { context.startActivity(if (open) FileUtils.openIntent(uri, mime) else FileUtils.shareIntent(uri, resultName, mime)) }
            .onFailure { scope.launch { snackbar.showSnackbar("操作失败：${it.message ?: "未知错误"}") } }
    }

    // ---------- 模板管理 ----------
    fun openTemplates() {
        templates = WordTemplateStore.load(context)
        showTemplates = true
    }

    /** 把模板内容插入到当前光标处（复用格式片段插入算法） */
    fun applyTemplate(t: WordTemplate) {
        if (busy || govBusy) return
        insertSnippet(MarkdownSnippets.Snippet(t.id, t.name, t.content, t.content.length))
    }

    fun newBlankTemplate() {
        templateEdit = null
        templateName = "新模板"
        templateExt = "md"
        templateContent = ""
        showTemplates = false
        showTemplateEdit = true
    }

    /** 把当前编辑区内容存为模板 */
    fun saveCurrentAsTemplate() {
        if (tfv.text.isBlank()) { scope.launch { snackbar.showSnackbar("当前没有内容可存为模板") }; return }
        templateEdit = null
        templateName = FileUtils.baseName(fileName).ifBlank { "模板" }
        templateExt = if (fileName.lowercase().endsWith(".txt")) "txt" else "md"
        templateContent = tfv.text
        showTemplates = false
        showTemplateEdit = true
    }

    fun editTemplate(t: WordTemplate) {
        templateEdit = t
        templateName = t.name
        templateExt = t.ext
        templateContent = t.content
        showTemplates = false
        showTemplateEdit = true
    }

    fun deleteTemplate(t: WordTemplate) {
        templates = templates.filterNot { it.id == t.id }
        WordTemplateStore.save(context, templates)
    }

    fun saveTemplate() {
        val name = templateName.trim().ifBlank { "未命名模板" }
        val list = templates.toMutableList()
        val edit = templateEdit
        if (edit != null) {
            val idx = list.indexOfFirst { it.id == edit.id }
            if (idx >= 0) list[idx] = WordTemplate(edit.id, name, templateExt, templateContent, System.currentTimeMillis())
        } else {
            list.add(WordTemplate(java.util.UUID.randomUUID().toString(), name, templateExt, templateContent, System.currentTimeMillis()))
        }
        templates = list
        WordTemplateStore.save(context, list)
        showTemplateEdit = false
        showTemplates = true
    }

    // ============================================================
    // 主布局：参照「公众号」Tab 的 Scaffold 骨架
    // ============================================================
    Scaffold(
        topBar = {
            AnimatedVisibility(
                visible = topExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                WordToolbar(
                    onSave = { doSave() },
                    onUndo = { undo() },
                    canUndo = undoStack.isNotEmpty(),
                    onRedo = { redo() },
                    canRedo = redoStack.isNotEmpty()
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = bottomExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                WordActionBar(
                    onOpen = { openPicker.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", DOCX_MIME, "application/octet-stream", "*/*")) },
                    onExportDocx = { exportDocx() },
                    onExportPdf = { exportPdf() },
                    onExportText = { startExport(it) },
                    onSendTo = { sendTo(it) },
                    onImportExchange = { openImportExchange() }
                )
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // 常驻切换条：折叠顶栏开关 + 编辑/预览 + 折叠底栏开关（唯一常驻控件）
            EditPreviewBar(
                selectedIndex = subView.ordinal,
                onSelect = { i ->
                    if (i == SubView.PREVIEW.ordinal) switchToPreview() else subView = SubView.EDIT
                },
                topExpanded = topExpanded,
                onToggleTop = { topExpanded = !topExpanded },
                bottomExpanded = bottomExpanded,
                onToggleBottom = { bottomExpanded = !bottomExpanded },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Box(Modifier.fillMaxSize().weight(1f).padding(horizontal = 8.dp)) {
                when (subView) {
                    SubView.EDIT -> MdEditorPane(
                        tfv = tfv,
                        fontSize = fontSize,
                        onFontSizeChange = { fontSize = it },
                        onChange = { newTfv ->
                            if (newTfv.text != tfv.text) {
                                undoStack.addLast(tfv)
                                if (undoStack.size > 60) undoStack.removeFirst()
                                redoStack.clear()
                            }
                            tfv = newTfv; dirty = true; autoSaved = false
                        },
                        onInsert = { insertSnippet(it) },
                        onUndo = { undo() },
                        canUndo = undoStack.isNotEmpty(),
                        onRedo = { redo() },
                        canRedo = redoStack.isNotEmpty(),
                        onClear = {
                            tfv = TextFieldValue(""); undoStack.clear(); redoStack.clear()
                            dirty = true; autoSaved = false
                        },
                        onTemplates = { openTemplates() },
                        title = "Markdown 源",
                        hint = "切到「预览」即自动生成公文",
                        toolbarExpanded = topExpanded
                    )
                    SubView.PREVIEW -> PaperPreview(
                        doc = govDoc,
                        busy = busy,
                        searchOpen = searchOpen,
                        query = query,
                        onQueryChange = { query = it },
                        onToggleSearch = {
                            if (searchOpen) { searchOpen = false; query = "" } else searchOpen = true
                        },
                        onCloseDoc = {
                            // 关闭文档时立即销毁旧 WebView，释放 HTML/CSS 解析占用的内存
                            safeDestroyWebView(webView); webView = null
                            commitGov(null); resultUri = null; fidelityNotes = emptyList(); lastGenSource = ""
                        },
                        fidelityNotes = fidelityNotes,
                        onDismissFidelity = { fidelityNotes = emptyList() },
                        onStartEdit = { startEdit(it) },
                        onWebViewReady = { webView = it }
                    )
                }
            }
        }
    }

    // ---------- WebView 生命周期：Composable 销毁时释放，避免 Activity 泄漏 ----------
    DisposableEffect(Unit) {
        onDispose { safeDestroyWebView(webView); webView = null }
    }

    // ---------- 导出命名 ----------
    if (showExportDialog) {
        ExportNameDialog(
            kind = pendingKind,
            name = exportName,
            suffix = exportSuffix,
            onNameChange = { exportName = it },
            onSuffixChange = { exportSuffix = it },
            onConfirm = { k, n, c -> showExportDialog = false; doExport(k, n, c) },
            onDismiss = { showExportDialog = false }
        )
    }

    // ---------- 导出结果 ----------
    ExportResultDialog(
        visible = showResult && resultUri != null,
        onDismiss = { showResult = false },
        title = when {
            resultIsPdf -> "PDF 已生成"
            resultExt == "docx" || resultExt == "doc" -> "Word 已生成"
            resultExt == "md" -> "Markdown 已导出"
            resultExt == "txt" -> "纯文本已导出"
            else -> "文件已导出"
        },
        fileName = resultName,
        savePath = resultPath,
        fileIcon = when {
            resultIsPdf -> Icons.Default.PictureAsPdf
            resultExt == "docx" || resultExt == "doc" -> Icons.Default.Description
            else -> Icons.Default.Article
        },
        onOpen = { openOrShare(open = true) },
        onShare = { openOrShare(open = false) }
    )

    // ---------- 模板管理（列表） ----------
    if (showTemplates) {
        TemplateListDialog(
            templates = templates,
            onApply = { applyTemplate(it) },
            onEdit = { editTemplate(it) },
            onDelete = { deleteTemplate(it) },
            onSaveCurrent = { saveCurrentAsTemplate() },
            onNewBlank = { newBlankTemplate() },
            onDismiss = { showTemplates = false }
        )
    }

    // ---------- 模板管理（新建 / 编辑） ----------
    if (showTemplateEdit) {
        TemplateEditDialog(
            editing = templateEdit,
            name = templateName,
            content = templateContent,
            ext = templateExt,
            onNameChange = { templateName = it },
            onContentChange = { templateContent = it },
            onExtChange = { templateExt = it },
            onSave = { saveTemplate() },
            onDismiss = { showTemplateEdit = false; showTemplates = true }
        )
    }

    // ---------- 保存 命名 ----------
    if (showSaveDialog) {
        SaveNameDialog(
            name = saveName,
            isDocxSource = govDoc != null && govDoc?.originalDocx != null,
            hasOriginalUri = originalUri != null,
            onNameChange = { saveName = it },
            onConfirm = { performSave(it) },
            onDismiss = { showSaveDialog = false }
        )
    }

    // ---------- 源 Markdown 草稿恢复 ----------
    if (showRestore && pendingDraft != null) {
        val d = pendingDraft!!
        MarkdownDraftRestoreDialog(
            draft = d,
            onRestore = {
                tfv = TextFieldValue(it.text, TextRange(it.text.length))
                fileName = it.name
                originalUri = null
                dirty = true
                autoSaved = false
                pendingDraft = null
                showRestore = false
            },
            onDiscard = { DraftStore.clear(context); pendingDraft = null; showRestore = false }
        )
    }

    // ---------- 公文草稿恢复 ----------
    if (showRestoreGov && pendingGovDraft != null) {
        GovDraftRestoreDialog(
            draft = pendingGovDraft!!,
            onRestore = {
                commitGov(it)
                govDirty = true
                govAutoSaved = false
                govEditVersion++
                showRestoreGov = false
                pendingGovDraft = null
            },
            onDiscard = {
                GovDocDraftStore.clear(context)
                govDirty = false
                govAutoSaved = false
                showRestoreGov = false
                pendingGovDraft = null
            }
        )
    }

    // ---------- 就地编辑弹窗 ----------
    if (editing != null) {
        InPlaceEditDialog(
            target = editing!!,
            runs = runsOf(editing!!),
            onApplySingle = { applyEditSingle(it) },
            onApplyGroups = { applyEditGroups(it) },
            onOpenFindReplace = { editing = null; findReplaceOpen = true },
            onDismiss = { editing = null }
        )
    }

    // ---------- 全局查找替换 ----------
    if (findReplaceOpen) {
        FindReplaceDialog(
            findText = findText,
            replaceText = replaceText,
            onFindChange = { findText = it },
            onReplaceChange = { replaceText = it },
            onReplaceAll = { f, r ->
                val n = findReplace(f, r)
                findReplaceOpen = false
                scope.launch { snackbar.showSnackbar(if (n > 0) "已替换 $n 处" else "未找到：$f") }
            },
            onDismiss = { findReplaceOpen = false }
        )
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

// ============================================================
// 顶部控制栏（参照公众号：紧凑一行，左标题 + 右操作图标）
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordToolbar(
    onSave: () -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
    onRedo: () -> Unit,
    canRedo: Boolean
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("WORD", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Text("Markdown 一键转公文", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onClick = onSave, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Save, contentDescription = "保存", modifier = Modifier.size(19.dp))
        }
        FilledTonalIconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Undo, contentDescription = "撤销", modifier = Modifier.size(19.dp))
        }
        FilledTonalIconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Redo, contentDescription = "重做", modifier = Modifier.size(19.dp))
        }
    }
}

// ============================================================
// 底部操作栏（悬浮卡片：打开 / 导出 DOCX / 转 PDF / 导出文本 四按钮，等宽）
// ============================================================
@Composable
private fun WordActionBar(
    onOpen: () -> Unit,
    onExportDocx: () -> Unit,
    onExportPdf: () -> Unit,
    onExportText: (String) -> Unit,
    onSendTo: (String) -> Unit,
    onImportExchange: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
        shape = UI_CARD_RADIUS, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val btnMod = Modifier.weight(1f).height(UI_ACTION_HEIGHT)
            val btnPad = PaddingValues(horizontal = 3.dp, vertical = 0.dp)
            OutlinedButton(
                onClick = onOpen, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("打开", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Button(onClick = onExportDocx, shape = UI_BTN_RADIUS, modifier = btnMod, contentPadding = btnPad) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("导出DOCX", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onExportPdf, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("转PDF", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
            Box {
                OutlinedButton(
                    onClick = { menuOpen = true }, shape = UI_BTN_RADIUS, modifier = btnMod,
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = btnPad
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("导出", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("导出 Markdown (.md)") }, onClick = { menuOpen = false; onExportText("md") })
                    DropdownMenuItem(text = { Text("导出 纯文本 (.txt)") }, onClick = { menuOpen = false; onExportText("txt") })
                    DropdownMenuItem(text = { Text("自定义后缀导出…") }, onClick = { menuOpen = false; onExportText("custom") })
                    DropdownMenuItem(text = { Text("发送到 PPTX") }, onClick = { menuOpen = false; onSendTo(MarkdownExchange.PPTX) })
                    DropdownMenuItem(text = { Text("发送到 公众号") }, onClick = { menuOpen = false; onSendTo(MarkdownExchange.WECHAT) })
                    DropdownMenuItem(text = { Text("从其他编辑器导入") }, onClick = { menuOpen = false; onImportExchange() })
                }
            }
        }
    }
}

// ============================================================
// 预览子页：公文纸面渲染（点字即改）+ 检索 + 空态
// ============================================================
@Composable
private fun PaperPreview(
    doc: GovDoc?,
    busy: Boolean = false,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onCloseDoc: () -> Unit,
    fidelityNotes: List<String>,
    onDismissFidelity: () -> Unit,
    onStartEdit: (EditTarget) -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    if (doc == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("正在生成公文…", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(76.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Article, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp)) }
                }
                Spacer(Modifier.height(18.dp))
                Text("公文预览", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                Text("在「编辑」页写好 Markdown，\n切到「预览」即自动生成公文并在此排版、点字直接改；\n也可点底部「打开」载入 .md 或 .docx 文件。", fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 信息行
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(32.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Article, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(doc.title.ifBlank { "公文" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${doc.blocks.size} 个段落 · 点文字可直接修改", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onToggleSearch) {
                        Icon(Icons.Default.Search, "检索正文", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCloseDoc, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, "关闭文档", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (searchOpen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleSearch) { Icon(Icons.Default.ArrowBack, "退出检索", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                        OutlinedTextField(value = query, onValueChange = onQueryChange, placeholder = { Text("搜索正文 / 表格…", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 14.sp),
                            trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "清空检索词", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) } } } else null)
                    }
                }
            }
        }

        // 保真度提示
        if (fidelityNotes.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("本文含 ${fidelityNotes.joinToString("、")}，已按原样保留、暂不可直接编辑", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissFidelity, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, "关闭提示", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 纸面 / 检索结果
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.TopCenter) {
            val searching = searchOpen && query.isNotBlank()
            val hits = GovDocSearch.search(doc, query)
            if (searching) {
                if (hits.isNotEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        Text("找到 ${hits.size} 处匹配「$query」", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(hits) { h ->
                                val label = if (h.row >= 0) "第 ${h.blockIndex + 1} 块 · 第 ${h.row + 1} 行 ${h.col + 1} 列" else "第 ${h.blockIndex + 1} 段"
                                Surface(
                                    onClick = { onStartEdit(EditTarget(h.blockIndex, h.row, h.col)); onToggleSearch() },
                                    color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 2.dp,
                                    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(4.dp))
                                        Text(h.preview, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未找到「$query」", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // A4 纸面模拟：灰底白页 + 阴影，页面按文档页宽等比缩放适配屏宽并水平居中
                GovDocPaper(doc = doc, onStartEdit = onStartEdit, onWebViewReady = onWebViewReady)
            }
        }
    }
}

/**
 * A4 纸面模拟：用 WebView 渲染 HTML，实现与 WPS / Word 打印效果一致的预览。
 * 纸页按文档真实物理尺寸渲染，加载后 JS 把整页等比缩放到屏宽——字号与纸页
 * 同比例缩放，显示即真实打印比例（WYSIWYG）。字号 / 行距等格式（字体、字号、
 * 颜色、粗斜体、下划线、删除线、高亮、上下标、表格列宽/边框）由 CSS 原生表达。
 *
 * 编辑不依赖 contenteditable：段落 / 表格单元格带 data-block 钩子，点击经 JS 桥
 * （editBridge.startEdit）回调触发结构化编辑弹窗，编辑结果写回 GovDoc 模型。
 * 渲染失败时降级到 Compose SimpleTextFallback，保证用户始终能预览。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GovDocPaper(
    doc: GovDoc,
    onStartEdit: (EditTarget) -> Unit,
    onWebViewReady: (WebView) -> Unit
) {
    // 加载进度与失败状态：让用户看到「正在渲染」反馈
    var loadProgress by remember { mutableStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    // 预生成 HTML：失败时不进入 WebView 而走降级
    // 关键：Word 打开场景下以 originalDocx 为 key，避免导出后 commitGov 触发 recomposition
    // 把已编辑的 WebView 重新加载回原始内容（覆盖用户刚改的字）。Markdown 场景下以 doc
    // 自身为 key，模型变更时正常刷新。
    val htmlKey = remember(doc) { doc.originalDocx ?: doc }
    val htmlResult = remember(htmlKey) {
        runCatching { DocxHtml.govDocToHtml(doc) }
    }
    if (loadFailed || htmlResult.isFailure) {
        // 渲染失败：降级到简化文本预览，避免递归调用 PaperPreview
        SimpleTextFallback(doc = doc, onStartEdit = onStartEdit)
        return
    }
    val html = htmlResult.getOrNull() ?: return
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFE8E8E8))) {
        // Word 文档源：提示可直接在页面内编辑，避免被误认为只读
        if (doc.originalDocx != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "点击段落或表格即可编辑文字；改完后点顶部「保存」将另存为新的 Word 文档。",
                    fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        // 加载进度条（顶部细线），加载完成后自动消失
        if (loadProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    // 允许混合内容（如有）
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loadProgress = 100
                        }
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            loadFailed = true
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loadProgress = newProgress
                        }
                    }
                    isVerticalScrollBarEnabled = true
                    // 不设 initialScale：HTML 内按真实页宽布局并 JS 整体 zoom 到视口宽，
                    // 还原真实打印比例；双指缩放仍可用（maximum-scale=4.0）。
                    setBackgroundColor(0xFFE8E8E8.toInt())
                    addJavascriptInterface(object : Any() {
                        @JavascriptInterface
                        fun startEdit(block: Int, row: Int, col: Int) {
                            val t = if (row < 0) EditTarget(block, runIndex = -1) else EditTarget(block, row, col)
                            onStartEdit(t)
                        }
                    }, "editBridge")
                    onWebViewReady(this)
                }
            },
            update = { wv ->
                if (html != wv.tag as? String) {
                    wv.tag = html
                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

/**
 * 安全释放 WebView：先将其从父视图摘除再 destroy，避免仍附着在视图层级时调用
 * destroy() 抛出的 IllegalStateException（"Cannot destroy WebView while still attached"）导致闪退。
 */
private fun safeDestroyWebView(wv: WebView?) {
    if (wv == null) return
    try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Throwable) {}
    try { wv.stopLoading() } catch (_: Throwable) {}
    try { wv.destroy() } catch (_: Throwable) {}
}

/**
 * 简化文本降级预览：WebView 渲染失败或 HTML 解析异常时使用。
 * 把每个段落 / 表格转成可读的纯文本（保留基础格式如粗斜下划线），
 * 用户仍可点段落进入就地编辑弹窗，导出功能不受影响。
 */
@Composable
private fun SimpleTextFallback(
    doc: GovDoc,
    onStartEdit: (EditTarget) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFE8E8E8))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                "WebView 渲染失败，已切换到简化文本预览（可点段落编辑）",
                fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
        doc.blocks.forEachIndexed { idx, b ->
            when (b) {
                is Block.Para -> {
                    val fullText = b.runs.joinToString("") { it.text }
                    Surface(
                        onClick = { onStartEdit(EditTarget(idx, runIndex = -1)) },
                        color = Color.White, shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Text(
                            fullText.ifBlank { "（空段落）" },
                            fontSize = 14.sp, lineHeight = 22.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                is Block.Table -> {
                    Surface(
                        color = Color.White, shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text("表格（${b.rows.size} 行）", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            b.rows.forEachIndexed { ri, row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    row.forEachIndexed { ci, cell ->
                                        val text = cell.joinToString("") { it.text }
                                        Surface(
                                            onClick = { onStartEdit(EditTarget(idx, ri, ci)) },
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.weight(1f).padding(horizontal = 1.dp)
                                        ) {
                                            Text(
                                                text.ifBlank { "·" },
                                                fontSize = 12.sp,
                                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
