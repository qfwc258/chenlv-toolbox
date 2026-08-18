package com.wb.mdgw

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
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
import kotlinx.coroutines.CompletableDeferred
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

/** 设置/弹窗中的分区小标题（紧凑、主色、字距收窄），与 PPTX 弹窗风格统一。 */
@Composable
private fun WSectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

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
    // 上次用于生成公文的源 Markdown；切到「预览」时若与当前不一致则自动重新生成
    var lastGenSource by remember { mutableStateOf("") }

    // 自动保存草稿（源 Markdown）
    var autoSaved by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var pendingDraft by remember { mutableStateOf<DraftStore.MdDraft?>(null) }

    // 撤销 / 重做（仅源 Markdown 正文）
    val undoStack = remember { ArrayDeque<TextFieldValue>() }
    val redoStack = remember { ArrayDeque<TextFieldValue>() }

    // ---------- 公文模型（生成后预览 / 就地编辑 / 导出） ----------
    var govDoc by remember { mutableStateOf<GovDoc?>(null) }
    var govBusy by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf("") }
    var fidelityNotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

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

    // ---------- 公文设置 ----------
    var selectedSpec by remember { mutableStateOf(SettingsStore.spec(context)) }
    var smartQuotes by remember { mutableStateOf(SettingsStore.smartQuotes(context)) }
    var pageNumber by remember { mutableStateOf(SettingsStore.pageNumber(context)) }
    var titleFont by remember { mutableStateOf(SettingsStore.titleFont(context)) }
    var showSettings by remember { mutableStateOf(false) }

    // ---------- 结果 / 导出弹窗 ----------
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var resultName by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf("") }
    var resultIsPdf by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportName by remember { mutableStateOf("") }
    var pendingKind by remember { mutableStateOf("docx") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    val charCount = tfv.text.length
    val lineCount = if (tfv.text.isEmpty()) 0 else tfv.text.lineSequence().count()

    // ============================================================
    // 业务逻辑
    // ============================================================
    fun chooseSpec(spec: GovDocSpec) {
        selectedSpec = spec
        titleFont = spec.mainTitleFont
        SettingsStore.applySpec(context, spec)
    }

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

    /**
     * 统一「打开」：根据扩展名分流。
     *  - MD / TXT：载入编辑区，可编辑（切到「编辑」子页）。
     *  - DOCX / DOC：解析为公文模型，放入预览区可编辑，同时在编辑区生成对应 Markdown。
     */
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

    LaunchedEffect(initialUri) { initialUri?.let { openFile(it) } }

    LaunchedEffect(Unit) {
        if (initialUri == null && !DraftSession.handled) {
            DraftSession.handled = true
            DraftStore.load(context)?.let { d -> pendingDraft = d; showRestore = true }
        }
    }

    // 源 Markdown 防抖自动保存
    LaunchedEffect(tfv.text) {
        if (dirty && tfv.text.isNotEmpty()) {
            delay(1500)
            DraftStore.save(context, fileName, tfv.text)
            autoSaved = true
        }
    }

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
                val updated = syncWebViewEditsSuspend() ?: govDoc
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
        val d = govDoc ?: return
        if (d.blocks.isEmpty()) { scope.launch { snackbar.showSnackbar("没有可导出的内容") }; return }
        pendingKind = kind
        exportName = FileUtils.baseName(d.title).ifBlank { "公文" }
        showExportDialog = true
    }

    /**
     * 从 WebView 收集用户编辑的文本，回写到 GovDoc.blocks（同步等待 JS 完成）。
     * 用于导出前确保所有编辑都已同步。
     *
     * 用 CompletableDeferred 而非 suspendCancellableCoroutine，避免协程库版本对
     * suspendCancellableCoroutine 签名（onCancellation 形参是否必填）的影响。
     */
    suspend fun syncWebViewEditsSuspend(): GovDoc? {
        val wv = webView ?: return null
        val d = govDoc ?: return null

        val deferred = CompletableDeferred<GovDoc?>(d)
        wv.evaluateJavascript("collectEdits()") { json ->
            // 回调到达前协程可能已取消（用户退出页面），防止重复完成 deferred
            if (deferred.isCompleted) return@evaluateJavascript
            try {
                if (json == null || json == "null" || json.isBlank()) {
                    deferred.complete(d)
                    return@evaluateJavascript
                }
                val trimmed = json.trim().removeSurrounding("\"")
                    .replace("\\\"", "\"")
                // 解析编辑 JSON 数组（直接内联，绕开所有作用域相关的解析问题）
                val edits = mutableListOf<EditEntry>()
                val objRegex = Regex("""\{[^}]+\}""")
                for (match in objRegex.findAll(trimmed)) {
                    val obj = match.value
                    val b = Regex(""""b"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val row = Regex(""""row"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val col = Regex(""""col"\s*:\s*(-?\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val t = Regex(""""t"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(obj)?.groupValues?.get(1) ?: ""
                    edits += EditEntry(b, row, col, t.replace("\\n", "\n").replace("\\t", "\t"))
                }
                if (edits.isEmpty()) {
                    deferred.complete(d)
                    return@evaluateJavascript
                }
                val blocks = d.blocks.toMutableList()
                val newEdits = mutableSetOf<EditTarget>()
                for (edit in edits) {
                    val b = blocks.getOrNull(edit.blockIndex) ?: continue
                    when {
                        edit.row < 0 && b is Block.Para -> {
                            val dist = distributeRunsRespectingFormat(b.runs, edit.text)
                            val newRuns = b.runs.mapIndexed { k, r -> r.copy(text = dist[k]) }
                            blocks[edit.blockIndex] = Block.Para(newRuns, b.props)
                            newRuns.indices.forEach { newEdits += EditTarget(edit.blockIndex, runIndex = it) }
                        }
                        edit.row >= 0 && b is Block.Table -> {
                            val cell = b.rows.getOrNull(edit.row)?.getOrNull(edit.col) ?: continue
                            val dist = distributeRunsRespectingFormat(cell, edit.text)
                            val newCell = cell.mapIndexed { k, r -> r.copy(text = dist[k]) }
                            val newRows = b.rows.mapIndexed { ri, row ->
                                if (ri != edit.row) row else row.mapIndexed { ci, c ->
                                    if (ci != edit.col) c else newCell
                                }
                            }
                            blocks[edit.blockIndex] = Block.Table(newRows)
                            newCell.indices.forEach { newEdits += EditTarget(edit.blockIndex, edit.row, edit.col, runIndex = it) }
                        }
                    }
                }
                deferred.complete(d.copy(blocks = blocks, edits = d.edits + newEdits))
            } catch (e: Exception) {
                deferred.complete(d)
            }
        }
        return deferred.await()
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

    fun doExport(kind: String, name: String? = null) {
        scope.launch {
            // 导出前同步 WebView 编辑内容到 GovDoc
            syncWebViewEditsSuspend()?.let { updatedGov ->
                commitGov(updatedGov)
                govDirty = true; govAutoSaved = false; govEditVersion++
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
                resultIsPdf = kind == "pdf"; showResult = true
                GovDocDraftStore.clear(context)
                govDirty = false; govAutoSaved = false
                snackbar.showSnackbar("已导出：$outName")
            }.onFailure { snackbar.showSnackbar("导出失败：${it.message ?: "未知错误"}") }
            govBusy = false
        }
    }
    fun openOrShare(open: Boolean) {
        val uri = resultUri ?: return
        val mime = if (resultIsPdf) FileUtils.PDF_MIME else DOCX_MIME
        runCatching { context.startActivity(if (open) FileUtils.openIntent(uri, mime) else FileUtils.shareIntent(uri, resultName, mime)) }
            .onFailure { scope.launch { snackbar.showSnackbar("操作失败：${it.message ?: "未知错误"}") } }
    }

    // ============================================================
    // 主布局：参照「公众号」Tab 的 Scaffold 骨架
    // ============================================================
    Scaffold(
        topBar = {
            WordToolbar(
                onSave = { doSave() },
                onUndo = { undo() },
                canUndo = undoStack.isNotEmpty(),
                onRedo = { redo() },
                canRedo = redoStack.isNotEmpty(),
                onSettings = { showSettings = true }
            )
        },
        bottomBar = {
            WordActionBar(
                onOpen = { openPicker.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", DOCX_MIME, "application/octet-stream", "*/*")) },
                onExportDocx = { exportDocx() },
                onExportPdf = { exportPdf() }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // 编辑 / 预览 分段切换（选中态主色填充）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = UI_SECTION_RADIUS,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(4.dp)) {
                    SubView.values().forEach { v ->
                        val label = if (v == SubView.EDIT) "编辑" else "预览"
                        val icon = if (v == SubView.EDIT) Icons.Default.Edit else Icons.Default.Visibility
                        val selected = subView == v
                        val cellMod = Modifier.weight(1f).height(40.dp)
                            .clickable { if (v == SubView.PREVIEW) switchToPreview() else subView = SubView.EDIT }
                        if (selected) {
                            Surface(color = MaterialTheme.colorScheme.primary, shape = UI_SECTION_RADIUS, modifier = cellMod) {
                                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        } else {
                            Box(cellMod, contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize().weight(1f).padding(horizontal = 12.dp)) {
                when (subView) {
                    SubView.EDIT -> EditorPane(
                        tfv = tfv,
                        fontSize = fontSize,
                        onValueChange = { newTfv ->
                            if (newTfv.text != tfv.text) {
                                undoStack.addLast(tfv)
                                if (undoStack.size > 60) undoStack.removeFirst()
                                redoStack.clear()
                            }
                            tfv = newTfv; dirty = true; autoSaved = false
                        },
                        onInsert = { insertSnippet(it) }
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
                            webView?.destroy(); webView = null
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
        onDispose { webView?.destroy(); webView = null }
    }

    // ---------- 公文设置 ----------
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("公文设置", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    WSectionLabel("排版规范")
                    GovDocSpec.ALL_PRESETS.forEach { spec ->
                        val sel = selectedSpec == spec
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(UI_SECTION_RADIUS)
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { chooseSpec(spec) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            RadioButton(selected = sel, onClick = { chooseSpec(spec) }, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(spec.specName, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                Text(
                                    when (spec.specName) {
                                        "国标通用" -> "默认标准格式"
                                        "诉讼文书" -> "适配诉讼卷宗页边距"
                                        "行政机关" -> "严格符合 GB/T 9704"
                                        else -> ""
                                    },
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    WSectionLabel("选项")
                    // 智能引号
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("智能引号", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("英文直引号自动转中文弯引号", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = smartQuotes, onCheckedChange = { smartQuotes = it; SettingsStore.saveSmartQuotes(context, it) })
                    }
                    // 添加页码
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("添加页码", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("Word 写入页脚、PDF 底部居中阿拉伯数字", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = pageNumber, onCheckedChange = { pageNumber = it; SettingsStore.savePageNumber(context, it) })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    WSectionLabel("主标题字体")
                    Text("部分手机无小标宋，可切换黑体避免异常", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(MdToGongwen.FONT_XIAOBIAO to "小标宋", MdToGongwen.FONT_HEI to "黑体").forEach { (v, label) ->
                            FilterChip(selected = titleFont == v, onClick = { titleFont = v; SettingsStore.saveTitleFont(context, v) },
                                label = { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = UI_SECTION_RADIUS, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("当前生效规范", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "A4 纸 · 页边距 上${selectedSpec.page.topCm} 下${selectedSpec.page.bottomCm} 左${selectedSpec.page.leftCm} 右${selectedSpec.page.rightCm} 厘米\n" +
                                    "正文${selectedSpec.bodyFont} ${ptToGongwenSizeName(selectedSpec.bodySizePt)} · 固定行距 ${selectedSpec.lineSpacingPt.toInt()} 磅 · 首行缩进 2 字符",
                                fontSize = 10.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成", fontWeight = FontWeight.SemiBold) } }
        )
    }

    // ---------- 导出命名 ----------
    if (showExportDialog) {
        val ext = if (pendingKind == "pdf") "pdf" else "docx"
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(if (ext == "pdf") Icons.Default.PictureAsPdf else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(if (ext == "pdf") "导出为 PDF" else "导出为 Word", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = exportName, onValueChange = { exportName = it }, label = { Text("文件名") }, singleLine = true,
                        suffix = { Text(".$ext", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                    Spacer(Modifier.height(6.dp))
                    Text("将保存到系统「下载」文件夹，可随时在结果弹窗中打开或分享", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { val k = pendingKind; showExportDialog = false; doExport(k, exportName) }) { Text("导出") } },
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 导出结果 ----------
    ExportResultDialog(
        visible = showResult && resultUri != null,
        onDismiss = { showResult = false },
        title = if (resultIsPdf) "PDF 已生成" else "Word 已生成",
        fileName = resultName,
        savePath = resultPath,
        fileIcon = if (resultIsPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
        onOpen = { openOrShare(open = true) },
        onShare = { openOrShare(open = false) }
    )

    // ---------- 保存 命名 ----------
    if (showSaveDialog) {
        val isDocxSource = govDoc != null && govDoc?.originalDocx != null
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            icon = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("保存文件", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = saveName, onValueChange = { saveName = it }, label = { Text("文件名") }, singleLine = true,
                        suffix = { Text(if (isDocxSource) ".docx" else ".md", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (isDocxSource) "当前为 Word 文档，编辑后将另存为新的 .docx（原文件无法被覆盖写回）"
                        else if (originalUri != null) "保持原名将直接写回打开的文件；改名则另存为副本"
                        else "将保存到「陈律文档」文件夹",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { performSave(saveName) }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } }
        )
    }

    // ---------- 源 Markdown 草稿恢复 ----------
    if (showRestore && pendingDraft != null) {
        val d = pendingDraft!!
        val discard = { DraftStore.clear(context); pendingDraft = null; showRestore = false }
        AlertDialog(
            onDismissRequest = discard,
            icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("发现未保存的草稿", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(d.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val firstLine = d.text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                    Text(if (firstLine.length > 60) firstLine.take(60) + "…" else firstLine.ifBlank { "（空草稿）" }, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("上次编辑内容已在本地自动保存，是否恢复？", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { tfv = TextFieldValue(d.text, TextRange(d.text.length)); fileName = d.name; originalUri = null; dirty = true; autoSaved = false; pendingDraft = null; showRestore = false }) { Text("恢复草稿") } },
            dismissButton = { TextButton(onClick = discard) { Text("丢弃") } }
        )
    }

    // ---------- 公文草稿恢复 ----------
    if (showRestoreGov && pendingGovDraft != null) {
        val g = pendingGovDraft!!
        val preview = g.blocks.firstOrNull().let { b ->
            when (b) {
                is Block.Para -> b.runs.joinToString("") { it.text }
                is Block.Table -> "表格（${b.rows.size} 行）"
                null -> ""
            }
        }.let { if (it.length > 40) it.take(40) + "…" else it }
        AlertDialog(
            onDismissRequest = { showRestoreGov = false },
            icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("恢复上次未保存的公文草稿？", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("标题：${g.title.ifBlank { "（未命名）" }}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(if (preview.isNotBlank()) preview else "（空草稿）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("检测到上次退出前自动保存的公文草稿，可一键恢复继续编辑", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { commitGov(g); govDirty = true; govAutoSaved = false; govEditVersion++; showRestoreGov = false; pendingGovDraft = null }) { Text("恢复草稿") } },
            dismissButton = { TextButton(onClick = { GovDocDraftStore.clear(context); govDirty = false; govAutoSaved = false; showRestoreGov = false; pendingGovDraft = null }) { Text("丢弃") } }
        )
    }

    // ---------- 就地编辑弹窗 ----------
    if (editing != null) {
        val t = editing!!
        val runs = runsOf(t)
        val groups = remember(t) { groupRuns(runs) }
        val hasFormat = runs.any { it.bold || it.italic || it.underline }
        var splitMode by remember(t) { mutableStateOf(false) }
        var fullText by remember(t) { mutableStateOf(runs.joinToString("") { it.text }) }
        var groupTexts by remember(t) { mutableStateOf(groups.map { it.text }) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (t.row >= 0) "编辑单元格" else "编辑文字", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (groups.size > 1) {
                        Text("按字段拆分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Switch(checked = splitMode, onCheckedChange = { splitMode = it }, modifier = Modifier.height(20.dp))
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("整段内容：${runs.joinToString("") { it.text }}", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                    }
                    if (splitMode) {
                        groups.forEachIndexed { gi, g ->
                            if (g.bold || g.italic || g.underline) {
                                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (g.underline) StyleTag("下划线")
                                    if (g.bold) StyleTag("粗体")
                                    if (g.italic) StyleTag("斜体")
                                }
                            }
                            OutlinedTextField(value = groupTexts[gi], onValueChange = { newVal -> groupTexts = groupTexts.toMutableList().also { it[gi] = newVal } },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), singleLine = false, shape = RoundedCornerShape(12.dp), textStyle = TextStyle(fontSize = 14.sp))
                        }
                    } else {
                        if (hasFormat) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (runs.any { it.underline }) StyleTag("下划线")
                                if (runs.any { it.bold }) StyleTag("粗体")
                                if (runs.any { it.italic }) StyleTag("斜体")
                            }
                            Text("带格式的字段会保持原样（下划线不丢失）；如需单独改某个字段的长度，打开右上角「按字段拆分」。", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        OutlinedTextField(value = fullText, onValueChange = { fullText = it }, modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 3, shape = RoundedCornerShape(12.dp), textStyle = TextStyle(fontSize = 14.sp))
                    }
                }
            },
            confirmButton = { Button(onClick = { if (splitMode) applyEditGroups(groupTexts) else applyEditSingle(fullText) }, shape = UI_BTN_RADIUS) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
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
    canRedo: Boolean,
    onSettings: () -> Unit
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
        FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Default.Tune, contentDescription = "设置", modifier = Modifier.size(19.dp))
        }
    }
}

// ============================================================
// 底部操作栏（悬浮卡片：打开 / 导出 DOCX / 转 PDF 三按钮，等宽）
// ============================================================
@Composable
private fun WordActionBar(
    onOpen: () -> Unit,
    onExportDocx: () -> Unit,
    onExportPdf: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
        shape = UI_CARD_RADIUS, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val btnMod = Modifier.weight(1f).height(UI_ACTION_HEIGHT)
            val btnPad = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
            OutlinedButton(
                onClick = onOpen, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("打开", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Button(onClick = onExportDocx, shape = UI_BTN_RADIUS, modifier = btnMod, contentPadding = btnPad) {
                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("导出DOCX", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onExportPdf, shape = UI_BTN_RADIUS, modifier = btnMod,
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline),
                contentPadding = btnPad
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("转PDF", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ============================================================
// 编辑子页：Markdown 源 + 片段工具栏 + 字号
// ============================================================
@Composable
private fun EditorPane(
    tfv: TextFieldValue,
    fontSize: Int,
    onValueChange: (TextFieldValue) -> Unit,
    onInsert: (MarkdownSnippets.Snippet) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
        // 顶部轻量信息条
        val charCount = tfv.text.length
        val lineCount = if (tfv.text.isEmpty()) 0 else tfv.text.lineSequence().count()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Markdown 源", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp)) {
                Text("$charCount 字 · $lineCount 行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        // 编辑卡片
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            shape = UI_CARD_RADIUS,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxSize()
        ) {
            BasicTextField(
                value = tfv,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize + 6).sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = false,
                maxLines = Int.MAX_VALUE,
                decorationBox = { inner ->
                    if (tfv.text.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = CircleShape, modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.EditNote, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("在此输入 Markdown 内容", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("或点上方「打开」载入文件 · 切到「预览」即自动生成公文", fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MarkdownSnippets.HINT_SNIPPETS.forEach { s ->
                                    Surface(onClick = { onInsert(s) }, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text(s.label, fontSize = 11.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                    }
                                }
                            }
                        }
                    }
                    inner()
                }
            )
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
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCloseDoc, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (searchOpen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleSearch) { Icon(Icons.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                        OutlinedTextField(value = query, onValueChange = onQueryChange, placeholder = { Text("搜索正文 / 表格…", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 14.sp),
                            trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) } } } else null)
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
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
                // A4 纸面模拟：灰底白页 + 阴影，页面宽度拉满屏幕，高度按 A4 真实比例 210:297 约束
                // padding=0 让 WebView 真占满屏幕宽度，避免内容被裁切
                GovDocPaper(doc = doc, onStartEdit = onStartEdit, onWebViewReady = onWebViewReady)
            }
        }
    }
}

/**
 * A4 纸面模拟：用 WebView 渲染 HTML，实现与 WPS / Word 打印效果一致的预览。
 * 页面宽度拉满手机屏幕，高度按 A4 真实比例 210:297 约束。所有格式（字体、字号、颜色、粗斜体、
 * 下划线、删除线、高亮、表格列宽/边框）由 CSS 原生表达，无需逐个建模。
 *
 * 用户可直接在页面上编辑文字（contenteditable），导出前通过 [syncWebViewEdits] 收集。
 * 渲染失败时降级到 Compose PaperPreview，保证用户始终能预览。
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
        runCatching { doc.originalDocx?.let { DocxHtml.toHtml(it, doc.page) } ?: DocxHtml.govDocToHtml(doc) }
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
                    "可直接在页面上点按修改文字；改完后点顶部「保存」将另存为新的 Word 文档。",
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
                    // 固定 A4 真实比例 210:297（不按内容高度自适应），让任意屏幕下页面比例都是 A4
                    setInitialScale(100)
                    setBackgroundColor(0xFFE8E8E8.toInt())
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

@Composable
private fun StyleTag(label: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
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
