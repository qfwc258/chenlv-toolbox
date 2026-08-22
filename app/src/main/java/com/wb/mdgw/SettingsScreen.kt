package com.wb.mdgw

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.pptx.PptThemes
import com.wb.mdgw.pptx.hexToColor
import com.wb.mdgw.wechat.CssEditDialog
import com.wb.mdgw.wechat.ThemePreset
import com.wb.mdgw.wechat.ThemeStorage

/**
 * 「设置」Tab：统一收纳全局/公文/公众号/PPTX 的全部偏好设置。
 *
 * 所有控件直接读写 [AppSettings]（单一数据源），改动即时同步到各功能 Tab；
 * 持久化由 AppSettings 内部落到各自的 Store。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val darkMode by AppSettings.darkMode.collectAsState()
    val wordSpec by AppSettings.wordSpec.collectAsState()
    val smartQuotes by AppSettings.smartQuotes.collectAsState()
    val pageNumber by AppSettings.pageNumber.collectAsState()
    val titleFont by AppSettings.titleFont.collectAsState()
    val wechatTheme by AppSettings.wechatTheme.collectAsState()
    val pptxTone by AppSettings.pptxTone.collectAsState()
    val pptxAutoPaginate by AppSettings.pptxAutoPaginate.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var showCss by remember { mutableStateOf(false) }
    var toneInput by remember { mutableStateOf(pptxTone) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ---------- 通用 ----------
        SettingsGroup("通用") {
            SettingsRow(title = "深色模式", desc = "夜间护眼，全局生效") {
                Switch(
                    checked = darkMode,
                    onCheckedChange = { AppSettings.setDarkMode(context, it) }
                )
            }
            SettingsRow(title = "关于", desc = "版本、开发者与联系方式") {
                TextButton(onClick = { showAbout = true }) {
                    Text("查看", fontSize = 13.sp)
                }
            }
        }

        // ---------- 公文 ----------
        SettingsGroup("公文") {
            WSectionLabel("排版规范")
            GovDocSpec.ALL_PRESETS.forEach { spec ->
                val sel = wordSpec == spec
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { AppSettings.chooseWordSpec(context, spec) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = sel,
                        onClick = { AppSettings.chooseWordSpec(context, spec) },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(spec.specName, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text(
                            when (spec.specName) {
                                "国标通用" -> "默认标准格式"
                                "诉讼文书" -> "适配诉讼卷宗页边距"
                                "行政机关" -> "严格符合 GB/T 9704"
                                else -> ""
                            },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsRow(title = "智能引号", desc = "英文直引号自动转中文弯引号") {
                Switch(
                    checked = smartQuotes,
                    onCheckedChange = { AppSettings.setSmartQuotes(context, it) }
                )
            }
            SettingsRow(title = "添加页码", desc = "Word 写入页脚、PDF 底部居中阿拉伯数字") {
                Switch(
                    checked = pageNumber,
                    onCheckedChange = { AppSettings.setPageNumber(context, it) }
                )
            }

            WSectionLabel("主标题字体")
            Text(
                "部分手机无小标宋，可切换黑体避免异常",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(MdToGongwen.FONT_XIAOBIAO to "小标宋", MdToGongwen.FONT_HEI to "黑体").forEach { (v, label) ->
                    FilterChip(
                        selected = titleFont == v,
                        onClick = { AppSettings.setTitleFont(context, v) },
                        label = { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ---------- 公众号 ----------
        SettingsGroup("公众号") {
            WSectionLabel("主题")
            ThemePreset.THEMES.forEach { theme ->
                val sel = wechatTheme == theme.key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { AppSettings.setWechatTheme(context, theme.key) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = sel,
                        onClick = { AppSettings.setWechatTheme(context, theme.key) },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(theme.name, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showCss = true },
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("自定义 CSS", fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        AppSettings.resetWechatCss(context)
                        Toast.makeText(context, "已恢复当前主题默认样式", Toast.LENGTH_SHORT).show()
                    },
                    enabled = ThemeStorage.hasCustom(context, wechatTheme),
                    modifier = Modifier.weight(1f).height(40.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复默认", fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                }
            }
        }

        // ---------- PPTX ----------
        SettingsGroup("PPTX") {
            WSectionLabel("色调")
            Text(
                "主色调贯穿封面、章节色块、标题与强调，点色板或输入色值",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            // 色板（12 色平铺两行，点选即生效）
            PptThemes.CUSTOM_PALETTE.chunked(6).forEach { rowColors ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    rowColors.forEach { c ->
                        val isSel = c.equals(pptxTone, true)
                        val color = hexToColor(c)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (isSel) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else {
                                        Modifier.border(1.2.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                                    }
                                )
                                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                                    toneInput = c
                                    AppSettings.setPptxTone(context, c)
                                }
                        ) {
                            if (isSel) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp).align(Alignment.Center)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            // 自定义色值输入（6 位 hex，输满即生效）
            OutlinedTextField(
                value = toneInput,
                onValueChange = { v ->
                    toneInput = v.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                    if (toneInput.length == 6) AppSettings.setPptxTone(context, toneInput)
                },
                label = { Text("自定义色值（如 2E5FA3）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            SettingsRow(title = "自动分页", desc = "超长内容自动拆分为多页") {
                Switch(
                    checked = pptxAutoPaginate,
                    onCheckedChange = { AppSettings.setPptxAutoPaginate(context, it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showCss) {
        CssEditDialog(
            initialCss = AppSettings.wechatCss.value,
            onDismiss = { showCss = false },
            onSave = { AppSettings.setWechatCss(context, it) }
        )
    }
}

// ---------- 分组与行 UI ----------

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}

@Composable
private fun SettingsRow(
    title: String,
    desc: String? = null,
    trailing: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (desc != null) {
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun WSectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}
