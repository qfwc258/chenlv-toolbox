package com.wb.mdgw.docgen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分组后的 UI 小节（title 为 null 表示首个分组之前的散字段）；每项携带全局行索引以保证 LazyColumn key 唯一 */
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocGenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var templates by remember { mutableStateOf(DocTemplateStore.loadMeta(context)) }
    var lines by remember { mutableStateOf(FieldRuleStore.loadOrDefault(context).lines) }
    var caseNumber by remember { mutableStateOf("1") }
    var selectedTypes by remember { mutableStateOf(emptySet<String>()) } // 空 = 全部
    var keyword by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<GenOutput?>(null) }

    fun persistLines(newLines: List<RuleLine>) {
        lines = newLines
        FieldRuleStore.save(context, FieldDoc(newLines, System.currentTimeMillis()))
    }

    fun updateValue(index: Int, value: String) {
        val newLines = lines.toMutableList()
        newLines[index] = newLines[index].copy(value = value)
        persistLines(newLines)
    }

    // 导入模板（多选）
    val templatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                uris.count { DocTemplateStore.import(context, it) != null }
            }
            templates = DocTemplateStore.loadMeta(context)
            val skipped = uris.size - ok
            snackbar.showSnackbar(
                if (skipped == 0) "已导入 $ok 个模板" else "导入 $ok 个，跳过 $skipped 个（仅支持 docx/pdf）"
            )
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

    // 导出规则文本
    val ruleExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(SharedTextParser.export(FieldDoc(lines)).toByteArray())
                    }
                }
            }
            snackbar.showSnackbar("已导出 shared_text.txt")
        }
    }

    fun doGenerate() {
        if (templates.isEmpty()) {
            scope.launch { snackbar.showSnackbar("请先导入文书模板（docx/pdf）") }
            return
        }
        scope.launch {
            busy = true
            val out = withContext(Dispatchers.IO) {
                DocGenEngine.generate(context, caseNumber, selectedTypes, FieldDoc(lines))
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
            // ---------- 模板管理 ----------
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("文书模板", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Button(onClick = {
                                templatePicker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/pdf"
                                    )
                                )
                            }) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("导入模板")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "选择 docx / pdf 模板，可多选；docx 会按下方规则替换占位符，pdf 原样复制。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (templates.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("尚未导入模板", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                        } else {
                            templates.forEach { t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
                                    IconButton(
                                        onClick = {
                                            DocTemplateStore.delete(context, t.id)
                                            templates = DocTemplateStore.loadMeta(context)
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 案件信息 / 类型 ----------
            item {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("案件信息", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = caseNumber,
                            onValueChange = { caseNumber = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("案件编号") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("文书类型（按文件名包含的类型码筛选）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedTypes.isEmpty(),
                                onClick = { selectedTypes = emptySet() },
                                label = { Text("全部") }
                            )
                            DocType.values().forEach { dt ->
                                FilterChip(
                                    selected = dt.code in selectedTypes,
                                    onClick = {
                                        selectedTypes = if (dt.code in selectedTypes)
                                            selectedTypes - dt.code
                                        else selectedTypes + dt.code
                                    },
                                    label = { Text("${dt.code} ${dt.label}") }
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 替换字段 ----------
            item {
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("替换字段", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                ruleImporter.launch(arrayOf("text/plain", "text/*", "*/*"))
                            }) { Icon(Icons.Default.FileUpload, null, Modifier.size(16.dp)); Spacer(Modifier.size(2.dp)); Text("导入", fontSize = 12.sp) }
                            Spacer(Modifier.size(6.dp))
                            OutlinedButton(onClick = { ruleExporter.launch("shared_text.txt") }) {
                                Icon(Icons.Default.FileDownload, null, Modifier.size(16.dp)); Spacer(Modifier.size(2.dp)); Text("导出", fontSize = 12.sp)
                            }
                        }
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
                        TextButton(onClick = {
                            persistLines(lines.map { if (it.isField) it.copy(value = "") else it })
                        }) { Text("一键清空所有填写值（保留字段结构）", fontSize = 12.sp) }
                    }
                }
            }

            // 字段编辑区
            val kw = keyword.trim()
            if (kw.isNotEmpty()) {
                val matched = lines.filter {
                    it.isField && (
                        it.key.contains(kw, true) ||
                        (FieldLabels.labelOf(it.key) ?: "").contains(kw) ||
                        it.value.contains(kw))
                }
                items(matched, key = { "search-" + it.key }) { line ->
                    val idx = lines.indexOf(line)
                    FieldInput(line) { v -> updateValue(idx, v) }
                }
            } else {
                val sections = buildSections(lines)
                sections.forEach { section ->
                    val header = section.title ?: "其他"
                    val isCollapsed = header in collapsed
                    item(key = "group-$header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                section.title ?: "其他字段",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                collapsed = if (isCollapsed) collapsed - header else collapsed + header
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                    if (!isCollapsed) {
                        section.items.forEach { (idx, line) ->
                            if (line.isComment) {
                                item(key = "comment-$idx") {
                                    Text(
                                        line.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                                    )
                                }
                            } else if (line.isField) {
                                item(key = "field-$idx-${line.key}") {
                                    FieldInput(line) { v -> updateValue(idx, v) }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldInput(line: RuleLine, onChange: (String) -> Unit) {
    val label = FieldLabels.labelOf(line.key) ?: line.key
    val isLong = FieldLabels.isLong(line.key)
    OutlinedTextField(
        value = line.value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        label = {
            Text(
                if (label == line.key) line.key else "$label（${line.key}）",
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = !isLong,
        minLines = if (isLong) 3 else 1,
        shape = RoundedCornerShape(10.dp)
    )
}
