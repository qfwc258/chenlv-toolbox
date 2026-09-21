package com.wb.mdgw.docgen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.FileUtils
import com.wb.mdgw.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分组后的 UI 小节（title 为 null 表示首个分组之前的散字段）；每项携带全局行索引以保证 key 唯一 */
private data class Section(val title: String?, val items: List<Pair<Int, RuleLine>>)

private fun buildSections(lines: List<RuleLine>): List<Section> {
    val result = mutableListOf<Section>()
    var items = mutableListOf<Pair<Int, RuleLine>>()
    var title: String? = null
    fun flush() {
        if (items.isNotEmpty()) result.add(Section(title, items))
        items = mutableListOf()
    }
    lines.forEachIndexed { idx, line ->
        when {
            line.isGroup -> { flush(); title = line.text }
            line.isField || line.isComment -> items.add(idx to line)
        }
    }
    flush()
    return result
}

private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9]*$")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocGenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var templateDir by remember { mutableStateOf(SettingsStore.docgenTemplateDir(context)) }
    var hasAccess by remember { mutableStateOf(DocTemplateDir.hasAccess(context)) }
    var templates by remember { mutableStateOf(emptyList<DocTemplateFile>()) }
    var lines by remember { mutableStateOf(FieldRuleStore.loadOrDefault(context).lines) }
    var typeList by remember { mutableStateOf(DocTypeStore.load(context)) }
    var selectedTypes by remember { mutableStateOf(SettingsStore.docgenSelectedTypes(context)) } // 空 = 全部
    var keyword by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<GenOutput?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showTypeManage by remember { mutableStateOf(false) }
    var addDialogGroup by remember { mutableStateOf<String?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var showTemplateList by remember { mutableStateOf(false) }
    var showTemplateCard by remember { mutableStateOf(false) }

    val defaults = remember { DefaultFields.lines(context) }
    val standardKeys = remember { DefaultFields.keys(context) }

    /** 保存类型列表，并清理已不存在的选中态 */
    fun persistTypeList(newList: List<DocTypeItem>) {
        typeList = newList
        DocTypeStore.save(context, newList)
        val valid = newList.map { it.code }.toSet()
        val cleaned = selectedTypes.filter { it in valid }.toSet()
        if (cleaned != selectedTypes) {
            selectedTypes = cleaned
            SettingsStore.saveDocgenSelectedTypes(context, cleaned)
        }
    }

    fun rescan() {
        hasAccess = DocTemplateDir.hasAccess(context)
        templates = if (hasAccess) DocTemplateDir.scan(templateDir) else emptyList()
        // 未授权 / 空目录时自动展开，引导用户处理；正常情况下保持折叠
        if (!hasAccess || templates.isEmpty()) showTemplateCard = true
    }

    LaunchedEffect(Unit) { rescan() }

    fun persistLines(newLines: List<RuleLine>) {
        lines = newLines
        FieldRuleStore.save(context, FieldDoc(newLines, System.currentTimeMillis()))
    }

    fun updateValue(index: Int, value: String) {
        val newLines = lines.toMutableList()
        newLines[index] = newLines[index].copy(value = value)
        persistLines(newLines)
    }

    fun deleteField(index: Int) {
        val newLines = lines.toMutableList()
        newLines.removeAt(index)
        persistLines(newLines)
    }

    fun addField(key: String, value: String, alias: String = "") {
        persistLines(DefaultFields.insertField(lines, key, value, defaults, alias))
    }

    fun updateField(index: Int, newKey: String, newValue: String, newAlias: String) {
        val old = lines.getOrNull(index) ?: return
        if (!old.isField) return
        val key = newKey.trim()
        if (!KEY_REGEX.matches(key)) return
        val duplicate = lines.withIndex().any { (i, l) -> i != index && l.isField && l.key == key }
        if (duplicate) return
        persistLines(lines.toMutableList().also {
            it[index] = old.copy(key = key, value = newValue, alias = newAlias.trim())
        })
    }

    // Android 11+：跳「所有文件访问」设置页，返回后重扫
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { rescan() }

    // Android 10 及以下：申请读存储权限
    val readPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { rescan() }

    fun requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:" + context.packageName)
            }
            runCatching { manageStorageLauncher.launch(intent) }.onFailure {
                manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            readPermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // 导入规则文本
    val ruleImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { FileUtils.readText(context, uri) }
            if (text != null) {
                persistLines(SharedTextParser.parse(text))
                snackbar.showSnackbar("已导入替换规则")
            } else {
                snackbar.showSnackbar("读取规则文件失败")
            }
        }
    }

    // 导出规则文本：直接写入案件文件夹（与生成文书同目录，目录名由 委托人_阶段 自动生成）
    fun exportRules() {
        val caseDir = DocGenEngine.caseDirName(FieldDoc(lines))
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = SharedTextParser.export(FieldDoc(lines)).toByteArray(Charsets.UTF_8)
                    FileUtils.saveToDownloadsInDir(context, caseDir, "shared_text.txt", bytes, "text/plain")
                }.getOrNull()
            }
            if (saved != null) {
                snackbar.showSnackbar("已导出：下载/陈律文档/$caseDir/shared_text.txt")
            } else {
                snackbar.showSnackbar("导出失败，请检查存储权限")
            }
        }
    }

    fun doGenerate() {
        if (!hasAccess) {
            scope.launch { snackbar.showSnackbar("请先授予存储权限以读取模板目录") }
            return
        }
        val tpl = DocTemplateDir.scan(templateDir)
        if (tpl.isEmpty()) {
            scope.launch { snackbar.showSnackbar("模板目录为空或不存在：$templateDir") }
            return
        }
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                DocGenEngine.generate(context, selectedTypes, FieldDoc(lines), tpl)
            }
            busy = false
            if (out.results.isEmpty()) {
                snackbar.showSnackbar("没有符合所选类型的模板")
            } else {
                output = out
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("生成文书", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { doGenerate() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("正在生成…")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("生成文书", fontSize = 16.sp)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 模板目录（默认折叠为一行状态条） ----------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        // 折叠状态条：图标 + 标题 + 路径/数量 + 扫描 + 展开
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("文书模板", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    when {
                                        !hasAccess -> "未授权 · 展开授权"
                                        templates.isEmpty() -> "目录为空或不存在"
                                        else -> "$templateDir · ${templates.size} 个"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    SettingsStore.saveDocgenTemplateDir(context, templateDir)
                                    rescan()
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.size(3.dp))
                                Text("扫描", fontSize = 12.sp)
                            }
                            IconButton(onClick = { showTemplateCard = !showTemplateCard }) {
                                Icon(
                                    if (showTemplateCard) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showTemplateCard) "折叠模板设置" else "展开模板设置"
                                )
                            }
                        }

                        // 展开区：路径 / 授权 / 模板列表
                        AnimatedVisibility(visible = showTemplateCard) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = templateDir,
                                    onValueChange = { templateDir = it },
                                    label = { Text("模板目录路径") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "默认 /sdcard/pylaw/mb，把 docx/pdf 模板放进该目录；docx 按规则替换占位符，pdf 原样复制。",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (!hasAccess) {
                                    Spacer(Modifier.height(10.dp))
                                    Button(onClick = { requestStorageAccess() }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.size(6.dp))
                                        Text("授予存储权限（所有文件访问）")
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "需授权后才能读取 /sdcard 下的模板目录。",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                                    )
                                } else if (templates.isEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "目录为空或不存在：$templateDir（修改路径后点「扫描」）",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                                    )
                                } else {
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "已扫描到 ${templates.size} 个模板",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { showTemplateList = !showTemplateList },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Text(if (showTemplateList) "收起列表" else "查看列表", fontSize = 12.sp)
                                            Icon(
                                                if (showTemplateList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    AnimatedVisibility(visible = showTemplateList) {
                                        Column {
                                            templates.forEach { t ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        if (t.isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        t.fileName,
                                                        fontSize = 13.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
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

            // ---------- 文书类型（可增删、重命名） ----------
            item {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("文书类型", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = { showTypeManage = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("管理", fontSize = 12.sp)
                            }
                        }
                        Text(
                            "按文件名包含的类型码筛选；归档目录自动为「案件_委托人_阶段」",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TypeChip(
                                selected = selectedTypes.isEmpty(),
                                label = "全部",
                                onClick = {
                                    selectedTypes = emptySet()
                                    SettingsStore.saveDocgenSelectedTypes(context, selectedTypes)
                                }
                            )
                            typeList.forEach { dt ->
                                TypeChip(
                                    selected = dt.code in selectedTypes,
                                    label = "${dt.code} ${dt.label}",
                                    onClick = {
                                        selectedTypes = if (dt.code in selectedTypes)
                                            selectedTypes - dt.code
                                        else selectedTypes + dt.code
                                        SettingsStore.saveDocgenSelectedTypes(context, selectedTypes)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 主要字段（工具条 + 分组字段合并为一个卡片） ----------
            item {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        // 标题行
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("主要字段", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { ruleImporter.launch(arrayOf("text/plain", "text/*", "*/*")) }) {
                                Icon(Icons.Default.FileUpload, contentDescription = "导入规则", modifier = Modifier.size(19.dp))
                            }
                            IconButton(onClick = { exportRules() }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "导出规则到案件文件夹", modifier = Modifier.size(19.dp))
                            }
                            IconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) keyword = ""
                            }) {
                                Icon(
                                    if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (showSearch) "关闭搜索" else "搜索字段",
                                    modifier = Modifier.size(19.dp),
                                    tint = if (showSearch) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                var menuOpen by remember { mutableStateOf(false) }
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("清空所有值") },
                                        onClick = {
                                            menuOpen = false
                                            persistLines(lines.map { if (it.isField) it.copy(value = "") else it })
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重置为默认结构") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                val doc = withContext(Dispatchers.IO) { FieldRuleStore.resetOrDefault(context) }
                                                lines = doc.lines
                                                collapsed = emptySet()
                                                snackbar.showSnackbar("已重置为默认结构")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(visible = showSearch) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = keyword,
                                    onValueChange = { keyword = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("搜索字段 / 标签 / 内容", fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }

                        val kw = keyword.trim()
                        if (kw.isNotEmpty()) {
                            // 搜索结果（扁平）
                            val matched = lines.filter {
                                it.isField && (
                                    it.key.contains(kw, true) ||
                                    it.alias.contains(kw) ||
                                    (FieldLabels.labelOf(it.key) ?: "").contains(kw) ||
                                    it.value.contains(kw))
                            }
                            if (matched.isEmpty()) {
                                Text("没有匹配的字段", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(8.dp))
                            }
                            matched.forEach { line ->
                                val idx = lines.indexOf(line)
                                FieldRow(
                                    line = line,
                                    onChange = { v -> updateValue(idx, v) },
                                    onEdit = { editIndex = idx },
                                    onDelete = { deleteField(idx) }
                                )
                            }
                        } else {
                            // 分组字段
                            buildSections(lines).forEach { section ->
                                val header = section.title ?: "其他字段"
                                val isCollapsed = header in collapsed
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        collapsed = if (isCollapsed) collapsed - header else collapsed + header
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                            contentDescription = null
                                        )
                                    }
                                    Text(
                                        section.title ?: "其他字段",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        addDialogGroup = section.title
                                        showAddDialog = true
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Add, contentDescription = "在该分组添加字段", modifier = Modifier.size(18.dp))
                                    }
                                }
                                AnimatedVisibility(visible = !isCollapsed) {
                                    Column {
                                        section.items.forEach { (idx, line) ->
                                            if (line.isComment) {
                                                Text(
                                                    line.text,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                                )
                                            } else if (line.isField) {
                                                FieldRow(
                                                    line = line,
                                                    onChange = { v -> updateValue(idx, v) },
                                                    onEdit = { editIndex = idx },
                                                    onDelete = { deleteField(idx) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 底部全宽添加按钮
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { addDialogGroup = null; showAddDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("添加字段")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // ---------- 文书类型管理对话框 ----------
    if (showTypeManage) {
        TypeManageDialog(
            initial = typeList,
            onDismiss = { showTypeManage = false },
            onSave = { persistTypeList(it) }
        )
    }

    // ---------- 添加字段对话框 ----------
    if (showAddDialog) {
        AddFieldDialog(
            templates = templates,
            defaults = defaults,
            standardKeys = standardKeys,
            existingKeys = lines.filter { it.isField }.map { it.key }.toSet(),
            initialGroup = addDialogGroup,
            onDismiss = { showAddDialog = false },
            onAdd = { key, value, alias ->
                addField(key, value, alias)
                scope.launch { snackbar.showSnackbar("已添加字段 $key") }
            }
        )
    }

    // ---------- 编辑字段对话框 ----------
    if (editIndex >= 0 && editIndex < lines.size) {
        val line = lines[editIndex]
        if (line.isField) {
            EditFieldDialog(
                line = line,
                originalIsStandard = line.key in standardKeys,
                isKeyDuplicate = { k -> lines.any { it.isField && it.key == k } },
                onDismiss = { editIndex = -1 },
                onSave = { k, v, a ->
                    updateField(editIndex, k, v, a)
                    editIndex = -1
                    scope.launch { snackbar.showSnackbar("已保存字段 $k") }
                }
            )
        }
    }

    // ---------- 结果弹窗 ----------
    output?.let { out ->
        val failed = out.results.filter { !it.success }
        AlertDialog(
            onDismissRequest = { output = null },
            title = { Text("生成完成") },
            text = {
                Column {
                    Text("成功 ${out.successCount} 个，失败 ${failed.size} 个", fontSize = 14.sp)
                    Text("保存位置：下载 / 陈律文档 / ${out.caseDir}/", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (out.sharedTextPath != null) {
                        Text("已归档 shared_text.txt 到该文件夹", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (failed.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("失败项：", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        failed.take(8).forEach {
                            Text("· ${it.templateName}：${it.error}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                if (out.zipUri != null) {
                    Button(onClick = {
                        val intent = FileUtils.shareIntent(out.zipUri, out.zipName, "application/zip")
                        context.startActivity(Intent.createChooser(intent, "分享 ${out.zipName}"))
                    }) { Text("分享全部 ZIP") }
                } else {
                    TextButton(onClick = { output = null }) { Text("关闭") }
                }
            },
            dismissButton = {
                if (out.zipUri != null) {
                    TextButton(onClick = { output = null }) { Text("关闭") }
                }
            }
        )
    }
}

/** 实心分段按钮：选中=主题色实底白字+✓，未选=浅底描边（比 FilterChip 更醒目） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
            }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 文书类型管理：增、删、改 类型码 / 显示名 */
@Composable
private fun TypeManageDialog(
    initial: List<DocTypeItem>,
    onDismiss: () -> Unit,
    onSave: (List<DocTypeItem>) -> Unit
) {
    var items by remember { mutableStateOf(initial) }
    var newCode by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val codeFilter = remember { Regex("[^A-Za-z0-9]") }

    fun addType() {
        val code = newCode.trim()
        val label = newLabel.trim()
        if (code.isEmpty() || label.isEmpty()) {
            error = "类型码和显示名都不能为空"
            return
        }
        if (items.any { it.code.equals(code, ignoreCase = true) }) {
            error = "类型码 $code 已存在"
            return
        }
        items = items + DocTypeItem(code, label)
        newCode = ""
        newLabel = ""
        error = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理文书类型") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "类型码对应文件名中的数字/字母，生成时按「文件名包含该码」筛选",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                items.forEachIndexed { idx, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = item.code,
                            onValueChange = { v ->
                                val c = codeFilter.replace(v, "")
                                items = items.toMutableList().also { it[idx] = item.copy(code = c) }
                            },
                            label = { Text("码", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.width(78.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = item.label,
                            onValueChange = { v ->
                                items = items.toMutableList().also { it[idx] = item.copy(label = v) }
                            },
                            label = { Text("显示名", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { items = items.filterIndexed { i, _ -> i != idx } }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除类型", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("新增类型", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCode,
                        onValueChange = { newCode = codeFilter.replace(it, "") },
                        label = { Text("码", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.width(78.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("显示名", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { addType() }) {
                        Icon(Icons.Default.Add, contentDescription = "添加类型")
                    }
                }
                if (error.isNotEmpty()) {
                    Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val valid = items.filter { it.code.isNotBlank() && it.label.isNotBlank() }
                val codes = valid.map { it.code.lowercase() }
                error = when {
                    valid.isEmpty() -> "至少保留一个类型"
                    codes.size != codes.distinct().size -> "存在重复的类型码"
                    else -> ""
                }
                if (error.isEmpty()) {
                    onSave(valid)
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 字段行：输入框 + 编辑 + 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldRow(
    line: RuleLine,
    onChange: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) { FieldInput(line, onChange) }
        IconButton(onClick = onEdit, modifier = Modifier.padding(top = 10.dp).size(38.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "编辑字段", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.padding(top = 10.dp).size(38.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "删除字段", modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(line: RuleLine, onChange: (String) -> Unit) {
    val display = FieldLabels.displayName(line.key, line.alias)
    val isLong = FieldLabels.isLong(line.key)
    OutlinedTextField(
        value = line.value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        label = {
            Text(
                if (display == line.key) line.key else "$display（${line.key}）",
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = !isLong,
        minLines = if (isLong) 3 else 1,
        shape = RoundedCornerShape(10.dp)
    )
}

// ==================== 添加字段对话框 ====================

private data class DefaultGroup(val title: String, val fields: List<RuleLine>)
private data class DefaultFieldItem(val group: String?, val key: String, val label: String)

@Composable
private fun AddFieldDialog(
    templates: List<DocTemplateFile>,
    defaults: List<RuleLine>,
    standardKeys: List<String>,
    existingKeys: Set<String>,
    initialGroup: String?,
    onDismiss: () -> Unit,
    onAdd: (key: String, value: String, alias: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf<DocxPlaceholders.ScanResult?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var customKey by remember { mutableStateOf("") }
    var customAlias by remember { mutableStateOf("") }
    var customValue by remember { mutableStateOf("") }

    // 打开即扫描模板（IO）
    LaunchedEffect(Unit) {
        scan = withContext(Dispatchers.IO) {
            val docxBytes = templates.filter { it.isDocx }.mapNotNull { it.readBytes() }
            DocxPlaceholders.scan(docxBytes, standardKeys)
        }
    }

    // 默认字段分组
    val groups = remember(defaults) {
        val result = mutableListOf<DefaultGroup>()
        var title: String? = null
        var cur = mutableListOf<RuleLine>()
        fun flush() {
            if (cur.isNotEmpty()) result.add(DefaultGroup(title ?: "其他字段", cur))
            cur = mutableListOf()
        }
        defaults.forEach { l ->
            when {
                l.isGroup -> { flush(); title = l.text }
                l.isField -> cur.add(l)
            }
        }
        flush()
        result
    }
    val allItems = remember(defaults) {
        val list = mutableListOf<DefaultFieldItem>()
        var g: String? = null
        defaults.forEach { l ->
            when {
                l.isGroup -> g = l.text
                l.isField -> list.add(DefaultFieldItem(g, l.key, FieldLabels.displayName(l.key, l.alias)))
            }
        }
        list
    }

    var expandedGroups by remember {
        mutableStateOf(buildSet {
            add("委托授权"); add("授权")
            if (initialGroup != null) add(initialGroup)
        })
    }

    val q = query.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加字段") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索标签 / key / 分类", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    if (q.isNotEmpty()) {
                        // ---- 搜索：跨来源扁平化 ----
                        val matched = allItems.filter {
                            it.key.contains(q, true) || it.label.contains(q, true) ||
                                (it.group?.contains(q, true) == true)
                        }
                        val unknownMatched = (scan?.unknown ?: emptyList())
                            .filter { it.contains(q, true) && it !in matched.map { m -> m.key } }
                        items(matched, key = { "s-" + it.key }) { item ->
                            PickerRow(
                                label = item.label,
                                key = item.key,
                                subtitle = item.group ?: "",
                                exists = item.key in existingKeys,
                                onAdd = { onAdd(item.key, "", "") }
                            )
                        }
                        items(unknownMatched, key = { "su-" + it }) { k ->
                            PickerRow(
                                label = k, key = k, subtitle = "疑似模板占位符",
                                exists = k in existingKeys,
                                onAdd = { onAdd(k, "", "") }
                            )
                        }
                        if (matched.isEmpty() && unknownMatched.isEmpty()) {
                            item { Text("没有匹配的字段", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
                        }
                    } else {
                        // ---- ① 模板需要 ----
                        item {
                            Text("模板需要（扫描自模板，未添加）",
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                        }
                        val needStandard = scan?.standard?.filter { it !in existingKeys } ?: emptyList()
                        val needUnknown = scan?.unknown?.filter { it !in existingKeys } ?: emptyList()
                        if (scan == null) {
                            item {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                    Text("正在扫描模板…", fontSize = 12.sp)
                                }
                            }
                        } else if (needStandard.isEmpty() && needUnknown.isEmpty()) {
                            item {
                                Text("模板需要的字段都已添加（或未在模板中扫到占位符）",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(8.dp))
                            }
                        } else {
                            items(needStandard, key = { "t-" + it }) { k ->
                                val item = allItems.firstOrNull { it.key == k }
                                PickerRow(
                                    label = item?.label ?: k,
                                    key = k,
                                    subtitle = item?.group ?: "标准字段",
                                    exists = false,
                                    onAdd = { onAdd(k, "", "") }
                                )
                            }
                            items(needUnknown, key = { "u-" + it }) { k ->
                                PickerRow(
                                    label = k, key = k, subtitle = "疑似自定义占位符",
                                    exists = false,
                                    onAdd = { onAdd(k, "", "") }
                                )
                            }
                        }

                        // ---- ② 标准字段（按分类） ----
                        item {
                            Text("标准字段（按分类）",
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        groups.forEach { g ->
                            item(key = "gh-" + g.title) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        expandedGroups = if (g.title in expandedGroups)
                                            expandedGroups - g.title else expandedGroups + g.title
                                    }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (g.title in expandedGroups) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null, Modifier.size(18.dp)
                                    )
                                    Text(g.title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                            if (g.title in expandedGroups) {
                                items(g.fields, key = { "g-" + g.title + "-" + it.key }) { f ->
                                    PickerRow(
                                        label = FieldLabels.displayName(f.key, f.alias),
                                        key = f.key,
                                        subtitle = null,
                                        exists = f.key in existingKeys,
                                        onAdd = { onAdd(f.key, "", "") }
                                    )
                                }
                            }
                        }

                        // ---- ③ 自定义（手动） ----
                        item(key = "custom-head") {
                            TextButton(onClick = { showCustom = !showCustom },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text(if (showCustom) "收起自定义字段" else "自定义字段（手动输入 key）")
                            }
                        }
                        if (showCustom) {
                            item(key = "custom-body") {
                                Column {
                                    val keyValid = KEY_REGEX.matches(customKey.trim())
                                    val keyExists = customKey.trim() in existingKeys
                                    OutlinedTextField(
                                        value = customKey, onValueChange = { customKey = it },
                                        label = { Text("字段名 key（字母开头）") },
                                        singleLine = true,
                                        isError = customKey.isNotEmpty() && (!keyValid || keyExists),
                                        supportingText = {
                                            when {
                                                customKey.isNotBlank() && keyExists -> Text("该字段已存在")
                                                customKey.isNotBlank() && !keyValid -> Text("需字母开头、仅字母数字，如 zdy1")
                                                else -> Text("模板里没有、标准字段里也没有时使用")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customAlias, onValueChange = { customAlias = it },
                                        label = { Text("显示名（中文备注，可选）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customValue, onValueChange = { customValue = it },
                                        label = { Text("初始值（可留空）") },
                                        minLines = 2,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        enabled = keyValid && !keyExists,
                                        onClick = {
                                            onAdd(customKey.trim(), customValue, customAlias.trim())
                                            customKey = ""; customAlias = ""; customValue = ""
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("添加自定义字段") }
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

/** 添加弹窗里的一行：名称 + key + 已添加/＋ */
@Composable
private fun PickerRow(
    label: String,
    key: String,
    subtitle: String?,
    exists: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (label == key) key else "$label（$key）",
                fontSize = 13.sp,
                color = if (exists) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (exists) {
            Text("已添加", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        } else {
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, contentDescription = "添加 $key", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ==================== 编辑字段对话框 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFieldDialog(
    line: RuleLine,
    originalIsStandard: Boolean,
    isKeyDuplicate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String, alias: String) -> Unit
) {
    var key by remember { mutableStateOf(line.key) }
    var value by remember { mutableStateOf(line.value) }
    var alias by remember { mutableStateOf(line.alias) }

    val keyValid = KEY_REGEX.matches(key.trim())
    val keyChanged = key.trim() != line.key
    val dup = keyChanged && isKeyDuplicate(key.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑字段") },
        text = {
            Column {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("字段名 key") },
                    singleLine = true,
                    isError = key.isNotEmpty() && (!keyValid || dup),
                    supportingText = {
                        when {
                            key.isNotBlank() && dup -> Text("该 key 已存在")
                            key.isNotBlank() && !keyValid -> Text("需字母开头、仅字母数字")
                            else -> Text("修改 key 会影响模板占位符匹配")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("显示名（中文备注，可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text("字段值") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                if (originalIsStandard && keyChanged) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "提示：这是标准字段，改 key 后将按自定义字段处理，模板里的原占位符将不再匹配。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = keyValid && !dup, onClick = { onSave(key.trim(), value, alias.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
