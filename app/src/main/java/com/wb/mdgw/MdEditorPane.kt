package com.wb.mdgw

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 公共 Markdown 编辑器 —— WORD / 公众号 / PPTX 三个 Tab 复用的编辑区。
 *
 * 设计原则：
 *  - **状态由调用方持有**（内容 [tfv]、字号 [fontSize]、撤销/重做栈），本组件只做 UI 与回调转发；
 *    因此各 Tab 的编辑内容、字号、草稿存储、撤销栈完全独立，互不干扰。
 *  - 编辑区尽量铺满可用空间：信息条与工具栏极薄，编辑卡片 `weight(1f)` 占满剩余高度。
 *  - 统一能力：格式工具栏（[MarkdownSnippets.SNIPPETS]）、字号步进、撤销/重做、清空、字数/行数统计。
 *
 * @param tfv           编辑内容（含光标）
 * @param fontSize      当前字号（调用方独立记忆，本组件不持久化）
 * @param onFontSizeChange 字号步进回调（如 `{ fontSize = it }`）
 * @param onChange      内容变更回调（调用方在此维护撤销栈 / 置脏）
 * @param onInsert      格式片段插入回调
 * @param onUndo/canUndo/onRedo/canRedo 撤销 / 重做
 * @param onClear       清空（null 表示不显示清空按钮）
 * @param title         顶部信息条左侧标题
 * @param hint          信息条右侧的轻提示（页面数 / 转换提示等），空态时也作为副文案
 */
@Composable
fun MdEditorPane(
    tfv: TextFieldValue,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onChange: (TextFieldValue) -> Unit,
    onInsert: (MarkdownSnippets.Snippet) -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
    onRedo: () -> Unit,
    canRedo: Boolean,
    onClear: (() -> Unit)? = null,
    title: String = "Markdown 源",
    hint: String? = null
) {
    val charCount = tfv.text.length
    val lineCount = if (tfv.text.isEmpty()) 0 else tfv.text.lineSequence().count()

    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp)) {
        // ── 顶部信息条：标题 + 轻提示 + 字数/行数（单行，极薄）──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "$charCount 字 · $lineCount 行",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ── 格式工具栏：chips 横向滚动 + 字号步进 + 撤销/重做/清空 ──
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(MarkdownSnippets.SNIPPETS) { s ->
                    Surface(
                        onClick = { onInsert(s) },
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 9.dp)) {
                            Text(
                                s.label, fontSize = 11.sp, maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            FontSizeStepper(fontSize, onFontSizeChange)
            Spacer(Modifier.width(2.dp))
            MdIconBtn(Icons.Default.Undo, "撤销", enabled = canUndo, onClick = onUndo)
            MdIconBtn(Icons.Default.Redo, "重做", enabled = canRedo, onClick = onRedo)
            if (onClear != null) {
                MdIconBtn(Icons.Default.Delete, "清空", enabled = tfv.text.isNotEmpty(), onClick = onClear)
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 编辑卡片：占满剩余空间 ──
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            shape = UI_CARD_RADIUS,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            BasicTextField(
                value = tfv,
                onValueChange = onChange,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize + 6).sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = false,
                maxLines = Int.MAX_VALUE,
                decorationBox = { inner ->
                    if (tfv.text.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                shape = CircleShape,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.EditNote, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("在此输入 Markdown 内容", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint ?: "输入后将实时渲染",
                                fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                MarkdownSnippets.HINT_SNIPPETS.forEach { s ->
                                    Surface(
                                        onClick = { onInsert(s) },
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            s.label, fontSize = 11.sp, maxLines = 1, softWrap = false,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    inner()
                }
            )
        }
    }
}

/** 字号步进器：`A− 15 A+`，范围 12~24。 */
@Composable
private fun FontSizeStepper(fontSize: Int, onChange: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.height(26.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdIconBtn(
                Icons.Default.Remove, "减小字号",
                enabled = fontSize > 12, size = 24.dp, iconSize = 14.dp,
                onClick = { onChange(fontSize - 1) }
            )
            Text(
                "$fontSize", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 1.dp)
            )
            MdIconBtn(
                Icons.Default.Add, "增大字号",
                enabled = fontSize < 24, size = 24.dp, iconSize = 14.dp,
                onClick = { onChange(fontSize + 1) }
            )
        }
    }
}

/** 工具栏小图标按钮（紧凑，28dp）。 */
@Composable
private fun MdIconBtn(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    size: Dp = 28.dp,
    iconSize: Dp = 16.dp,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(
            icon, desc, modifier = Modifier.size(iconSize),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    }
}
