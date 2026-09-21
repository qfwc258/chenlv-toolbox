@file:OptIn(ExperimentalMaterial3Api::class)

package com.wb.mdgw

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.IntentSenderRequest
import androidx.core.content.FileProvider
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的文档：统一浏览「下载 / 陈律文档」下的全部产物（WORD / PPTX / PDF / 截图排版 / 生成文书）。
 * 支持打开、分享、删除；PDF 可一键带入「PDF 处理」加页码 / 盖章。
 */
data class DocItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val relPath: String
) {
    val isPdf: Boolean get() = name.lowercase().endsWith(".pdf")
}

/** 扫描「下载 / 陈律文档」目录（含案件子目录）。Q+ 走 MediaStore，低版本走 File。 */
fun scanDocuments(context: Context): List<DocItem> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scanViaMediaStore(context)
    else scanViaFile(context)

private fun mimeOf(name: String, reported: String?): String = when {
    !reported.isNullOrBlank() && reported != "application/octet-stream" && reported != "content/unknown" -> reported
    name.lowercase().endsWith(".pdf") -> FileUtils.PDF_MIME
    name.lowercase().endsWith(".docx") -> FileUtils.DOCX_MIME
    name.lowercase().endsWith(".doc") -> "application/msword"
    name.lowercase().endsWith(".pptx") ->
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    name.lowercase().endsWith(".ppt") -> "application/vnd.ms-powerpoint"
    name.lowercase().endsWith(".zip") -> "application/zip"
    name.lowercase().endsWith(".md") -> "text/markdown"
    name.lowercase().endsWith(".txt") -> "text/plain"
    else -> "*/*"
}

private fun scanViaMediaStore(context: Context): List<DocItem> {
    val out = mutableListOf<DocItem>()
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH
    )
    runCatching {
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("%陈律文档%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val uri = Uri.withAppendedPath(collection, id.toString())
                out.add(
                    DocItem(
                        uri = uri,
                        name = name,
                        mime = mimeOf(name, if (mimeCol >= 0) c.getString(mimeCol) else null),
                        size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        modified = if (dateCol >= 0) c.getLong(dateCol) * 1000L else 0L,
                        relPath = if (relCol >= 0) c.getString(relCol) ?: "" else ""
                    )
                )
            }
        }
    }
    return out
}

private fun scanViaFile(context: Context): List<DocItem> = runCatching {
    val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "陈律文档"
    )
    if (!root.exists()) return emptyList()
    val authority = "${context.packageName}.fileprovider"
    root.walkTopDown()
        .filter { it.isFile }
        .mapNotNull { f ->
            // 个别文件无法被 FileProvider 共享时跳过，避免整页扫描失败
            runCatching {
                DocItem(
                    uri = FileProvider.getUriForFile(context, authority, f),
                    name = f.name,
                    mime = mimeOf(f.name, null),
                    size = f.length(),
                    modified = f.lastModified(),
                    relPath = "Download/陈律文档/" + (f.parentFile?.relativeTo(root)?.path?.takeIf { it != "." }?.let { "$it/" } ?: "")
                )
            }.getOrNull()
        }
        .sortedByDescending { it.modified }
        .toList()
}.getOrDefault(emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    onOpenPdf: (Uri) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DocItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<DocItem?>(null) }
    var menuFor by remember { mutableStateOf<DocItem?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            val list = withContext(Dispatchers.IO) { scanDocuments(context) }
            items = list
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // 删除被系统拦截（非本应用创建的文件）时，发起系统授权；授权后重试删除
    var pendingDelete by remember { mutableStateOf<DocItem?>(null) }
    val deleteSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDelete?.let { item ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(item.uri, null, null) } }
                    reload()
                }
            }
        }
        pendingDelete = null
    }

    fun performDelete(item: DocItem) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.delete(item.uri, null, null) > 0
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val sender = (e as? RecoverableSecurityException)
                            ?.userAction?.actionIntent?.intentSender
                        if (sender != null) {
                            pendingDelete = item
                            deleteSenderLauncher.launch(
                                IntentSenderRequest.Builder(sender).build()
                            )
                        }
                    }
                    false
                }
            }
            if (ok) {
                snackbar.showSnackbar("已删除：${item.name}")
                reload()
            }
        }
    }

    fun openItem(item: DocItem) {
        runCatching {
            context.startActivity(FileUtils.openIntent(item.uri, item.mime))
        }.onFailure {
            scope.launch { snackbar.showSnackbar("无法打开：${it.message ?: "没有可用应用"}") }
        }
    }

    fun shareItem(item: DocItem) {
        runCatching {
            context.startActivity(FileUtils.shareIntent(item.uri, item.name, item.mime))
        }.onFailure {
            scope.launch { snackbar.showSnackbar("无法分享：${it.message ?: ""}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的文档", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        Modifier.size(40.dp).align(Alignment.Center), strokeWidth = 3.dp
                    )
                }
                items.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            null,
                            Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("暂无文档", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "生成文书、截图排版、WORD / PPTX 等产物都会保存在这里",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.uri.toString() }) { item ->
                            DocRow(
                                item = item,
                                onOpen = { openItem(item) },
                                onShare = { shareItem(item) },
                                onDelete = { confirmDelete = item },
                                onPdfProcess = { onOpenPdf(item.uri) },
                                menuExpanded = menuFor?.uri == item.uri,
                                onMenuToggle = { open ->
                                    menuFor = if (open) item else null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除文件") },
            text = { Text("确定删除「${item.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    performDelete(item)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DocRow(
    item: DocItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPdfProcess: () -> Unit,
    menuExpanded: Boolean,
    onMenuToggle: (Boolean) -> Unit
) {
    ElevatedCard(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                iconFor(item.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        if (item.size > 0) append(Formatter.formatShortFileSize(LocalContext.current, item.size))
                        if (item.modified > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.modified)))
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { onMenuToggle(true) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuToggle(false) }) {
                    DropdownMenuItem(
                        text = { Text("打开") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp)) },
                        onClick = { onMenuToggle(false); onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) },
                        onClick = { onMenuToggle(false); onShare() }
                    )
                    if (item.isPdf) {
                        DropdownMenuItem(
                            text = { Text("加页码 / 盖章") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp)) },
                            onClick = { onMenuToggle(false); onPdfProcess() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { onMenuToggle(false); onDelete() }
                    )
                }
            }
        }
    }
}

private fun iconFor(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        lower.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        lower.endsWith(".ppt") || lower.endsWith(".pptx") -> Icons.Default.Slideshow
        lower.endsWith(".zip") -> Icons.Default.Folder
        lower.endsWith(".doc") || lower.endsWith(".docx") -> Icons.Default.Article
        else -> Icons.Default.Description
    }
}
