package com.wb.mdgw.elementdoc

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.AppSettings
import com.wb.mdgw.BrandTokens
import com.wb.mdgw.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 要素式文书填表页：按官方原表逐行渲染（说明/章节/勾选要素/输入/表格/附件），
 * 生成时把草稿注入原表并导出保留官方版式的 .docx。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ElementFillScreen(
    template: SpcTemplate,
    initialDraft: ElementDraft?,
    onBack: () -> Unit,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var caseName by remember { mutableStateOf(initialDraft?.caseName ?: "") }
    var rowValues by remember { mutableStateOf(initialDraft?.rowValues ?: emptyMap()) }
    var gridValues by remember { mutableStateOf(initialDraft?.gridValues ?: emptyMap()) }
    var attachmentRows by remember { mutableStateOf(initialDraft?.attachmentRows ?: emptyMap()) }
    var extraRows by remember { mutableStateOf(initialDraft?.extraRows ?: emptyList()) }
    var excludedIds by remember { mutableStateOf(initialDraft?.excludedRowIds ?: emptySet()) }
    var signerName by remember { mutableStateOf(initialDraft?.signerName ?: "") }
    var signDate by remember { mutableStateOf(initialDraft?.signDate ?: "") }
    var includeAttachments by remember { mutableStateOf(initialDraft?.includeAttachments ?: true) }
    var hideUntouched by remember { mutableStateOf(initialDraft?.hideUntouchedPartyRows ?: false) }

    var showPreview by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // UI 优化：说明长文默认折叠，减少首屏占用
    var noticeExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }
    // 草稿管理 / A4 预览 / 示例
    var showDraftsDialog by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<List<ElementDraft>>(emptyList()) }
    var showA4Preview by remember { mutableStateOf(false) }
    var isDemoSample by remember { mutableStateOf(initialDraft?.caseName == DemoSampleEngine.DEMO_CASE_NAME) }

    /** 把一份草稿整体灌入当前编辑状态（载入示例/恢复草稿/空白表共用） */
    fun applyDraft(d: ElementDraft) {
        caseName = d.caseName
        rowValues = d.rowValues
        gridValues = d.gridValues
        attachmentRows = d.attachmentRows
        extraRows = d.extraRows
        excludedIds = d.excludedRowIds
        signerName = d.signerName
        signDate = d.signDate
        includeAttachments = d.includeAttachments
        hideUntouched = d.hideUntouchedPartyRows
        isDemoSample = d.caseName == DemoSampleEngine.DEMO_CASE_NAME
    }

    /** 空白表：清空所有已填值，保留官方原表结构 */
    fun applyBlank() {
        applyDraft(ElementDraft(templateId = template.id, caseName = "空白表"))
        scope.launch { snackbar.showSnackbar("已恢复官方空白原表，可直接预览或下载空白表") }
    }

    // 从本机 JSON 备份恢复草稿：读取用户选择的 .json 文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = FileUtils.readText(context, uri)
                    ElementDraftBackup.parse(text)
                }
            }
            result.onSuccess { draftsInFile ->
                if (draftsInFile.isEmpty()) {
                    snackbar.showSnackbar("备份文件中没有可恢复的草稿")
                } else {
                    draftsInFile.forEach { ElementDraftStore.save(context, it) }
                    // 优先恢复当前模板的第一份
                    val mine = draftsInFile.filter { it.templateId == template.id }
                    if (mine.isNotEmpty()) applyDraft(mine.first())
                    snackbar.showSnackbar("已从备份恢复 ${draftsInFile.size} 份草稿")
                }
            }.onFailure {
                snackbar.showSnackbar("恢复失败：${it.message ?: "文件格式不正确"}")
            }
        }
    }

    /** 导出所有草稿为 JSON 备份到 Downloads */
    fun exportBackup() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = ElementDraftStore.all(context)
                    val json = ElementDraftBackup.serialize(all)
                    val outName = "要素式文书草稿备份_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())}.json"
                    FileUtils.saveToDownloads(context, outName, json.toByteArray(Charsets.UTF_8), "application/json")
                }
            }
            result.onSuccess { snackbar.showSnackbar("草稿备份已导出：${it.displayPath}") }
                .onFailure { snackbar.showSnackbar("导出失败：${it.message ?: "未知错误"}") }
        }
    }

    fun currentDraft() = ElementDraft(
        templateId = template.id,
        caseName = caseName.trim(),
        rowValues = rowValues,
        gridValues = gridValues,
        attachmentRows = attachmentRows,
        extraRows = extraRows,
        excludedRowIds = excludedIds,
        hideUntouchedPartyRows = hideUntouched,
        includeAttachments = includeAttachments,
        signerName = signerName,
        signDate = signDate
    )

    fun persistDraft() {
        if (caseName.isBlank()) return
        ElementDraftStore.save(context, currentDraft())
    }

    // 切后台/返回时保存
    DisposableEffect(Unit) {
        onDispose { if (caseName.isNotBlank()) ElementDraftStore.save(context, currentDraft()) }
    }

    fun resolvedDoc(): ResolvedSpcDocument = ElementEngine.buildResolved(template, currentDraft())

    fun doGenerate() {
        if (caseName.isBlank()) {
            scope.launch { snackbar.showSnackbar("请先填写案名/当事人，用于区分草稿") }
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = resolvedDoc()
                    val bytes = SpcDocxBuilder.build(doc)
                    val safeName = caseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val demoTag = if (isDemoSample) "_${DemoSampleEngine.DEMO_FILE_TAG}" else ""
                    val outName = "${template.displayName}-${safeName}$demoTag.docx"
                    val saved = FileUtils.saveToDownloads(context, outName, bytes, FileUtils.DOCX_MIME)
                    ElementDraftStore.save(context, currentDraft())
                    Triple(saved.displayPath, saved.uri, outName)
                }
            }
            result.onSuccess { (path, uri, name) ->
                snackbar.showSnackbar("已生成并保存：$path")
                context.startActivity(FileUtils.shareIntent(uri, name, FileUtils.DOCX_MIME))
            }.onFailure {
                snackbar.showSnackbar("生成失败：${it.message ?: "未知错误"}")
            }
            busy = false
        }
    }

    fun rowText(id: String): String = rowValues[id] ?: template.rows.firstOrNull { it.id == id }?.templateText ?: ""

    fun setRow(id: String, value: String) { rowValues = rowValues + (id to value) }

    /** 按最高法《说明》第4条，复制可重复的主体行（多原告/多被告/多代理人等） */
    fun duplicateRow(row: com.wb.mdgw.elementdoc.SpcRow) {
        val count = extraRows.count { it.sourceRowId == row.id }
        val baseLabel = row.label.replace("\n", "")
        val newLabel = ElementEngine.nextDuplicateLabel(row.label, count)
        val ex = ElementDraft.ExtraRow(
            uid = "extra_${row.id}_${System.currentTimeMillis()}_${count}",
            sourceRowId = row.id,
            label = newLabel,
            value = row.templateText,
            roleTag = row.roleTag
        )
        extraRows = extraRows + ex
        scope.launch { snackbar.showSnackbar("已复制新增一栏「${baseLabel}」，可直接改标题与内容") }
    }

    fun updateExtra(uid: String, label: String, value: String) {
        extraRows = extraRows.map { if (it.uid == uid) it.copy(label = label, value = value) else it }
    }

    fun removeExtra(uid: String) { extraRows = extraRows.filterNot { it.uid == uid } }

    fun toggleExcluded(id: String) {
        excludedIds = if (id in excludedIds) excludedIds - id else excludedIds + id
    }

    Box(Modifier.fillMaxSize()) {
    if (showA4Preview) {
        ElementA4PreviewScreen(
            html = ElementDocHtmlBuilder.build(resolvedDoc()),
            title = template.displayName,
            onBack = { showA4Preview = false }
        )
    } else {
    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(template.displayName, style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp), maxLines = 1)
                        Text("${template.domainName} · ${template.roleLabel}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { showA4Preview = true }) {
                        Icon(Icons.Default.Print, contentDescription = "A4预览")
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("载入虚构案例体验") },
                            leadingIcon = { Icon(Icons.Default.Science, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                applyDraft(DemoSampleEngine.build(template))
                                scope.launch { snackbar.showSnackbar("已载入虚构案例，导出时自动标注「虚构样例_不可提交」") }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("恢复官方空白表") },
                            leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                            onClick = { menuOpen = false; applyBlank() }
                        )
                        DropdownMenuItem(
                            text = { Text("存草稿") },
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                if (caseName.isBlank()) {
                                    scope.launch { snackbar.showSnackbar("请先填写案名再存草稿") }
                                } else {
                                    persistDraft()
                                    scope.launch { snackbar.showSnackbar("草稿已保存到本机") }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("草稿列表 / 恢复") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                drafts = ElementDraftStore.listForTemplate(context, template.id)
                                showDraftsDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出草稿备份 JSON") },
                            leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                            onClick = { menuOpen = false; exportBackup() }
                        )
                        DropdownMenuItem(
                            text = { Text("从备份恢复 JSON") },
                            leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                            onClick = { menuOpen = false; importLauncher.launch("*/*") }
                        )
                        DropdownMenuItem(
                            text = { Text("关于本模板") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { menuOpen = false; showAbout = true }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
            )
        },
        bottomBar = {
            // 固定底部操作栏：不悬浮遮挡正文（原 FAB 会盖住说明文字）
            Surface(tonalElevation = 2.dp, color = BrandTokens.BrandPaper) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { previewText = ElementEngine.toPlainText(resolvedDoc()); showPreview = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("预览", fontSize = 12.sp) }
                    Button(
                        onClick = { doGenerate() },
                        modifier = Modifier.weight(2f),
                        enabled = !busy
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (busy) "生成中…" else "生成 Word", fontSize = 12.sp)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 案名 + 选项（紧凑布局：三个输入各占一行，开关合并一行，依据折叠）
            item {
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = caseName,
                            onValueChange = { caseName = it },
                            label = { Text("案名 / 当事人（用于区分草稿）", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signerName, onValueChange = { signerName = it },
                                label = { Text("签名人", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                placeholder = { Text("留空手签/盖章", fontSize = 11.sp) }
                            )
                            OutlinedTextField(
                                value = signDate, onValueChange = { signDate = it },
                                label = { Text("日期", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                placeholder = { Text("年 月 日", fontSize = 11.sp) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = includeAttachments, onCheckedChange = { includeAttachments = it },
                                modifier = Modifier.height(28.dp))
                            Text("附件表", fontSize = 11.sp)
                            Spacer(Modifier.size(12.dp))
                            Switch(checked = hideUntouched, onCheckedChange = { hideUntouched = it },
                                modifier = Modifier.height(28.dp))
                            Text("隐藏未填当事人行", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = { sourceExpanded = !sourceExpanded },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text(if (sourceExpanded) "收起依据" else "依据：最高法《法〔2025〕82号》官方示范文本",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sourceExpanded) {
                            Text("源文件：${template.sourceFile}。2025-07-14 起全国法院推广，文书须经执业律师复核后使用。",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 官方原表逐行渲染
            itemsIndexed(template.rows) { index, row ->
                if (row.id in excludedIds) {
                    // 已隐藏：显示恢复条
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { toggleExcluded(row.id) }
                            .background(Color(0xFFF0EAE0), RoundedCornerShape(8.dp)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("已隐藏：${row.label.ifBlank { row.kind }}（点击恢复）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    when (row.kind) {
                        "notice" -> {
                            // 官方填表说明通常较长，默认折叠避免占满首屏，点击展开
                            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    TextButton(
                                        onClick = { noticeExpanded = !noticeExpanded },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (noticeExpanded) "▼ 收起官方填表说明"
                                            else "▶ 官方填表说明（${row.templateText.length}字，点击展开）",
                                            fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (noticeExpanded) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(row.templateText, fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        "section" -> {
                            // 章节/特别提示统一左对齐，与 Word 输出一致；紧凑排布
                            Text(
                                row.templateText.replace("\n", " "), fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                textAlign = TextAlign.Start
                            )
                        }
                        "grid" -> {
                            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("表格（${row.cells.size} 列）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val cells = gridValues[row.id] ?: row.cells
                                    cells.forEachIndexed { ci, cell ->
                                        OutlinedTextField(
                                            value = cell,
                                            onValueChange = { nv ->
                                                val updated = cells.toMutableList().also { it[ci] = nv }
                                                gridValues = gridValues + (row.id to updated)
                                            },
                                            label = { Text("第${ci + 1}栏") },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        "guide", "full_input" -> {
                            OutlinedTextField(
                                value = rowText(row.id),
                                onValueChange = { setRow(row.id, it) },
                                label = { Text(row.label.ifBlank { row.templateText.take(20) }) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                        }
                        else -> {
                            // field：标签 + 可勾选原文输入框
                            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(row.label, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                            modifier = Modifier.weight(1f))
                                        // 按最高法《说明》第4条，主体行可复制新增一栏（多原告/多被告等）
                                        if (row.repeatable) {
                                            TextButton(
                                                onClick = { duplicateRow(row) },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.size(3.dp))
                                                Text("复制新增一行", fontSize = 11.sp)
                                            }
                                        }
                                        IconButton(onClick = { toggleExcluded(row.id) }) {
                                            Icon(Icons.Default.VisibilityOff, contentDescription = "隐藏此行", modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    CheckboxTextField(
                                        text = rowText(row.id),
                                        onTextChange = { setRow(row.id, it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        roleTag = row.roleTag
                                    )
                                    // 复制出的追加行：可改标题/内容、可删除
                                    val extras = extraRows.filter { it.sourceRowId == row.id }
                                    extras.forEach { ex ->
                                        Card(
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                        ) {
                                            Column(Modifier.padding(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    OutlinedTextField(
                                                        value = ex.label,
                                                        onValueChange = { updateExtra(ex.uid, it, ex.value) },
                                                        label = { Text("标题") },
                                                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                                                        singleLine = true
                                                    )
                                                    IconButton(onClick = { removeExtra(ex.uid) }) {
                                                        Icon(Icons.Default.Delete, contentDescription = "删除此行", modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                                CheckboxTextField(
                                                    text = ex.value,
                                                    onTextChange = { updateExtra(ex.uid, ex.label, it) },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                    roleTag = ex.roleTag
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

            // 附件表
            if (includeAttachments) {
                itemsIndexed(template.attachments) { attIdx, att ->
                    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("附件：${att.title}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            val rows = attachmentRows[attIdx] ?: att.rows
                            rows.forEachIndexed { rIdx, cells ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    cells.forEachIndexed { cIdx, cell ->
                                        OutlinedTextField(
                                            value = cell,
                                            onValueChange = {
                                                val newRows = rows.toMutableList()
                                                val newCells = cells.toMutableList(); newCells[cIdx] = it
                                                newRows[rIdx] = newCells
                                                attachmentRows = attachmentRows + (attIdx to newRows)
                                            },
                                            modifier = Modifier.weight(1f), singleLine = true,
                                            label = { Text(att.columns.getOrNull(cIdx) ?: "") }
                                        )
                                    }
                                }
                            }
                            Row {
                                TextButton(onClick = {
                                    val newRows = rows.toMutableList()
                                    newRows.add(List(att.columns.size.coerceAtLeast(1)) { "" })
                                    attachmentRows = attachmentRows + (attIdx to newRows)
                                }) { Text("+ 加一行") }
                                if (rows.isNotEmpty()) {
                                    TextButton(onClick = {
                                        attachmentRows = attachmentRows + (attIdx to rows.dropLast(1))
                                    }) { Text("− 删末行") }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("纯文本预览") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    item { Text(previewText, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("关闭") } }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于本模板") },
            text = {
                Column {
                    Text("依据最高人民法院、司法部、中华全国律师协会《关于印发部分案件起诉状答辩状示范文本的通知》（法〔2025〕82号），2025-07-14 起全国法院全面推广。")
                    Spacer(Modifier.height(8.dp))
                    Text("结构化数据与 docx 生成引擎移植自开源项目 qwertyzhu/cn-lawyer-ai-toolbox（lawyer朱思浩），遵循 Apache License 2.0，保留原始署名。")
                    Spacer(Modifier.height(8.dp))
                    Text("本工具为效率辅助，文书须经执业律师复核后方可用于立案。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } }
        )
    }

    if (showDraftsDialog) {
        DraftsDialog(
            drafts = drafts,
            onDismiss = { showDraftsDialog = false },
            onRestore = { d ->
                applyDraft(d)
                showDraftsDialog = false
                scope.launch { snackbar.showSnackbar("已恢复草稿「${d.caseName}」") }
            },
            onDelete = { d ->
                ElementDraftStore.delete(context, d.templateId, d.caseName)
                drafts = ElementDraftStore.listForTemplate(context, template.id)
                scope.launch { snackbar.showSnackbar("已删除草稿「${d.caseName}」") }
            }
        )
    }
    } // end else
    } // end Box
}

/**
 * 草稿列表弹窗：列出本模板已存草稿，可恢复/删除。
 */
@Composable
private fun DraftsDialog(
    drafts: List<ElementDraft>,
    onDismiss: () -> Unit,
    onRestore: (ElementDraft) -> Unit,
    onDelete: (ElementDraft) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本机草稿（${drafts.size}）") },
        text = {
            if (drafts.isEmpty()) {
                Text("本模板暂无已存草稿。填写案名后点「存草稿」即可。", fontSize = 12.sp)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(drafts) { _, d ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.caseName.ifBlank { "（未命名）" }, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                    Text(
                                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(d.updatedAt)),
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onRestore(d) }) { Text("恢复", fontSize = 12.sp) }
                                IconButton(onClick = { onDelete(d) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除草稿", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 支持点击 □/☑ 切换勾选的文本输入框。
 * 把原文按勾选符拆段，□/☑ 作为可点击 chip，其余为普通文本展示；长按或点文本区进入编辑。
 */
@Composable
private fun CheckboxTextField(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    roleTag: String = "",
    showMediationShortcut: Boolean = false
) {
    val context = LocalContext.current
    val lawyerProfile by AppSettings.lawyerProfile.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var showQuickFill by remember { mutableStateOf(false) }
    var quickData by remember { mutableStateOf(PartyFillEngine.Data()) }
    val tokens = remember(text) { ElementEngine.parseCheckboxTokens(text) }
    // 仅"先行调解"要素行（官方 roleTag）或显式开启时渲染一键快捷键，且常驻不折叠
    val showShortcut = showMediationShortcut || roleTag == "mediation_benefit"
    val isAgent = roleTag == "agent"
    val fillable = PartyFillEngine.isPartyFillable(roleTag)

    Column(modifier) {
        // 一键快捷键：常驻于可视勾选区上方，不依赖进入"编辑原文"
        if (showShortcut) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onTextChange(ElementEngine.setAllMediationBenefitChoices(text, true)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("一键勾选 5 项“了解☑”", fontSize = 11.sp)
                }
                Spacer(Modifier.size(4.dp))
                TextButton(
                    onClick = { onTextChange(ElementEngine.setAllMediationBenefitChoices(text, false)) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("反向：均选“不了解☑”", fontSize = 11.sp)
                }
            }
        }
        // 委托诉讼代理人行：一键带入设置里保存的律师执业档案
        if (isAgent) {
            TextButton(
                onClick = {
                    if (lawyerProfile.isEmpty) {
                        Toast.makeText(context, "请先在「设置 → 我的执业档案」填写姓名与律所", Toast.LENGTH_LONG).show()
                    } else {
                        val filled = PartyFillEngine.fromLawyerProfile(
                            templateText = text,
                            name = lawyerProfile.name,
                            firmName = lawyerProfile.firmName,
                            licenseNo = lawyerProfile.licenseNo,
                            phone = lawyerProfile.phone
                        )
                        onTextChange(filled)
                        Toast.makeText(context, "已从执业证信息带入「${lawyerProfile.displayName}」代理信息", Toast.LENGTH_SHORT).show()
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("一键带入我的执业证信息", fontSize = 11.sp)
            }
        }
        // 主体行：打开快速结构化填入面板
        if (fillable) {
            TextButton(
                onClick = { quickData = PartyFillEngine.Data(); showQuickFill = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("快速结构化填入", fontSize = 11.sp)
            }
        }
        // 可视勾选区：按 □/☑ 切段渲染
        WrapCheckboxText(text = text, onToggle = { idx -> onTextChange(ElementEngine.toggleCheckboxAtIndex(text, idx)) })
        TextButton(onClick = { editing = !editing }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(if (editing) "收起编辑" else "编辑原文", fontSize = 11.sp)
        }
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                placeholder = { Text("可直接输入/修改，□ 表示未勾选，☑ 表示已勾选；勾选数 ${tokens.size} 项") }
            )
        }
    }

    if (showQuickFill) {
        QuickFillDialog(
            roleTag = roleTag,
            data = quickData,
            onDataChange = { quickData = it },
            onDismiss = { showQuickFill = false },
            onApply = {
                onTextChange(PartyFillEngine.applyToCellText(text, roleTag, quickData))
                showQuickFill = false
                Toast.makeText(context, "已按官方表格结构填入", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * 快速结构化填入面板：按官方 roleTag 展示对应字段，填入后由 PartyFillEngine
 * 精准替换原表单元格，保留官方标签与勾选项顺序。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFillDialog(
    roleTag: String,
    data: PartyFillEngine.Data,
    onDataChange: (PartyFillEngine.Data) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    // UI 聚焦：面板打开即聚焦首个输入框；高对比边框/标签色明确当前焦点
    val firstFieldFocus = remember { FocusRequester() }
    LaunchedEffect(roleTag) {
        runCatching { firstFieldFocus.requestFocus() }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速结构化填入", style = MaterialTheme.typography.titleMedium) },
        text = {
            // 关键修正：字段超出高度时滚动，避免底部输入框被裁切只剩边框
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "按官方表格结构填入，保留标签与勾选顺序；留空字段不改动。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    roleTag == "agent" -> {
                        QFGroup("代理信息")
                        OptionsRow("是否有代理人") {
                            FilterChip(selected = data.agentHas, onClick = { onDataChange(data.copy(agentHas = true)) }, label = { Text("有", fontSize = 11.sp) })
                            FilterChip(selected = !data.agentHas, onClick = { onDataChange(data.copy(agentHas = false)) }, label = { Text("无", fontSize = 11.sp) })
                        }
                        OptionsRow("代理权限") {
                            listOf("一般授权", "特别授权").forEach { a ->
                                FilterChip(selected = data.agentAuthority == a, onClick = { onDataChange(data.copy(agentAuthority = a)) }, label = { Text(a, fontSize = 11.sp) })
                            }
                        }
                        QFGroup("执业信息")
                        QFTextField("姓名", data.name, focusRequester = firstFieldFocus, colors = fieldColors) { onDataChange(data.copy(name = it)) }
                        QFTextField("单位 / 律所", data.workUnit, colors = fieldColors) { onDataChange(data.copy(workUnit = it)) }
                        QFTextField("职务", data.jobTitle, colors = fieldColors) { onDataChange(data.copy(jobTitle = it)) }
                        QFTextField("联系电话", data.phone, colors = fieldColors) { onDataChange(data.copy(phone = it)) }
                    }
                    roleTag.contains("person") -> {
                        QFGroup("基本信息")
                        QFTextField("姓名", data.name, focusRequester = firstFieldFocus, colors = fieldColors) { onDataChange(data.copy(name = it)) }
                        OptionsRow("性别") {
                            listOf("男", "女").forEach { g ->
                                FilterChip(selected = data.gender == g, onClick = { onDataChange(data.copy(gender = g)) }, label = { Text(g, fontSize = 11.sp) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QFTextField("出生日期", data.birthDate, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(birthDate = it)) }
                            QFTextField("民族", data.ethnicity, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(ethnicity = it)) }
                        }
                        QFGroup("职业与联系")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QFTextField("工作单位", data.workUnit, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(workUnit = it)) }
                            QFTextField("职务", data.jobTitle, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(jobTitle = it)) }
                        }
                        QFTextField("联系电话", data.phone, colors = fieldColors) { onDataChange(data.copy(phone = it)) }
                        QFGroup("住址与证件")
                        QFTextField("住所地（户籍所在地）", data.domicile, colors = fieldColors) { onDataChange(data.copy(domicile = it)) }
                        QFTextField("经常居住地", data.residence, colors = fieldColors) { onDataChange(data.copy(residence = it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QFTextField("证件类型", data.idType, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(idType = it)) }
                            QFTextField("证件号码", data.idNumber, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(idNumber = it)) }
                        }
                    }
                    else -> {
                        QFGroup("主体信息")
                        QFTextField("名称", data.name, focusRequester = firstFieldFocus, colors = fieldColors) { onDataChange(data.copy(name = it)) }
                        QFTextField("住所地（主要办事机构所在地）", data.domicile, colors = fieldColors) { onDataChange(data.copy(domicile = it)) }
                        QFTextField("注册地 / 登记地", data.regAddress, colors = fieldColors) { onDataChange(data.copy(regAddress = it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QFTextField("法定代表人 / 负责人", data.legalRep, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(legalRep = it)) }
                            QFTextField("职务", data.repTitle, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(repTitle = it)) }
                        }
                        QFTextField("联系电话", data.phone, colors = fieldColors) { onDataChange(data.copy(phone = it)) }
                        QFGroup("证照与银行")
                        QFTextField("统一社会信用代码", data.creditCode, colors = fieldColors) { onDataChange(data.copy(creditCode = it)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QFTextField("银行账号", data.bankAccount, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(bankAccount = it)) }
                            QFTextField("开户名", data.bankHolder, colors = fieldColors, modifier = Modifier.weight(1f)) { onDataChange(data.copy(bankHolder = it)) }
                        }
                        QFTextField("开户行", data.bankName, colors = fieldColors) { onDataChange(data.copy(bankName = it)) }
                        QFGroup("组织类型")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PartyFillEngine.ORG_TYPES.forEach { ot ->
                                FilterChip(
                                    selected = data.orgType == ot,
                                    onClick = { onDataChange(data.copy(orgType = ot)) },
                                    label = { Text(ot, fontSize = 10.sp) }
                                )
                            }
                        }
                        OptionsRow("所有制性质") {
                            listOf("民营", "国有", "其他").forEach { o ->
                                FilterChip(selected = data.ownershipNature == o, onClick = { onDataChange(data.copy(ownershipNature = o)) }, label = { Text(o, fontSize = 11.sp) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("填入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun QFGroup(title: String) {
    Text(
        title, fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun QFTextField(
    label: String,
    value: String,
    focusRequester: FocusRequester? = null,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        colors = colors,
        modifier = modifier.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        )
    )
}

@Composable
private fun OptionsRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** 把含 □/☑ 的文本拆成普通片段与可点勾选框，流式排布 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapCheckboxText(text: String, onToggle: (Int) -> Unit) {
    val parts = remember(text) {
        val list = mutableListOf<Pair<String, Int?>>() // (文本, 勾选框索引或null)
        var boxIdx = 0
        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                '□', '☑' -> {
                    if (sb.isNotEmpty()) { list.add(sb.toString() to null); sb.clear() }
                    list.add(ch.toString() to boxIdx)
                    boxIdx++
                }
                '\n' -> { sb.append(' '); }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) list.add(sb.toString() to null)
        list
    }
    FlowRow(horizontalArrangement = Arrangement.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (seg, boxIdx) ->
            if (boxIdx == null) {
                Text(seg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                        .size(20.dp)
                        .background(
                            if (seg == "☑") MaterialTheme.colorScheme.primaryContainer else Color(0xFFEDE7DE),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onToggle(boxIdx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(seg, fontSize = 14.sp, color = if (seg == "☑") MaterialTheme.colorScheme.primary else Color.DarkGray)
                }
            }
        }
    }
}
