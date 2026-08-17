package com.wb.mdgw.wechat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自定义 CSS 编辑弹窗。
 *
 * 能力（对照方案第九章）：
 *   - 实时编辑、可滚动、可放大字体
 *   - 一键清空编辑区
 *   - 保存：写入当前主题独立缓存并即时生效
 *   - 取消：不落盘
 */
@Composable
fun CssEditDialog(
    initialCss: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialCss) }
    var bigFont by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("自定义 CSS（当前主题）") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { bigFont = !bigFont }) {
                        Text(if (bigFont) "缩小字体" else "放大字体")
                    }
                    TextButton(onClick = { text = "" }) {
                        Text("清空编辑区")
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (bigFont) 15.sp else 12.sp,
                        lineHeight = if (bigFont) 22.sp else 18.sp
                    ),
                    minLines = 12,
                    singleLine = false,
                    label = { Text("CSS") }
                )
                Text(
                    text = "提示：修改后即时内联到预览；点「保存」本主题独立记忆，可随时「恢复默认」回滚。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    )
}
