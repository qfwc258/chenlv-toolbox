package com.wb.mdgw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

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
