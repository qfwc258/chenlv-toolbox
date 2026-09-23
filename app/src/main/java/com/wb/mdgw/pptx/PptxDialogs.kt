package com.wb.mdgw.pptx

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.UI_BTN_RADIUS
import com.wb.mdgw.UI_CARD_RADIUS
import kotlin.math.roundToInt


// ============================================================
// PPTX 的「设置 / 样式 / 版式组合」弹窗与配套小组件。
//
// 原先全部堆在 MdPptxScreen.kt（单文件近 2000 行），主屏逻辑被埋没。
// 这里集中放弹窗类 UI，主屏文件只保留「预览 + 工具栏 + 画布」。
// ============================================================

// ────────────────────────────────────────────────
// 统一设置弹窗（自动分页 / 波浪 / 波浪参数 / 样式）
// ────────────────────────────────────────────────

/**
 * 设置面板：内联覆盖层（Box + Surface 卡片），不依赖 Dialog window。
 * 作为 Scaffold content 根 Box 的子项，必然显示在内容区之上，规避此前 AlertDialog 不显示问题。
 * 内含：自动分页 / 版式间距 / 波浪参数 / 直线色块高度 / 自定义样式(CSS)。
 * 底部装饰的开关已移至每页的「版式组合」选择器中，此处仅保留全局视觉参数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PptxSettingsDialog(
    autoPaginate: Boolean,
    onAuto: (Boolean) -> Unit,
    params: PptWaveParams,
    onParamsChange: (PptWaveParams) -> Unit,
    barHeightDenom: Int,
    onBarHeightDenom: (Int) -> Unit,
    bandGap: Int,
    onBandGap: (Int) -> Unit,
    logoScale: Float,
    onLogoScale: (Float) -> Unit,
    logoHAlign: String,
    onLogoHAlign: (String) -> Unit,
    logoVAlign: String,
    onLogoVAlign: (String) -> Unit,
    onStyle: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) { onDismiss() }
    // 内联覆盖层：不依赖 Dialog window，作为 content 根 Box 的子项必然显示在内容区之上，
    // 彻底规避此前 AlertDialog 在该组合上下文下「点击已触发、弹窗却不显示」的问题。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .imePadding()
            .clickable(
                onClick = onDismiss,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 600.dp)
                // 卡片本身消费点击，避免点卡片内空白处穿透到遮罩误关
                .clickable(
                    onClick = {},
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("设置", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                SectionLabel("排版")
                // 自动分页
                SettingToggleRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "自动分页",
                    desc = "按内容自动拆分多页",
                    checked = autoPaginate,
                    onCheckedChange = onAuto
                )
                // 版式间距（全局）：色块与正文的间距，数值直接输入
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Height, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("版式间距", fontSize = 14.sp)
                        Text(
                            "色块与正文的间距，全局生效",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GapNumField(value = bandGap, onChange = onBandGap)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionLabel("底部装饰参数（全局 · 在版式组合中逐页开关）")

                // 波浪参数（始终显示）：高度 / 透明度 / 层次对比，100% = 出厂效果
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "波浪参数（100% = 出厂效果）",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        WaveParamSlider(label = "波浪高度", value = params.heightScale, rangeMin = 0.4f, rangeMax = 1.6f, step = 0.05f) { onParamsChange(params.copy(heightScale = it)) }
                        WaveParamSlider(label = "波浪透明度", value = params.opacityScale, rangeMin = 0.3f, rangeMax = 1.2f, step = 0.05f) { onParamsChange(params.copy(opacityScale = it)) }
                        WaveParamSlider(label = "层次对比", value = params.contrast, rangeMin = 0.0f, rangeMax = 1.6f, step = 0.05f) { onParamsChange(params.copy(contrast = it)) }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 直线色块参数（始终显示）：高度可调（默认 1/60 页高），颜色跟随主题主色调
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("直线色块高度", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "1/${barHeightDenom} 页高",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = barHeightDenom.toFloat(),
                            onValueChange = { onBarHeightDenom(it.roundToInt()) },
                            valueRange = 30f..100f,
                            steps = ((100f - 30f) / 5f - 1f).toInt().coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "越小越厚（1/30~1/100）；颜色跟随主题主色调，满屏宽、贴齐页底",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Logo 参数（始终显示）：大小 / 水平位置 / 垂直位置
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "Logo 参数（右下角 · 在版式组合中逐页开关）",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // 大小
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("大小", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "${(logoScale * 100).roundToInt()}%",
                                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = logoScale,
                            onValueChange = onLogoScale,
                            valueRange = 0.10f..0.30f,
                            steps = ((0.30f - 0.10f) / 0.02f - 1f).toInt().coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        // 水平位置
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("水平", fontSize = 13.sp, modifier = Modifier.weight(0.3f))
                            Pill("左", logoHAlign == "left") { onLogoHAlign("left") }
                            Pill("右", logoHAlign == "right") { onLogoHAlign("right") }
                        }
                        Spacer(Modifier.height(4.dp))
                        // 垂直位置
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("垂直", fontSize = 13.sp, modifier = Modifier.weight(0.3f))
                            Pill("上", logoVAlign == "top") { onLogoVAlign("top") }
                            Pill("下", logoVAlign == "bottom") { onLogoVAlign("bottom") }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                SectionLabel("样式")
                // 自定义样式
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Palette, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("自定义样式 (CSS)", fontSize = 14.sp)
                        Text("行距 / 颜色 / 字体 / 字号…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onStyle) { Text("编辑", fontWeight = FontWeight.Bold) }
                }

                // 底部操作
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 恢复默认：波浪参数 / 色块高度 / 版式间距 一并复位
                    TextButton(onClick = {
                        onParamsChange(PptWaveParams())
                        onBarHeightDenom(60)
                        onBandGap(24)
                    }) { Text("恢复默认") }
                    Button(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text("完成") }
                }
            }
        }
    }
}

/** 设置弹窗中的分区小标题（紧凑、主色、字距收窄）。 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/**
 * 版式间距数值输入框：仅数字、限 0~96pt，输入即生效。
 * 文本与状态双向同步：外部复位（恢复默认 / 草稿恢复）时跟随刷新，手动清空不回写状态。
 */
