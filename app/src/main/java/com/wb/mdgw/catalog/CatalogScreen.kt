package com.wb.mdgw.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 目录种类 */
enum class CatalogKind(val screenTitle: String) {
    EVIDENCE("证据目录"),
    ARCHIVE("法援目录")
}

@Composable
fun CatalogScreen(kind: CatalogKind, onBack: () -> Unit) {
    when (kind) {
        CatalogKind.EVIDENCE -> EvidenceScreen(onBack)
        CatalogKind.ARCHIVE -> ArchiveScreen(onBack)
    }
}

/** 生成结果（缓存 uri 用于分享/打开） */
private data class CatalogResult(
    val fileName: String,
    val displayPath: String,
    val uri: android.net.Uri
)

private fun todayString(): String =
    SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINA).format(Date())

private fun dateStamp(): String =
    SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

// ==================== 证据目录 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val prefs = remember { CatalogPrefs.load(context) }
    val draft = remember { EvidenceDraftStore.load(context) }
    var title by remember { mutableStateOf(draft?.title ?: prefs.evidenceTitle) }
    var submitter by remember { mutableStateOf(draft?.submitter ?: prefs.submitter) }
    var date by remember {
        mutableStateOf(draft?.date?.ifBlank { todayString() } ?: todayString())
    }
    var minRows by remember { mutableStateOf(draft?.minRows ?: prefs.minRows.toString()) }
    var items by remember {
        mutableStateOf(
            draft?.items?.map { EvidenceItem(it.name, it.pages, it.purpose, it.source) }
                ?.ifEmpty { listOf(EvidenceItem()) }
                ?: listOf(EvidenceItem())
        )
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CatalogResult?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // 输入停顿 0.5 秒自动保存草稿（含全部证据条目）
    LaunchedEffect(title, submitter, date, minRows, items) {
        delay(500)
        val d = EvidenceDraft(
            title = title, submitter = submitter, date = date, minRows = minRows,
            items = items.map { EvidenceDraftItem(it.name, it.pages, it.purpose, it.source) }
        )
        withContext(Dispatchers.IO) { EvidenceDraftStore.save(context, d) }
    }

    fun clearAll() {
        title = "证 据 目 录"
        submitter = ""
        date = todayString()
        minRows = "10"
        items = listOf(EvidenceItem())
        scope.launch { withContext(Dispatchers.IO) { EvidenceDraftStore.clear(context) } }
    }

    fun update(i: Int, block: EvidenceItem.() -> EvidenceItem) {
        items = items.mapIndexed { idx, it -> if (idx == i) it.block() else it }
    }
    fun move(i: Int, up: Boolean) {
        val j = if (up) i - 1 else i + 1
        if (j !in items.indices) return
        items = items.toMutableList().apply { add(j, removeAt(i)) }
    }
    fun duplicate(i: Int) {
        items = items.toMutableList().apply { add(i + 1, items[i].copy()) }
    }
    fun remove(i: Int) {
        items = items.toMutableList().apply { removeAt(i) }
    }

    fun doGenerate() {
        val meaningful = items.filter {
            it.name.isNotBlank() || it.pages.isNotBlank() ||
                it.purpose.isNotBlank() || it.source.isNotBlank()
        }
        if (meaningful.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请至少填写一条证据") }
            return
        }
        val rows = minRows.toIntOrNull()?.coerceIn(0, 200) ?: 10
        val form = EvidenceForm(
            title = title.ifBlank { "证 据 目 录" },
            submitter = submitter,
            date = date,
            minRows = rows,
            items = meaningful
        )
        val sub = submitter.trim().ifBlank { "未填" }
        val fileName = "证据目录_${sub}_${dateStamp()}.docx"
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                val bytes = CatalogDocxBuilder.evidence(form)
                val saved = FileUtils.saveToDownloads(context, fileName, bytes, FileUtils.DOCX_MIME)
                val uri = FileUtils.writeCache(context, fileName, bytes)
                CatalogPrefs.save(context, CatalogPrefsData(
                    evidenceTitle = form.title,
                    submitter = submitter.trim(),
                    minRows = rows,
                    archiveTitle = prefs.archiveTitle
                ))
                CatalogResult(fileName, saved.displayPath, uri)
            }
            busy = false
            result = res
        }
    }

    Scaffold(
        containerColor = com.wb.mdgw.BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "证据目录",
                        style = com.wb.mdgw.BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空重填")
                    }
                },
                colors = com.wb.mdgw.BrandTokens.BrandTopAppBarColors,
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(com.wb.mdgw.BrandTokens.BrandBronze.copy(alpha = 0.35f))
                )
            }
            )
        },
        bottomBar = {
            Button(
                onClick = { doGenerate() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("正在生成…")
                } else {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("生成 Word", fontSize = 16.sp)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            // 公共设置
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CatalogField(title, { title = it }, "标题")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { CatalogField(submitter, { submitter = it }, "提交人") }
                            Box(Modifier.weight(1f)) { CatalogField(date, { date = it }, "日期") }
                        }
                        CatalogField(
                            minRows, { v -> minRows = v.filter { it.isDigit() }.take(3) },
                            "数据区至少保留行数（空行补编号）",
                            keyboardType = KeyboardType.Number
                        )
                    }
                }
            }

            // 证据条目
            itemsIndexed(items, key = { i, _ -> i }) { i, item ->
                EvidenceCard(
                    index = i,
                    item = item,
                    canUp = i > 0,
                    canDown = i < items.size - 1,
                    onChange = { block -> update(i, block) },
                    onMove = { up -> move(i, up) },
                    onCopy = { duplicate(i) },
                    onDelete = { remove(i) }
                )
            }

            item {
                OutlinedButton(
                    onClick = { items = items + EvidenceItem() },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("添加证据")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空重填") },
            text = { Text("将清空当前全部证据条目与已填信息，且不可恢复，确定吗？") },
            confirmButton = {
                TextButton(onClick = { clearAll(); confirmClear = false }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    result?.let { CatalogResultDialog(it, onDismiss = { result = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceCard(
    index: Int,
    item: EvidenceItem,
    canUp: Boolean,
    canDown: Boolean,
    onChange: (EvidenceItem.() -> EvidenceItem) -> Unit,
    onMove: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "证据 ${index + 1}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.MoreVert, "条目操作", Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("上移") }, enabled = canUp,
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                            onClick = { menu = false; onMove(true) })
                        DropdownMenuItem(text = { Text("下移") }, enabled = canDown,
                            leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                            onClick = { menu = false; onMove(false) })
                        DropdownMenuItem(text = { Text("复制此条") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; onCopy() })
                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
            CatalogField(item.name, { v -> onChange { copy(name = v) } }, "证据名称")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(0.42f)) {
                    CatalogField(item.pages, { v -> onChange { copy(pages = v) } }, "页数",
                        keyboardType = KeyboardType.Text)
                }
                Box(Modifier.weight(0.58f)) {
                    CatalogField(item.source, { v -> onChange { copy(source = v) } }, "证据来源")
                }
            }
            CatalogField(item.purpose, { v -> onChange { copy(purpose = v) } }, "证明目的",
                singleLine = false, minLines = 3)
        }
    }
}

// ==================== 归档目录 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    val prefs = remember { CatalogPrefs.load(context) }
    val archiveDraft = remember { ArchiveDraftStore.load(context) }
    var title by remember { mutableStateOf(archiveDraft?.title ?: prefs.archiveTitle) }
    var items by remember {
        mutableStateOf(
            archiveDraft?.items?.takeIf { it.isNotEmpty() }
                ?.map { ArchiveItem(it.name, it.page) }
                ?: CatalogTemplates.newArchiveItems()
        )
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CatalogResult?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // 输入停顿 0.5 秒自动保存草稿（项目名改动 + 页码）
    LaunchedEffect(title, items) {
        delay(500)
        val d = ArchiveDraft(
            title = title,
            items = items.map { ArchiveDraftItem(it.name, it.page) }
        )
        withContext(Dispatchers.IO) { ArchiveDraftStore.save(context, d) }
    }

    fun clearAll() {
        title = "宁乡市法律援助案卷归档目录"
        items = CatalogTemplates.newArchiveItems()
        scope.launch { withContext(Dispatchers.IO) { ArchiveDraftStore.clear(context) } }
    }

    val focusRequesters = remember { List(CatalogTemplates.ARCHIVE_ITEMS.size) { FocusRequester() } }

    fun page(i: Int, v: String) {
        items = items.mapIndexed { idx, it -> if (idx == i) it.copy(page = v) else it }
    }
    fun name(i: Int, v: String) {
        items = items.mapIndexed { idx, it -> if (idx == i) it.copy(name = v) else it }
    }

    fun doGenerate() {
        val form = ArchiveForm(title = title.ifBlank { "宁乡市法律援助案卷归档目录" }, items = items)
        val fileName = "法援目录_${dateStamp()}.docx"
        scope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) {
                val bytes = CatalogDocxBuilder.archive(form)
                val saved = FileUtils.saveToDownloads(context, fileName, bytes, FileUtils.DOCX_MIME)
                val uri = FileUtils.writeCache(context, fileName, bytes)
                CatalogPrefs.save(context, prefs.copy(archiveTitle = form.title))
                CatalogResult(fileName, saved.displayPath, uri)
            }
            busy = false
            result = res
        }
    }

    Scaffold(
        containerColor = com.wb.mdgw.BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "法援目录",
                        style = com.wb.mdgw.BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 20.sp)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空重填")
                    }
                },
                colors = com.wb.mdgw.BrandTokens.BrandTopAppBarColors,
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(com.wb.mdgw.BrandTokens.BrandBronze.copy(alpha = 0.35f))
                )
            }
            )
        },
        bottomBar = {
            Button(
                onClick = { doGenerate() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("正在生成…")
                } else {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("生成 Word", fontSize = 16.sp)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            item {
                Card(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    CatalogField(title, { title = it }, "标题",
                        modifier = Modifier.padding(12.dp))
                }
            }
            itemsIndexed(items, key = { i, _ -> i }) { i, item ->
                Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            item.name,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .padding(vertical = 6.dp)
                        )
                        IconButton(onClick = { editing = i }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, "修改项目名", Modifier.size(15.dp))
                        }
                        OutlinedTextField(
                            value = item.page,
                            onValueChange = { v -> page(i, v.filter { it.isDigit() || it == '-' || it == ',' }.take(8)) },
                            modifier = Modifier
                                .width(86.dp)
                                .heightIn(min = 44.dp)
                                .focusRequester(focusRequesters[i]),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                            placeholder = { Text("页码", fontSize = 12.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = if (i < items.size - 1) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onNext = {
                                    if (i < focusRequesters.size - 1) {
                                        focusRequesters[i + 1].requestFocus()
                                    } else focusManager.clearFocus()
                                },
                                onDone = { focusManager.clearFocus() }
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }
    }

    // 修改项目名
    editing?.let { i ->
        var draft by remember(i) { mutableStateOf(items[i].name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改项目 ${i + 1}") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = { name(i, draft.trim()); editing = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空重填") },
            text = { Text("将恢复为默认 21 个项目并清空全部页码，确定吗？") },
            confirmButton = {
                TextButton(onClick = { clearAll(); confirmClear = false }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }

    result?.let { CatalogResultDialog(it, onDismiss = { result = null }) }
}

// ==================== 公共组件 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier.heightIn(min = 44.dp) else Modifier)
            .padding(vertical = 2.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
        label = { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    )
}

@Composable
private fun CatalogResultDialog(res: CatalogResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成完成") },
        text = {
            Column {
                Text(res.fileName, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("保存位置：${res.displayPath}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    context.startActivity(FileUtils.shareIntent(res.uri, res.fileName, FileUtils.DOCX_MIME))
                }
            }) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("分享")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(FileUtils.openIntent(res.uri, FileUtils.DOCX_MIME))
                }
            }) { Text("打开") }
        }
    )
}
