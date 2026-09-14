package com.wb.mdgw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 「WORD」Tab 的全部弹窗。
//
// 原先全部内联在 WordScreen 主函数里（单函数 1000+ 行），既难定位
// 也难复用。这里按职责拆成独立 Composable：状态仍由 WordScreen 持有，
// 弹窗只负责渲染 + 回调，便于单独阅读与回归。
// ============================================================

/** 导出命名：docx / pdf / md / txt / 自定义后缀共用一套输入框。 */
@Composable
internal fun ExportNameDialog(
    kind: String,
    name: String,
    suffix: String,
    onNameChange: (String) -> Unit,
    onSuffixChange: (String) -> Unit,
    onConfirm: (kind: String, name: String, suffix: String) -> Unit,
    onDismiss: () -> Unit
) {
    val isCustom = kind == "custom"
    val ext = when (kind) {
        "pdf" -> "pdf"
        "docx" -> "docx"
        "md" -> "md"
        "txt" -> "txt"
        else -> suffix.trim().trimStart('.').ifBlank { "txt" }
    }
    val dialogTitle = when (kind) {
        "pdf" -> "导出为 PDF"
        "docx" -> "导出为 Word"
        "md" -> "导出为 Markdown"
        "txt" -> "导出为 纯文本"
        else -> "自定义导出"
    }
    val dialogIcon = when (kind) {
        "pdf" -> Icons.Default.PictureAsPdf
        "docx" -> Icons.Default.Description
        else -> Icons.Default.Article
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(dialogIcon, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(dialogTitle, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = onNameChange, label = { Text("文件名") }, singleLine = true,
                    suffix = { Text(".$ext", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                if (isCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = suffix, onValueChange = onSuffixChange, label = { Text("自定义后缀（不含点）") }, singleLine = true,
                        suffix = { Text(".xxx", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (kind == "md" || kind == "txt" || isCustom) "导出源 Markdown 文本到系统「下载」文件夹，可随时在结果弹窗中打开或分享"
                    else "将保存到系统「下载」文件夹，可随时在结果弹窗中打开或分享",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(kind, name, suffix) }) { Text("导出") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 模板列表：点击应用、右侧编辑/删除，底部「存为模板 / 新建空白」。 */
@Composable
internal fun TemplateListDialog(
    templates: List<WordTemplate>,
    onApply: (WordTemplate) -> Unit,
    onEdit: (WordTemplate) -> Unit,
    onDelete: (WordTemplate) -> Unit,
    onSaveCurrent: () -> Unit,
    onNewBlank: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("我的模板", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (templates.isEmpty()) {
                    Text("暂无模板。可从当前文档「存为模板」，或「新建空白」模板。", fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    LazyColumn(Modifier.height(260.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(templates, key = { it.id }) { t ->
                            Surface(
                                onClick = { onApply(t) },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val previewLine = t.content.lineSequence().firstOrNull { it.isNotBlank() }
                                        Text(previewLine ?: "（空模板）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                        Text("." + t.ext, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                    IconButton(onClick = { onEdit(t) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Edit, "编辑模板", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { onDelete(t) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Delete, "删除模板", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("点击模板即插入到当前光标处", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSaveCurrent) { Text("存为模板") }
                TextButton(onClick = onNewBlank) { Text("新建空白") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 模板新建 / 编辑：名称 + 类型（md / txt）+ 内容。 */
@Composable
internal fun TemplateEditDialog(
    editing: WordTemplate?,
    name: String,
    content: String,
    ext: String,
    onNameChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onExtChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(if (editing == null) "新建模板" else "编辑模板", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = onNameChange, label = { Text("模板名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("md", "txt").forEach { e ->
                        Surface(
                            onClick = { onExtChange(e) },
                            color = if (ext == e) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                                Text(".$e", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (ext == e) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("模板类型", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = content, onValueChange = onContentChange, label = { Text("模板内容") },
                    modifier = Modifier.fillMaxWidth().height(200.dp), textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 保存命名：Word 文档源只能另存 .docx，Markdown 源可写回原文件。 */
@Composable
internal fun SaveNameDialog(
    name: String,
    isDocxSource: Boolean,
    hasOriginalUri: Boolean,
    onNameChange: (String) -> Unit,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("保存文件", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = onNameChange, label = { Text("文件名") }, singleLine = true,
                    suffix = { Text(if (isDocxSource) ".docx" else ".md", fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isDocxSource) "当前为 Word 文档，编辑后将另存为新的 .docx（原文件无法被覆盖写回）"
                    else if (hasOriginalUri) "保持原名将直接写回打开的文件；改名则另存为副本"
                    else "将保存到「陈律文档」文件夹",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 源 Markdown 草稿恢复（上次退出前自动保存的正文）。 */
@Composable
internal fun MarkdownDraftRestoreDialog(
    draft: DraftStore.MdDraft,
    onRestore: (DraftStore.MdDraft) -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("发现未保存的草稿", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(draft.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val firstLine = draft.text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                Text(if (firstLine.length > 60) firstLine.take(60) + "…" else firstLine.ifBlank { "（空草稿）" },
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("上次编辑内容已在本地自动保存，是否恢复？", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onRestore(draft) }) { Text("恢复草稿") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("丢弃") } }
    )
}

/** 公文草稿恢复（含标题与首块预览）。 */
@Composable
internal fun GovDraftRestoreDialog(
    draft: GovDoc,
    onRestore: (GovDoc) -> Unit,
    onDiscard: () -> Unit
) {
    val preview = draft.blocks.firstOrNull().let { b ->
        when (b) {
            is Block.Para -> b.runs.joinToString("") { it.text }
            is Block.Table -> "表格（${b.rows.size} 行）"
            null -> ""
        }
    }.let { if (it.length > 40) it.take(40) + "…" else it }
    AlertDialog(
        onDismissRequest = onDiscard,
        icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("恢复上次未保存的公文草稿？", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("标题：${draft.title.ifBlank { "（未命名）" }}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(if (preview.isNotBlank()) preview else "（空草稿）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("检测到上次退出前自动保存的公文草稿，可一键恢复继续编辑", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onRestore(draft) }) { Text("恢复草稿") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("丢弃") } }
    )
}

/**
 * 就地编辑：整段改写 / 按字段拆分两种模式。
 * 带格式（下划线 / 粗 / 斜）的 run 在整段模式下保持原样，避免下划线丢失。
 */
@Composable
internal fun InPlaceEditDialog(
    target: EditTarget,
    runs: List<TextRun>,
    onApplySingle: (String) -> Unit,
    onApplyGroups: (List<String>) -> Unit,
    onOpenFindReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(target) { groupRuns(runs) }
    val hasFormat = runs.any { it.bold || it.italic || it.underline }
    var splitMode by remember(target) { mutableStateOf(false) }
    var fullText by remember(target) { mutableStateOf(runs.joinToString("") { it.text }) }
    var groupTexts by remember(target) { mutableStateOf(groups.map { it.text }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (target.row >= 0) "编辑单元格" else "编辑文字", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        FmtChip("全局查找替换", false, onOpenFindReplace)
                    }
                    Text("提示：修改文字后点「保存」即可写入并刷新预览。", fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
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
        confirmButton = {
            Button(onClick = { if (splitMode) onApplyGroups(groupTexts) else onApplySingle(fullText) }, shape = UI_BTN_RADIUS) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 全局查找替换：对全文（含表格）生效。 */
@Composable
internal fun FindReplaceDialog(
    findText: String,
    replaceText: String,
    onFindChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onReplaceAll: (find: String, replace: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全局查找替换", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = findText, onValueChange = onFindChange,
                    label = { Text("查找") }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                OutlinedTextField(value = replaceText, onValueChange = onReplaceChange,
                    label = { Text("替换为") }, singleLine = true, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
                Text("替换对全文生效（含表格），每个 run 内独立匹配。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            // 替换计数与 Snackbar 提示由调用方处理（它持有 SnackbarHostState）
            Button(onClick = { onReplaceAll(findText, replaceText) }) { Text("全部替换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============================================================
// 弹窗内用到的小组件（原先在 WordScreen.kt 内，随弹窗一起迁出）
// ============================================================

@Composable
internal fun StyleTag(label: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 可点选小标签（如「全局查找替换」入口）：点击回调 [onClick] */
@Composable
internal fun FmtChip(text: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
    ) {
        Text(
            text, fontSize = 12.sp, color = fg,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