@Composable
internal fun GapNumField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                val digits = t.filter { it.isDigit() }.take(2)   // 两位上限，覆盖 0~96
                text = digits                                    // 允许清空（暂不回写，保留原值）
                digits.toIntOrNull()?.let { n ->
                    val clamped = n.coerceIn(0, 96)              // 超限钳制，显示与状态保持一致
                    if (clamped != n) text = clamped.toString()
                    onChange(clamped)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 14.sp, textAlign = TextAlign.Center
            ),
            modifier = Modifier.width(88.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "pt", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 设置弹窗中的「图标 + 标题/副标题 + 开关」紧凑行。 */
@Composable
internal fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 自定义样式（CSS）编辑：内联覆盖层，不依赖 Dialog window，规避此前 AlertDialog 不显示问题。
 * 支持：修改 / 持久（PptStyleStore）/ 恢复默认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PptxStyleDialog(
    initialCss: String,
    defaultCss: String,
    context: android.content.Context,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var edit by remember(initialCss) {
        mutableStateOf(if (initialCss.isBlank()) defaultCss else initialCss)
    }
    BackHandler(enabled = true) { onDismiss() }
    // 内联覆盖层：与设置面板一致的 Box 遮罩 + Surface 卡片，必然显示在内容区之上
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .imePadding()
            .clickable(
                onClick = onDismiss,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 560.dp)
                // 卡片本身消费点击，避免点卡片内空白处穿透到遮罩误关
                .clickable(
                    onClick = {},
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 标题栏
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("自定义样式（CSS）", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                Text(
                    "支持行距 / 段距 / 颜色 / 字体 / 字号 / 画布等，实时作用于预览与导出。留空或「恢复默认」即恢复出厂样式。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = edit,
                    onValueChange = { edit = it },
                    label = { Text("CSS 样式") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    singleLine = false
                )

                // 底部操作
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) { Text("恢复默认") }
                    Button(onClick = { onApply(edit.trim()) }, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) { Text("应用") }
                }
            }
        }
    }
}

/** 单个波浪参数滑块（标签 + 百分比读数 + Material3 Slider）。 */
@Composable
internal fun WaveParamSlider(
    label: String,
    value: Float,
    rangeMin: Float,
    rangeMax: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}%",
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = rangeMin..rangeMax,
            steps = ((rangeMax - rangeMin) / step - 1f).toInt().coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 阶段二组合选择器：结构 × 色块 × 对齐 × 装饰，作为每页版式的唯一控制。
 *
 * 预览区有空间时各轴选项直接展开（免去来回切换），顶部保留「全部应用」开关：
 *  - 自上而下依次为 结构 / 色块 / 对齐 / 栏宽(仅多栏) / 装饰，每条横向滚动；
 *  - 色块项带主题色迷你图示；装饰项支持长按 Pill 弹 Tooltip 说明效果。
 *
 * 「全部应用=是」时跳过特殊页（封面/目录/章节/结尾），由父级 PreviewPager 处理。
 */

