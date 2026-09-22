package com.wb.mdgw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 相对时间：今天 HH:mm / 昨天 HH:mm / 今年 M月d日 / 往年 yyyy/M/d */
fun formatRelativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "今天 $hm"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "昨天 $hm"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(ts))
    }
}

private fun iconForFile(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> Icons.Default.PictureAsPdf
        n.endsWith(".md") || n.endsWith(".txt") -> Icons.Default.Description
        else -> Icons.Default.Article
    }
}

/**
 * 通用「最近打开」列表。空列表不渲染任何内容。
 *
 * 采用普通 [Column]，可直接放入可滚动容器或 LazyColumn 的 item 中；
 * 点击整行回调 [onOpen]，右侧 ⋮ 可移除（[onRemove]）。
 */
@Composable
fun RecentFilesSection(
    items: List<RecentFile>,
    onOpen: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "最近打开"
) {
    if (items.isEmpty()) return
    Column(modifier) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        items.forEach { item -> RecentFileRow(item, onOpen, onRemove) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentFileRow(
    item: RecentFile,
    onOpen: (RecentFile) -> Unit,
    onRemove: (RecentFile) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = { onOpen(item) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                iconForFile(item.name),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                val sub = listOf(item.subtitle, formatRelativeTime(item.openedAt))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        sub,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("移除记录") },
                    onClick = { menuOpen = false; onRemove(item) }
                )
            }
        }
    }
}
