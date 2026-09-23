package com.wb.mdgw.tableform

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.FileUtils
import com.wb.mdgw.RecentFile
import com.wb.mdgw.RecentFilesSection
import com.wb.mdgw.RecentFilesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

private enum class TfPhase { EMPTY, PICK, FILL }

/** 旧版二进制 .doc（Word 97-2003），本应用只支持 .docx */
private class LegacyDocException(val uri: Uri, val name: String) : Exception("legacy .doc")
private class NoTableException : Exception("no table")

/**
 * 通用表格填报：导入任意含表格的 .docx，自动识别表头 / 空白格 / 合并，
 * 在手机端生成卡片式 / 清单式 / 网格式填报 UI，原位回填导出（格式保留）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableFormScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var phase by remember { mutableStateOf(TfPhase.EMPTY) }
    var busy by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("") }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var origBytes by remember { mutableStateOf<ByteArray?>(null) }
    var parsed by remember { mutableStateOf<TfDoc?>(null) }
    var tableIndex by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(TfMode.CARD) }
    var headerRow by remember { mutableStateOf(0) }
    var cardRecords by remember { mutableStateOf(listOf<Map<Int, String>>()) }
    var cellValues by remember { mutableStateOf(mapOf<Pair<Int, Int>, String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FileUtils.SavedFile?>(null) }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var resultName by remember { mutableStateOf("") }
    var legacy by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var recent by remember {
        mutableStateOf(RecentFilesStore.list(context, RecentFile.KIND_TABLEFORM))
    }

    fun refreshRecent() {
        recent = RecentFilesStore.list(context, RecentFile.KIND_TABLEFORM)
    }

    fun curTable(): TfTable? = parsed?.tables?.getOrNull(tableIndex)

    // CARD 字段列：数据列 domCell -> 表头标签
    fun cardFields(t: TfTable): List<Pair<Int, String>> {
        val tmpl = t.rows.getOrNull(headerRow + 1) ?: return emptyList()
        val labels = t.headerLabels(headerRow)
        return tmpl.cells
            .filter { !it.vMergeCont && it.logicalCol != t.seqLogicalCol }
            .map { c ->
                val raw = labels[c.logicalCol]?.replace(" ", "")?.replace("\n", "")?.trim().orEmpty()
                c.domCell to (raw.ifBlank { "第${c.logicalCol + 1}列" })
            }
    }

    fun initFromTable(t: TfTable, restore: TableFormDraft?) {
        headerRow = t.headerRow
        mode = t.mode
        if (restore != null && restore.tableIndex == tableIndex) {
            runCatching { TfMode.valueOf(restore.mode) }.onSuccess { mode = it }
            headerRow = restore.headerRow
            cardRecords = restore.cardRecords.map { m ->
                m.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
            }.ifEmpty {
                if (mode == TfMode.CARD) listOf(emptyMap()) else emptyList()
            }
            cellValues = restore.cellValues.mapNotNull { (k, v) ->
                val a = k.split("_")
                if (a.size == 2) {
                    val r = a[0].toIntOrNull(); val c = a[1].toIntOrNull()
                    if (r != null && c != null) (r to c) to v else null
                } else null
            }.toMap()
        } else {
            cardRecords = if (t.mode == TfMode.CARD) {
                t.dataRows.map { row ->
                    row.cells
                        .filter { !it.vMergeCont && it.logicalCol != t.seqLogicalCol && it.text.isNotEmpty() }
                        .associate { it.domCell to it.text }
                }.ifEmpty { listOf(emptyMap()) }
            } else emptyList()
            cellValues = emptyMap()
        }
    }

    /** 从 SAF uri 或「最近打开」载入：持久化权限 → 旧版 .doc 拦截 → 解析 → 进填报 */
    fun loadFrom(uri: Uri) {
        scope.launch {
            busy = true
            var name = ""
            runCatching {
                withContext(Dispatchers.IO) {
                    name = FileUtils.displayName(context, uri)
                    val bytes = FileUtils.readBytes(context, uri)
                    if (FileUtils.isLegacyDoc(bytes)) throw LegacyDocException(uri, name)
                    val doc = TableFormParser.parse(bytes)
                    if (doc.tables.isEmpty()) throw NoTableException()
                    bytes to doc
                }
            }.onSuccess { (bytes, doc) ->
                FileUtils.persistRead(context, uri)
                currentUri = uri
                fileName = name
                origBytes = bytes
                parsed = doc
                RecentFilesStore.touch(
                    context, RecentFile.KIND_TABLEFORM, uri, name, "${doc.tables.size} 个表格"
                )
                refreshRecent()
                val draft = TableFormDraftStore.loadFor(context, uri.toString(), name)
                if (doc.tables.size == 1) {
                    tableIndex = 0
                    initFromTable(doc.tables[0], draft)
                    phase = TfPhase.FILL
                } else {
                    val savedIdx = draft?.tableIndex?.takeIf { it in doc.tables.indices }
                    if (savedIdx != null) {
                        tableIndex = savedIdx
                        initFromTable(doc.tables[savedIdx], draft)
                        phase = TfPhase.FILL
                    } else {
                        phase = TfPhase.PICK
                    }
                }
            }.onFailure { e ->
                when (e) {
                    is LegacyDocException -> { legacy = e.uri to e.name }
                    is NoTableException -> snackbar.showSnackbar("未在该文档中找到表格")
                    else -> {
                        val dead = e is SecurityException || e is FileNotFoundException
                        if (dead) {
                            RecentFilesStore.remove(context, RecentFile.KIND_TABLEFORM, uri.toString())
                            refreshRecent()
                            snackbar.showSnackbar("文件已移动、删除或授权失效，请重新选择")
                        } else {
                            snackbar.showSnackbar("打开失败：${e.message ?: "文件无法解析"}")
                        }
                    }
                }
            }
            busy = false
        }
    }

    fun persistDraft() {
        val d = TableFormDraft(
            uri = currentUri?.toString().orEmpty(),
            fileName = fileName,
            tableIndex = tableIndex,
            mode = mode.name,
            headerRow = headerRow,
            cardRecords = cardRecords.map { m -> m.mapKeys { it.key.toString() } },
            cellValues = cellValues.mapKeys { "${it.key.first}_${it.key.second}" }
        )
        TableFormDraftStore.save(context, d)
    }

    fun clearInputs() {
        val t = curTable() ?: return
        cardRecords = if (mode == TfMode.CARD)
            (t.dataRows.map { row ->
                row.cells.filter { !it.vMergeCont && it.logicalCol != t.seqLogicalCol && it.text.isNotEmpty() }
                    .associate { it.domCell to it.text }
            }).ifEmpty { listOf(emptyMap()) }
        else emptyList()
        cellValues = emptyMap()
    }

    fun generate() {
        val bytes = origBytes ?: return
        val doc = parsed ?: return
        val t = curTable() ?: return
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val fill = when (mode) {
                        TfMode.CARD -> TableFill(
                            tableIndex = tableIndex,
                            card = CardSpec(
                                headerRow = headerRow,
                                seqDomCell = t.seqLogicalCol,
                                records = cardRecords.map { CardRecord(it) }
                            )
                        )
                        else -> TableFill(
                            tableIndex = tableIndex,
                            writes = cellValues.filter { it.value.isNotBlank() }
                                .map { CellWrite(it.key.first, it.key.second, it.value.trim()) },
                            headerRow = headerRow
                        )
                    }
                    TableFormFiller.fill(bytes, doc, listOf(fill))
                }
            }.onSuccess { out ->
                val outName = "${FileUtils.baseName(fileName)}_已填.docx"
                val saved = FileUtils.saveToDownloads(context, outName, out, FileUtils.DOCX_MIME)
                val uri = FileUtils.writeCache(context, outName, out)
                persistDraft()
                result = saved; shareUri = uri; resultName = outName
                snackbar.showSnackbar("已保存：${saved.displayPath}")
            }.onFailure {
                snackbar.showSnackbar("生成失败：${it.message ?: "未知错误"}")
            }
            busy = false
        }
    }

    // 输入防抖自动保存草稿
    LaunchedEffect(fileName, tableIndex, mode, headerRow, cardRecords, cellValues, phase) {
        if (phase == TfPhase.FILL && fileName.isNotBlank()) {
            delay(500)
            withContext(Dispatchers.IO) { persistDraft() }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadFrom(it) }
    }

    Scaffold(
        containerColor = com.wb.mdgw.BrandTokens.BrandPaper,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "通用表格填报",
                                style = com.wb.mdgw.BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp),
                                maxLines = 1
                            )
                            if (phase == TfPhase.FILL && fileName.isNotBlank())
                                Text(fileName, fontSize = 11.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (phase == TfPhase.FILL) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("重新导入") },
                                        leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                        onClick = { menuOpen = false; picker.launch(arrayOf(FileUtils.DOCX_MIME, "application/octet-stream", "*/*")) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("清空已填") },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                        onClick = { menuOpen = false; confirmClear = true }
                                    )
                                }
                            }
                        }
                    },
                    colors = com.wb.mdgw.BrandTokens.BrandTopAppBarColors
                )
                HorizontalDivider(
                    color = com.wb.mdgw.BrandTokens.BrandBronze.copy(alpha = 0.35f),
                    thickness = 1.dp
                )
            }
        },
        bottomBar = {
            if (phase == TfPhase.FILL) {
                Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
                    Button(
                        onClick = { generate() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 48.dp)
                    ) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        else {
                            Icon(Icons.Default.Downloading, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("生成 Word（保留原格式）")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (phase) {
                TfPhase.EMPTY -> EmptyState(
                    busy = busy,
                    recent = recent,
                    onImport = { picker.launch(arrayOf(FileUtils.DOCX_MIME, "application/octet-stream", "*/*")) },
                    onOpenRecent = { rf -> runCatching { loadFrom(Uri.parse(rf.uri)) } },
                    onRemoveRecent = { rf ->
                        RecentFilesStore.remove(context, RecentFile.KIND_TABLEFORM, rf.uri)
                        TableFormDraftStore.remove(context, rf.uri)
                        refreshRecent()
                    }
                )
                TfPhase.PICK -> {
                    val doc = parsed
                    if (doc != null) {
                        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Text("检测到 ${doc.tables.size} 个表格，请选择要填报的表格：",
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            itemsIndexed(doc.tables) { i, t ->
                                val modeCn = when (t.mode) {
                                    TfMode.CARD -> "卡片式"
                                    TfMode.LIST -> "清单式"
                                    TfMode.GRID -> "网格式"
                                }
                                ElevatedCard(
                                    onClick = {
                                        tableIndex = i
                                        val u = currentUri
                                        val d = if (u != null)
                                            TableFormDraftStore.loadFor(context, u.toString(), fileName) else null
                                        initFromTable(t, d)
                                        phase = TfPhase.FILL
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.GridView, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("表格 ${i + 1}", fontWeight = FontWeight.SemiBold)
                                            Text("${t.rows.size} 行 × ${t.gridCols} 列 · 建议$modeCn" +
                                                if (t.hasVerticalMerge) "（含合并，仅填空）" else "",
                                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.ChevronRight, null)
                                    }
                                }
                            }
                        }
                    }
                }
                TfPhase.FILL -> {
                    val t = curTable()
                    if (t != null) FillArea(
                        t = t,
                        mode = mode,
                        headerRow = headerRow,
                        cardRecords = cardRecords,
                        cellValues = cellValues,
                        fields = cardFields(t),
                        onMode = { mode = it },
                        onHeader = {
                            headerRow = it
                            // 表头行变化后，按新表头重新初始化卡片（避免错位）
                            if (mode == TfMode.CARD) {
                                cardRecords = t.dataRowsLet(it)
                            }
                        },
                        onCardValue = { idx, dc, v ->
                            cardRecords = cardRecords.toMutableList().also { l ->
                                l[idx] = l[idx] + (dc to v)
                            }
                        },
                        onAddCard = { cardRecords = cardRecords + emptyMap() },
                        onCopyCard = { idx ->
                            cardRecords = cardRecords.toMutableList().also {
                                it.add(idx + 1, HashMap(cardRecords[idx]))
                            }
                        },
                        onDeleteCard = { idx ->
                            cardRecords = cardRecords.toMutableList().also { it.removeAt(idx) }
                        },
                        onCellValue = { r, c, v -> cellValues = cellValues + ((r to c) to v) }
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空已填内容？") },
            text = { Text("将清空本次填写的所有内容，恢复为文档原始值，不影响原文件。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; clearInputs() }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    // 生成结果
    val sf = result
    val su = shareUri
    if (sf != null && su != null) {
        val shareU: Uri = su
        AlertDialog(
            onDismissRequest = { result = null; shareUri = null },
            title = { Text("已生成") },
            text = { Text("文件：$resultName\n保存位置：${sf.displayPath}") },
            confirmButton = {
                TextButton(onClick = {
                    result = null; shareUri = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_SEND).apply {
                        type = FileUtils.DOCX_MIME; putExtra(Intent.EXTRA_STREAM, shareU)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }) }.onFailure { Toast.makeText(context, "未找到可分享应用", Toast.LENGTH_SHORT).show() }
                }) { Text("分享") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val u: Uri = shareU
                    result = null; shareUri = null
                    runCatching { context.startActivity(FileUtils.openIntent(u, FileUtils.DOCX_MIME)) }
                        .onFailure { Toast.makeText(context, "未找到可打开应用", Toast.LENGTH_SHORT).show() }
                }) { Text("打开") }
            }
        )
    }

    // 旧版 .doc 引导：用 WPS/Word 打开后另存为 .docx
    val lg = legacy
    if (lg != null) {
        AlertDialog(
            onDismissRequest = { legacy = null },
            title = { Text("旧版 .doc 格式") },
            text = {
                Text("该文件是旧版 Word 97-2003（.doc）格式，本应用仅支持 .docx。\n\n请用 WPS/Word 打开后「另存为 .docx」，再回到表格填报导入。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val (u, n) = lg
                    legacy = null
                    FileUtils.openExternally(context, u, n)
                }) { Text("用 WPS 打开") }
            },
            dismissButton = {
                TextButton(onClick = { legacy = null }) { Text("知道了") }
            }
        )
    }
}

/** 用新表头行重新初始化卡片数据（保留原文件值） */
private fun TfTable.dataRowsLet(newHeader: Int): List<Map<Int, String>> {
    val mapped: List<Map<Int, String>> = rows.drop(newHeader + 1).map { row ->
        row.cells
            .filter { !it.vMergeCont && it.logicalCol != seqLogicalCol && it.text.isNotEmpty() }
            .associate { it.domCell to it.text }
    }
    return if (mapped.isEmpty()) listOf<Map<Int, String>>(emptyMap()) else mapped
}

@Composable
private fun EmptyState(
    busy: Boolean,
    recent: List<RecentFile>,
    onImport: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(36.dp))
        Icon(Icons.Default.GridView, null, modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Text("导入含表格的 Word（.docx）", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("自动识别表头、空白格与合并单元格，\n生成手机填报界面，导出时保留原表格格式。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            ) else {
                Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择 Word 文档")
            }
        }
        Spacer(Modifier.height(20.dp))
        RecentFilesSection(
            items = recent,
            onOpen = onOpenRecent,
            onRemove = onRemoveRecent,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillArea(
    t: TfTable,
    mode: TfMode,
    headerRow: Int,
    cardRecords: List<Map<Int, String>>,
    cellValues: Map<Pair<Int, Int>, String>,
    fields: List<Pair<Int, String>>,
    onMode: (TfMode) -> Unit,
    onHeader: (Int) -> Unit,
    onCardValue: (Int, Int, String) -> Unit,
    onAddCard: () -> Unit,
    onCopyCard: (Int) -> Unit,
    onDeleteCard: (Int) -> Unit,
    onCellValue: (Int, Int, String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 模式切换 + 表头行
        Surface(tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip("卡片式", mode == TfMode.CARD) { onMode(TfMode.CARD) }
                    Spacer(Modifier.width(8.dp))
                    ModeChip("清单式", mode == TfMode.LIST) { onMode(TfMode.LIST) }
                    Spacer(Modifier.width(8.dp))
                    ModeChip("网格式", mode == TfMode.GRID) { onMode(TfMode.GRID) }
                }
                if (mode != TfMode.LIST && headerRow >= 0 && t.rows.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("表头行：第 ${headerRow + 1} 行", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { if (headerRow > 0) onHeader(headerRow - 1) },
                            enabled = headerRow > 0,
                            modifier = Modifier.size(34.dp)
                        ) { Icon(Icons.Default.Remove, "上移", modifier = Modifier.size(18.dp)) }
                        IconButton(
                            onClick = { if (headerRow < t.rows.size - 2) onHeader(headerRow + 1) },
                            enabled = headerRow < t.rows.size - 2,
                            modifier = Modifier.size(34.dp)
                        ) { Icon(Icons.Default.Add, "下移", modifier = Modifier.size(18.dp)) }
                    }
                }
                if (mode == TfMode.CARD && t.hasVerticalMerge) {
                    Text("该表含纵向合并单元格，仅支持填空，不支持加行。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 内容
        when (mode) {
            TfMode.CARD -> LazyColumn(
                Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(cardRecords) { idx, rec ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${idx + 1} 条", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                    modifier = Modifier.weight(1f))
                                if (t.seqLogicalCol != null)
                                    Text("序号自动", fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { onCopyCard(idx) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(17.dp))
                                }
                                IconButton(onClick = { onDeleteCard(idx) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Default.DeleteOutline, "删除", modifier = Modifier.size(17.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            fields.forEach { (dc, label) ->
                                val v = rec[dc].orEmpty()
                                val long = label.contains("目的") || label.contains("内容") ||
                                    label.contains("地址") || label.contains("事实") || label.contains("理由")
                                OutlinedTextField(
                                    value = v,
                                    onValueChange = { onCardValue(idx, dc, it) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        .heightIn(min = if (long) 76.dp else 48.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 19.sp),
                                    shape = RoundedCornerShape(10.dp),
                                    minLines = if (long) 2 else 1
                                )
                            }
                        }
                    }
                }
                if (!t.hasVerticalMerge) {
                    item {
                        OutlinedButton(
                            onClick = onAddCard,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                        ) {
                            Icon(Icons.Default.PlaylistAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加一条（自动加行）")
                        }
                    }
                }
            }
            TfMode.LIST -> LazyColumn(
                Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val dataRows = t.rows.drop(headerRow + 1)
                itemsIndexed(dataRows) { i, row ->
                    val domRow = headerRow + 1 + i
                    val blanks = row.cells.filter { it.blank && !it.vMergeCont }
                    if (blanks.isEmpty()) return@itemsIndexed
                    val label = row.cells
                        .filter { !it.blank && !it.vMergeCont }
                        .joinToString(" ") { it.text.trim() }
                        .replace(Regex("\\s+"), " ")
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            blanks.forEach { bc ->
                                val key = domRow to bc.domCell
                                TfInput(
                                    value = cellValues[key].orEmpty(),
                                    onValue = { onCellValue(domRow, bc.domCell, it) },
                                    label = if (blanks.size > 1) "第${bc.logicalCol + 1}列" else "填写"
                                )
                            }
                        }
                    }
                }
            }
            TfMode.GRID -> LazyColumn(
                Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val dataRows = t.rows.drop(headerRow + 1)
                itemsIndexed(dataRows) { i, row ->
                    val domRow = headerRow + 1 + i
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.cells.filter { !it.vMergeCont }.forEach { c ->
                            val w = (if (c.colSpan > 1) 200 else 120).dp
                            if (c.blank) {
                                OutlinedTextField(
                                    value = cellValues[domRow to c.domCell].orEmpty(),
                                    onValueChange = { onCellValue(domRow, c.domCell, it) },
                                    modifier = Modifier.width(w).heightIn(min = 48.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true
                                )
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.width(w)
                                ) {
                                    Text(
                                        c.text.trim().replace(Regex("\\s+"), " "),
                                        fontSize = 12.sp, modifier = Modifier.padding(8.dp),
                                        maxLines = 4, overflow = TextOverflow.Ellipsis
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 12.sp) },
        modifier = Modifier.heightIn(min = 32.dp)
    )
}

@Composable
private fun TfInput(value: String, onValue: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        shape = RoundedCornerShape(10.dp)
    )
}
