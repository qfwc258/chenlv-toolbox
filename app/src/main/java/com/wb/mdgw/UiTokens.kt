package com.wb.mdgw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 全 App 统一的圆角与尺寸令牌，确保各 tab 视觉语言一致 */
val UI_SECTION_RADIUS = RoundedCornerShape(12.dp)
val UI_CARD_RADIUS = RoundedCornerShape(14.dp)
val UI_BTN_RADIUS = RoundedCornerShape(10.dp)
val UI_ACTION_HEIGHT = 46.dp

/**
 * 分段控件（胶囊背景 + 选中态主色填充 + 图标），用于「编辑/预览」「加页码/盖章」等二选一切换。
 * 视觉语言与 WORD tab 对齐。
 */
@Composable
fun SegmentedTabs(
    items: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = UI_SECTION_RADIUS,
        modifier = modifier
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            items.forEachIndexed { i, (label, icon) ->
                val selected = selectedIndex == i
                val cellMod = Modifier.weight(1f).height(40.dp).clickable { onSelect(i) }
                if (selected) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = UI_SECTION_RADIUS, modifier = cellMod) {
                        Row(
                            Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    Box(cellMod, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 统一的导出结果弹窗，用于 Word / PDF / PPTX 等模块。
 * 提供文件名预览、保存路径、打开与分享按钮，替代各模块中重复的 AlertDialog 实现。
 */
@Composable
fun ExportResultDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    fileName: String,
    savePath: String,
    fileIcon: ImageVector,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(30.dp)) },
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            Icon(fileIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(fileName, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("保存位置：$savePath", fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(UI_ACTION_HEIGHT), shape = UI_BTN_RADIUS) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("用其他应用打开", fontSize = 14.sp, maxLines = 1, softWrap = false)
                    }
                    Button(onClick = onShare, modifier = Modifier.fillMaxWidth().height(UI_ACTION_HEIGHT), shape = UI_BTN_RADIUS) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("分享文件", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}
