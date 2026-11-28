package com.wb.mdgw.notes

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BrandTokens
import com.wb.mdgw.UI_RADIUS_MD
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 便签模块：保存常用内容，支持新增 / 修改 / 删除 / 置顶 / 搜索。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var notes by remember { mutableStateOf(NoteStore.load(context)) }
    var keyword by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StickyNote?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<StickyNote?>(null) }

    fun persist(list: List<StickyNote>) {
        notes = list
        NoteStore.save(context, list)
    }

    val filtered = remember(notes, keyword) {
        if (keyword.isBlank()) notes
        else notes.filter { it.title.contains(keyword, true) || it.content.contains(keyword, true) }
    }

    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = { Text("便签", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    Text("${notes.size} 条", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = NoteStore.newNote()
                isNew = true
            }) { Icon(Icons.Default.Add, contentDescription = "新建便签") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text("搜索便签（标题或内容）", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = UI_RADIUS_MD
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (keyword.isNotBlank()) "没有匹配的便签" else "还没有便签，点右下角 + 新建",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp
                ) {
                    items(filtered, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { editing = note.copy(); isNew = false },
                            onTogglePin = {
                                persist(notes.map { if (it.id == note.id) it.copy(pinned = !it.pinned) else it })
                            },
                            onDelete = { confirmDelete = note }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    editing?.let { note ->
        NoteEditDialog(
            note = note,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { title, content ->
                val trimmedTitle = title.trim().ifBlank { "未命名便签" }
                val updated = note.copy(title = trimmedTitle, content = content, updatedAt = System.currentTimeMillis())
                persist(if (isNew) listOf(updated) + notes else notes.map { if (it.id == note.id) updated else it })
                editing = null
                Toast.makeText(context, if (isNew) "便签已新建" else "便签已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    confirmDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除便签") },
            text = { Text("确定删除「${note.title}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    persist(notes.filter { it.id != note.id })
                    confirmDelete = null
                    Toast.makeText(context, "便签已删除", Toast.LENGTH_SHORT).show()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun NoteCard(
    note: StickyNote,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onTogglePin, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (note.pinned) "取消置顶" else "置顶",
                    tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        if (note.content.isNotBlank()) {
            Text(
                note.content, fontSize = 11.sp,
                maxLines = 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            if (note.updatedAt > 0) {
                Text(dateFormat.format(Date(note.updatedAt)), fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun NoteEditDialog(
    note: StickyNote,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(note.title) }
    var content by remember { mutableStateOf(note.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建便签" else "编辑便签") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("标题", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = UI_RADIUS_MD
                )
                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    label = { Text("内容", fontSize = 11.sp) },
                    minLines = 6, modifier = Modifier.fillMaxWidth(), shape = UI_RADIUS_MD
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(title, content) }) { Text("保存") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}
