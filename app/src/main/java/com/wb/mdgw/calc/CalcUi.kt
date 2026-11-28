package com.wb.mdgw.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BrandTokens
import com.wb.mdgw.FileUtils
import com.wb.mdgw.UI_RADIUS_MD
import java.nio.charset.Charset

/** 计算类工具通用 UI 组件：暖纸色卡片、数字输入行、结果卡、复制/分享 */

@Composable
fun CalcCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = UI_RADIUS_MD,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
fun CalcNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String = "",
    placeholder: String = "",
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        suffix = if (unit.isBlank()) null else { { Text(unit, fontSize = 12.sp) } },
        placeholder = if (placeholder.isBlank()) null else { { Text(placeholder, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun CalcSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, sub: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun CalcResultHeadline(label: String, value: String, sub: String = "") {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF5EFE3), UI_RADIUS_MD).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF8A6D3B))
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B4E1F))
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, fontSize = 11.sp, color = Color(0xFF9A7B4F))
        }
    }
}

@Composable
fun CalcStepList(steps: List<String>) {
    steps.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("· ", fontSize = 12.sp, color = Color(0xFF9A7B4F))
            Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun CalcCopyShareBar(text: String, fileName: String, mimeType: String = "text/plain") {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { copyText(context, text); Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show() },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("复制清单")
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val saved = FileUtils.saveToDownloads(context, safe, text.toByteArray(Charset.forName("UTF-8")), mimeType)
                    context.startActivity(FileUtils.shareIntent(saved.uri, safe, mimeType))
                }.onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("保存/分享")
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("计算结果", text))
}

/** 通用计算页滚动容器 */
@Composable
fun CalcScrollBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

/**
 * 二选一分段控件：胶囊底 + 选中格主色实心填充，与全局 SegmentedTabs 视觉语言一致。
 * 两个格子始终可点击，选中态清晰高亮，避免出现「选中的反而灰掉、焦点不明」的问题。
 *
 * 尺寸约定：本控件按内容自适应宽度（不撑满父容器），这样与输入框同行时（如
 * 「本金输入框 + 万元/元」）不会抢占输入控件空间，避免输入框被挤成 0 宽而消失。
 */
@Composable
fun SegmentedButton2(
    left: String, right: String, leftSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Row(Modifier.padding(3.dp)) {
            SegmentedCell2(left, leftSelected, Modifier.widthIn(min = 60.dp)) { onSelect(true) }
            Spacer(Modifier.size(3.dp))
            SegmentedCell2(right, !leftSelected, Modifier.widthIn(min = 56.dp)) { onSelect(false) }
        }
    }
}

/** 三分段选择器：三个等宽选项，选中态胶囊高亮 */
@Composable
fun SegmentedButton3(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(3.dp)) {
            labels.forEachIndexed { idx, label ->
                if (idx > 0) Spacer(Modifier.size(3.dp))
                if (idx == selectedIndex) {
                    Button(
                        onClick = { onSelect(idx) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(idx) },
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedCell2(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            modifier = modifier.heightIn(min = 34.dp)
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            modifier = modifier.heightIn(min = 34.dp)
        ) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 弹窗内可滚动列表容器：限制最大高度并支持滚动，避免选项过多时无法拉取 */
@Composable
fun DialogScrollList(maxHeight: androidx.compose.ui.unit.Dp = 420.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        content = content
    )
}

fun num(text: String, default: Double = 0.0): Double = text.toDoubleOrNull() ?: default
fun numStr(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.2f", v)
