package com.wb.mdgw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
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

/** ===== 统一设计令牌：圆角阶梯（全 App 只允许这三档，消除 14 种散乱值）===== */
val UI_RADIUS_SM = RoundedCornerShape(8.dp)    // 小：标签、内嵌块、小芯片
val UI_RADIUS_MD = RoundedCornerShape(12.dp)   // 中：卡片、分区、按钮（主力）
val UI_RADIUS_LG = RoundedCornerShape(16.dp)   // 大：弹窗、大卡片
/** ===== 统一间距阶梯 ===== */
val UI_SPACE_XS = 4.dp
val UI_SPACE_SM = 8.dp
val UI_SPACE_MD = 12.dp
val UI_SPACE_LG = 16.dp
/** 兼容旧引用：统一指向中圆角，旧代码自动收敛 */
val UI_SECTION_RADIUS = UI_RADIUS_MD
val UI_CARD_RADIUS = UI_RADIUS_MD
val UI_BTN_RADIUS = UI_RADIUS_MD
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
 * 沉浸式切换条 —— 三个 Tab 唯一常驻的一行控件，最大化编辑 / 预览区。
 *
 * 布局：`[顶栏折叠开关] [编辑 | 预览 分段切换] [底栏折叠开关]`。
 * 顶 / 底工具栏默认收起，点两侧开关展开（展开后箭头方向反转）；中间分段切换编辑 / 预览。
 */
@Composable
fun EditPreviewBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    topExpanded: Boolean,
    onToggleTop: () -> Unit,
    bottomExpanded: Boolean,
    onToggleBottom: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHome, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Default.Home,
                contentDescription = "返回主页",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onToggleTop, modifier = Modifier.size(34.dp)) {
            Icon(
                if (topExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "编辑工具栏",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SegmentedTabs(
            items = listOf("编辑" to Icons.Default.Edit, "预览" to Icons.Default.Visibility),
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggleBottom, modifier = Modifier.size(34.dp)) {
            Icon(
                if (bottomExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = "操作栏",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    onShare: () -> Unit,
    extraActionText: String? = null,
    onExtraAction: (() -> Unit)? = null
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.CheckCircle, null, tint = BrandTokens.StatusSuccess, modifier = Modifier.size(30.dp)) },
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
                    if (extraActionText != null && onExtraAction != null) {
                        OutlinedButton(onClick = onExtraAction, modifier = Modifier.fillMaxWidth().height(UI_ACTION_HEIGHT), shape = UI_BTN_RADIUS) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(extraActionText, fontSize = 14.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}