/** 是否为多栏结构（左右/三栏/四栏）：`栏宽` 轴只在此时启用。 */
internal val SlideComposition.isMultiCol: Boolean
    get() = structure == Structure.TWO_COL || structure == Structure.THREE_COL || structure == Structure.FOUR_COL

@Composable
internal fun CompositionSelector(
    comp: SlideComposition,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    theme: PptTheme,
    onCompositionChange: (SlideComposition) -> Unit
) {
    val isMultiCol = comp.isMultiCol
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = UI_CARD_RADIUS,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            // 顶部行：版式标题 + 右侧「全部应用」（预览区有空间，各轴选项直接展开）
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "版式布局",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "全部应用",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Pill(if (applyToAll) "是" else "否", applyToAll, compact = true) {
                    onApplyToAllChange(!applyToAll)
                }
            }

            // 下方：全部轴选项直接展开，免去来回切换
            AxisSection("结构") {
                ToolRow {
                    Structure.values().forEach { s ->
                        Pill(s.label, comp.structure == s) { onCompositionChange(comp.copy(structure = s)) }
                    }
                }
            }
            AxisSection("色块") {
                ToolRow {
                    ColorBlock.values().forEach { c ->
                        val (iconColor, _) = colorBlockVisual(c)   // 形状信息已通过 iconShape 单独传入
                        PillWithIcon(
                            text = c.label,
                            iconColor = iconColor,
                            iconShape = c,
                            selected = comp.colorBlock == c,
                            compact = true,
                            theme = theme
                        ) { onCompositionChange(comp.copy(colorBlock = c)) }
                    }
                }
            }
            AxisSection("对齐") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AlignmentCell("上左", comp.valign == VAlign.TOP && comp.halign == HAlign.LEFT) {
                        onCompositionChange(comp.copy(valign = VAlign.TOP, halign = HAlign.LEFT))
                    }
                    AlignmentCell("上中", comp.valign == VAlign.TOP && comp.halign == HAlign.CENTER) {
                        onCompositionChange(comp.copy(valign = VAlign.TOP, halign = HAlign.CENTER))
                    }
                    AlignmentCell("中左", comp.valign == VAlign.CENTER && comp.halign == HAlign.LEFT) {
                        onCompositionChange(comp.copy(valign = VAlign.CENTER, halign = HAlign.LEFT))
                    }
                    AlignmentCell("中中", comp.valign == VAlign.CENTER && comp.halign == HAlign.CENTER) {
                        onCompositionChange(comp.copy(valign = VAlign.CENTER, halign = HAlign.CENTER))
                    }
                    // 多栏结构时，栏宽滑动条放在对齐行右侧
                    if (isMultiCol) {
                        Spacer(Modifier.width(8.dp))
                        ColumnWidthSlider(
                            colRatio = comp.colRatio,
                            onRatioChange = { onCompositionChange(comp.copy(colRatio = it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            AxisSection("装饰") {
                if (comp.hasBigBlock) {
                    Text(
                        "有色块时不可用",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                } else {
                    ToolRow {
                        BottomDecoration.values().forEach { d ->
                            PillWithTooltip(
                                text = d.label,
                                tip = decorationTip(d),
                                selected = comp.decoration == d
                            ) { onCompositionChange(comp.copy(decoration = d)) }
                        }
                    }
                }
            }
        }
    }
}

/** 版式轴小标题 + 其下一条选项行（直接展开时用）。 */
@Composable
internal fun AxisSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            title,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        content()
    }
}

/** 单条横向滚动的选项行：承载某一个轴的全部 Pickable 项。 */
@Composable
internal fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 「栏宽」滑动条：主栏占比滑动调节（20~80），null=智能。
 * 放在对齐行右侧，紧凑不占空间。调用处需在 RowScope 中传入 Modifier.weight(1f)。
 */
@Composable
internal fun ColumnWidthSlider(
    colRatio: Int?,
    onRatioChange: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = colRatio ?: 50
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                if (colRatio == null) "智能" else "${colRatio}%",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (colRatio != null) {
                Text(
                    "✕",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable { onRatioChange(null) }
                )
            }
        }
        Slider(
            value = current.toFloat(),
            onValueChange = { onRatioChange(it.roundToInt()) },
            valueRange = 20f..80f,
            steps = 11,
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
    }
}

