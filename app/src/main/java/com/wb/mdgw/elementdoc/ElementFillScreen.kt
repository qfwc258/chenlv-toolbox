package com.wb.mdgw.elementdoc

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showExtrasMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // UI 优化：说明长文默认折叠，减少首屏占用
    var noticeExpanded by remember { mutableStateOf(false) }
    var sourceExpanded by remember { mutableStateOf(false) }

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
                    val outName = "${template.displayName}-${safeName}.docx"
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
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, contentDescription = "关于") }
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
    var editing by remember { mutableStateOf(false) }
    val tokens = remember(text) { ElementEngine.parseCheckboxTokens(text) }
    // 仅"先行调解"要素行（官方 roleTag）或显式开启时渲染一键快捷键，且常驻不折叠
    val showShortcut = showMediationShortcut || roleTag == "mediation_benefit"

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
