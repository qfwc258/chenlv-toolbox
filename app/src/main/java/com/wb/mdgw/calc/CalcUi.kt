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

@Composable
fun SegmentedButton2(left: String, right: String, leftSelected: Boolean, onSelect: (Boolean) -> Unit) {
    Row {
        Button(onClick = { onSelect(true) }, enabled = !leftSelected, modifier = Modifier.widthIn(min = 56.dp)) {
            Text(left, fontSize = 12.sp)
        }
        Spacer(Modifier.size(4.dp))
        OutlinedButton(onClick = { onSelect(false) }, enabled = leftSelected, modifier = Modifier.widthIn(min = 48.dp)) {
            Text(right, fontSize = 12.sp)
        }
    }
}

fun num(text: String, default: Double = 0.0): Double = text.toDoubleOrNull() ?: default
fun numStr(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.2f", v)