/** 色块枚举 → (迷你图示色, 形状)。迷你图示在 Pill 左侧画出主题色的小色块。 */
internal fun colorBlockVisual(c: ColorBlock): Pair<String, Boolean> = when (c) {
    ColorBlock.NONE -> "#E0E0E0" to false   // 灰白底（无色块）
    ColorBlock.COVER -> "theme" to false     // 主题色整块
    ColorBlock.LEFT -> "theme" to true       // 主题色左侧竖条
    ColorBlock.TOP -> "theme" to false       // 主题色顶部横条
    ColorBlock.BOTTOM -> "theme" to false    // 主题色底部横条
    ColorBlock.RIGHT -> "theme" to true      // 主题色右侧竖条
}

/** Pill 文字 + 主题色迷你图示（用于「色块」行）。
 *  [iconShape] 决定色块的几何形态：整块/竖条/横条/无；颜色取自 [theme.accent]。 */
@Composable
internal fun PillWithIcon(
    text: String,
    iconColor: String,
    iconShape: ColorBlock,
    selected: Boolean,
    compact: Boolean = false,
    theme: PptTheme,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    // 解码主题色（"#RRGGBB" → Color）
    val accentColor = remember(theme.accent) { parseHexColor(theme.accent) }
    val grayColor = remember { parseHexColor("#E0E0E0") }
    val drawColor = if (iconColor == "theme") accentColor else grayColor
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.height(if (compact) 24.dp else 28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            // 左侧 8dp 宽 × 14dp 高的迷你色块图示（按形状决定占满 / 竖条 / 横条）
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when (iconShape) {
                            ColorBlock.NONE -> grayColor
                            ColorBlock.COVER -> drawColor
                            ColorBlock.TOP -> drawColor
                            ColorBlock.BOTTOM -> drawColor
                            ColorBlock.LEFT -> drawColor
                            ColorBlock.RIGHT -> drawColor
                        }
                    )
            )
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 10.sp, color = fg, maxLines = 1)
        }
    }
}

/** 对齐网格中的单格：36dp 宽的方形 Pill，含 2 字对齐标签。 */
@Composable
internal fun AlignmentCell(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.size(width = 38.dp, height = 26.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontSize = 10.sp, color = fg, maxLines = 1)
        }
    }
}

/** 装饰选项的悬浮提示文本。 */
internal fun decorationTip(d: BottomDecoration): String = when (d) {
    BottomDecoration.NONE -> "不画任何装饰"
    BottomDecoration.WAVE -> "底部一条波浪曲线"
    BottomDecoration.BAR -> "底部一条直线色块"
    BottomDecoration.LOGO -> "右下角放置 Logo"
}

/** Pill + 长按弹 Tooltip（用于「装饰」行：4 个选项效果不直观时给文字说明）。 */
@Composable
internal fun PillWithTooltip(text: String, tip: String, selected: Boolean, onClick: () -> Unit) {
    var showTip by remember { mutableStateOf(false) }
    Box {
        Pill(text, selected, onClick = onClick)
        // 透明长按热区：让长按事件能落到 Pill 上（Surface 已支持 onClick，但长按需要用 Modifier.pointerInput）
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(text) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { showTip = true }
                    )
                }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = showTip,
            onDismissRequest = { showTip = false }
        ) {
            androidx.compose.material3.Text(tip, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
        }
    }
}

/** 解析 "#RRGGBB" → androidx.compose.ui.graphics.Color。 */
internal fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color {
    val s = hex.removePrefix("#")
    val v = s.toLong(16)
    return androidx.compose.ui.graphics.Color(
        red = ((v shr 16) and 0xFF) / 255f,
        green = ((v shr 8) and 0xFF) / 255f,
        blue = (v and 0xFF) / 255f,
        alpha = 1f
    )
}

/** 紧凑胶囊按钮（仅文字，选中态实色填充）。
 *  [compact]=true 时按键更小（高度 22dp、无垂直内边距），
 *  让「色块」6 个选项能在窄屏上排开不换行；默认保持原 28dp 标准规格。 */
@Composable
internal fun Pill(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg, shape = UI_BTN_RADIUS, onClick = onClick,
        modifier = Modifier.height(if (compact) 22.dp else 28.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}
