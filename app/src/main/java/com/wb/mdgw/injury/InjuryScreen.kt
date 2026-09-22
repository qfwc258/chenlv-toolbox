package com.wb.mdgw.injury

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormat

/** 顶部模式：残疾赔偿 / 死亡赔偿（死亡即工亡） */
private enum class Mode { DISABILITY, DEATH }

/** 动态费用行草稿 */
private data class FeeDraft(val id: Long, val name: String, val amountText: String)

/** 点击行式选择项时弹出的选择请求 */
private data class PickerRequest(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

/** 性别 / 等级 / 护理的可选项（value 到中文标签） */
private val SEX_OPTIONS = listOf(
    SexType.MALE to "男（60岁退休）",
    SexType.FEMALE_WORKER to "女工人（50岁退休）",
    SexType.FEMALE_CADRE to "女干部（55岁退休）"
)
private val RANK_OPTIONS = listOf(
    Rank.LEVEL_1 to "一级", Rank.LEVEL_2 to "二级", Rank.LEVEL_3 to "三级",
    Rank.LEVEL_4 to "四级", Rank.LEVEL_5 to "五级", Rank.LEVEL_6 to "六级",
    Rank.LEVEL_7 to "七级", Rank.LEVEL_8 to "八级", Rank.LEVEL_9 to "九级",
    Rank.LEVEL_10 to "十级", Rank.DEATH to "工亡"
)
private val DISABILITY_RANK_OPTIONS = RANK_OPTIONS.filter { it.first != Rank.DEATH }
private val CARE_OPTIONS = listOf(
    CareType.NONE to "无", CareType.FULL to "完全",
    CareType.MOST to "大部分", CareType.PART to "部分"
)
private val BREAK_OPTIONS = listOf(
    "false" to "不解除劳动关系",
    "true" to "解除劳动关系"
)
private val LEVEL_1_TO_4 = setOf(
    Rank.LEVEL_1, Rank.LEVEL_2, Rank.LEVEL_3, Rank.LEVEL_4
)

/**
 * 工伤赔偿页：顶部「残疾赔偿 / 死亡赔偿」切换，卡片行式填报，输入即实时计算。
 * 顶部标题返回栏由外层 SimpleScreenFrame 提供；底部固定「重置 / 保存」。
 */
@Composable
fun InjuryScreen() {
    val context = LocalContext.current
    val calculator = remember { InjuryCalculator() }
    val saved = remember { InjuryStore.loadCase(context) }

    var mode by remember {
        mutableStateOf(if (saved?.rank == Rank.DEATH) Mode.DEATH else Mode.DISABILITY)
    }
    var nameText by remember { mutableStateOf(saved?.name ?: "") }
    var sexType by remember { mutableStateOf(saved?.sexType ?: SexType.MALE) }
    var ageText by remember { mutableStateOf(saved?.age?.let(::numStr) ?: "") }
    var rank by remember {
        mutableStateOf(saved?.rank?.takeIf { it != Rank.DEATH } ?: Rank.LEVEL_1)
    }
    var wageText by remember { mutableStateOf(saved?.wage?.let(::numStr) ?: "") }
    var breakRelation by remember { mutableStateOf(saved?.breakRelation ?: false) }
    var careType by remember { mutableStateOf(saved?.careType ?: CareType.NONE) }
    var stopMonthText by remember { mutableStateOf(saved?.stopMonth?.let(::numStr) ?: "") }
    var hospitalDayText by remember {
        mutableStateOf(saved?.hospitalDay?.let { if (it > 0) it.toString() else "" } ?: "")
    }
    var fees by remember { mutableStateOf(buildInitialFees(saved)) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }

    val isLevel1to4 = rank in LEVEL_1_TO_4
    val isDeath = mode == Mode.DEATH

    fun parseCase(): InjuryCase {
        val split = splitFees(fees)
        return InjuryCase(
            name = nameText.trim(),
            sexType = sexType,
            age = ageText.toFloatOrNull() ?: 0f,
            rank = if (isDeath) Rank.DEATH else rank,
            wage = wageText.toFloatOrNull() ?: 0f,
            breakRelation = !isDeath && breakRelation,
            careType = if (isDeath) CareType.NONE else careType,
            stopMonth = if (isDeath) 0f else (stopMonthText.toFloatOrNull() ?: 0f),
            hospitalDay = hospitalDayText.toIntOrNull() ?: 0,
            medicalCost = split.medical,
            rehabCost = split.rehab,
            assistCost = split.assist,
            otherCosts = split.others
        )
    }

    val result = remember(
        mode, nameText, sexType, ageText, rank, wageText, breakRelation, careType,
        stopMonthText, hospitalDayText, fees
    ) { calculator.calculate(parseCase()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun resetAll() {
        mode = Mode.DISABILITY
        nameText = ""; sexType = SexType.MALE; ageText = ""; rank = Rank.LEVEL_1
        wageText = ""; breakRelation = false; careType = CareType.NONE
        stopMonthText = ""; hospitalDayText = ""; fees = defaultFees()
        InjuryStore.erase(context)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶部模式 Tab（固定） ----------
        ModeTabs(mode) { mode = it }
        HorizontalDivider()

        // ---------- 中间滚动内容 ----------
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isDeath) {
                    FormCard("伤残信息") {
                        TextInputRow("伤者姓名", nameText, "输入") { nameText = it.take(20) }
                        SelectRow("性别", labelOf(SEX_OPTIONS, sexType)) {
                            picker = PickerRequest("选择性别", SEX_OPTIONS, sexType) { sexType = it }
                        }
                        NumberInputRow("年龄", ageText, "岁") { ageText = filterNum(it) }
                        SelectRow("伤残等级", labelOf(DISABILITY_RANK_OPTIONS, rank)) {
                            picker = PickerRequest("伤残等级", DISABILITY_RANK_OPTIONS, rank) {
                                rank = it
                                if (it in LEVEL_1_TO_4) breakRelation = false
                            }
                        }
                        NumberInputRow("本人月缴费工资", wageText, "元/月") { wageText = filterNum(it) }
                        Text(
                            "本人工资，指工伤职工因工作遭受事故伤害或患职业病前 12 个月平均月缴费工资。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
                        SelectRow(
                            "劳动关系",
                            if (breakRelation) "解除劳动关系" else "不解除",
                            enabled = !isLevel1to4
                        ) {
                            if (!isLevel1to4) {
                                picker = PickerRequest(
                                    "劳动关系", BREAK_OPTIONS,
                                    if (breakRelation) "true" else "false"
                                ) { breakRelation = it == "true" }
                            }
                        }
                        if (isLevel1to4) {
                            Text(
                                "1-4级保留劳动关系，不得解除",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        SelectRow("护理依赖", labelOf(CARE_OPTIONS, careType)) {
                            picker = PickerRequest("护理依赖", CARE_OPTIONS, careType) { careType = it }
                        }
                        NumberInputRow("停工留薪期", stopMonthText, "月") { stopMonthText = filterNum(it) }
                        NumberInputRow("住院天数", hospitalDayText, "天", integer = true) {
                            hospitalDayText = filterNum(it, integer = true)
                        }
                    }
                } else {
                    FormCard("死者信息") {
                        TextInputRow("死者姓名", nameText, "选填") { nameText = it.take(20) }
                        NumberInputRow("住院天数", hospitalDayText, "天", integer = true) {
                            hospitalDayText = filterNum(it, integer = true)
                        }
                        Text(
                            "工亡待遇为一次性工亡补助金 + 丧葬补助金；如有抢救、住院等费用，可在下方填写。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // ---------- 各项费用（动态） ----------
                FormCard("各项费用") {
                    fees.forEach { f ->
                        FeeRow(
                            fee = f,
                            onName = { v -> fees = fees.update(f.id) { it.copy(name = v) } },
                            onAmount = { v -> fees = fees.update(f.id) { it.copy(amountText = filterNum(v)) } },
                            onRemove = { fees = fees.filterNot { it.id == f.id } }
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val nid = (fees.maxOfOrNull { it.id } ?: 0L) + 1L
                                fees = fees + FeeDraft(nid, "", "")
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(" 添加各项费用", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    }
                }

                // ---------- 结果 ----------
                ResultGroupCard("工伤保险基金支付", result.fundItems, result.totalFund)
                ResultGroupCard("用人单位支付", result.employerItems, result.totalEmployer)

                Card(shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("一次性赔偿总额", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "¥ ${"%,.2f".format(result.grandTotal)}",
                            fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("工伤赔偿", buildResultText(result)))
                                toast("结果已复制")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Text(" 复制结果文本")
                        }
                        if (result.note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("备注：${result.note}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Spacer(Modifier.height(72.dp))
            }
        }

        // ---------- 底部固定操作 ----------
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { resetAll(); toast("已重置") },
                    modifier = Modifier.weight(1f)
                ) { Text("重置") }
                Button(
                    onClick = { InjuryStore.saveCase(context, parseCase()); toast("案件已保存") },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
            }
        }
    }

    // ---------- 行式选择弹窗 ----------
    picker?.let { p ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(p.title, fontSize = 16.sp) },
            text = {
                Column {
                    p.options.forEach { (v, l) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { p.onPick(v); picker = null }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = p.selected == v,
                                onClick = { p.onPick(v); picker = null }
                            )
                            Text(" $l", fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picker = null }) { Text("取消") }
            }
        )
    }
}

/** 顶部模式切换 Tab 行 */
@Composable
private fun ModeTabs(mode: Mode, onSelect: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModeTab("残疾赔偿", mode == Mode.DISABILITY, Modifier.weight(1f)) { onSelect(Mode.DISABILITY)}
        ModeTab("死亡赔偿", mode == Mode.DEATH, Modifier.weight(1f)) { onSelect(Mode.DEATH)}
    }
}

/** 单个模式 Tab */
@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 分组卡片：标题 + 分隔线 + 内容 */
@Composable
private fun FormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** 行式选择项：左标签 + 右值/占位 + > */
@Composable
private fun SelectRow(
    label: String,
    value: String,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Text(
            if (value.isBlank()) placeholder else value,
            fontSize = 14.sp,
            color = if (value.isBlank()) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.onSurface
        )
        Text("  >", color = MaterialTheme.colorScheme.outline, fontSize = 16.sp)
    }
}

/** 行式数字输入：左标签 + 右输入框 + 单位 */
@Composable
private fun NumberInputRow(
    label: String,
    value: String,
    unit: String,
    integer: Boolean = false,
    onValue: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            placeholder = { Text("输入", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal
            ),
            modifier = Modifier.width(124.dp)
        )
        Text(" $unit", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(48.dp))
    }
}

/** 行式文本输入（姓名等） */
@Composable
private fun TextInputRow(
    label: String,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End),
            modifier = Modifier.width(160.dp)
        )
    }
}

/** 动态费用行：名称 + 金额 + 删除 */
@Composable
private fun FeeRow(
    fee: FeeDraft,
    onName: (String) -> Unit,
    onAmount: (String) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        OutlinedTextField(
            value = fee.name,
            onValueChange = onName,
            singleLine = true,
            placeholder = { Text("费用名称", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = fee.amountText,
            onValueChange = onAmount,
            singleLine = true,
            placeholder = { Text("金额", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(92.dp)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Close, contentDescription = "删除该费用", modifier = Modifier.size(18.dp))
        }
    }
}

/** 结果分组卡片：明细列表 + 小计 */
@Composable
private fun ResultGroupCard(title: String, items: Map<String, Float>, subtotal: Float) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (items.isEmpty()) {
                Text("（无）", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            } else {
                items.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${"%,.2f".format(v)} 元", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("小计", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${"%,.2f".format(subtotal)} 元", fontSize = 14.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 默认费用三项 */
private fun defaultFees(): List<FeeDraft> = listOf(
    FeeDraft(1L, "医疗费", ""),
    FeeDraft(2L, "康复费", ""),
    FeeDraft(3L, "辅助器具费", "")
)

/** 从存档案件重建费用列表（固定三项 + 自定义） */
private fun buildInitialFees(c: InjuryCase?): List<FeeDraft> {
    if (c == null) return defaultFees()
    val list = mutableListOf(
        FeeDraft(1L, "医疗费", numStr(c.medicalCost)),
        FeeDraft(2L, "康复费", numStr(c.rehabCost)),
        FeeDraft(3L, "辅助器具费", numStr(c.assistCost))
    )
    c.otherCosts.forEachIndexed { i, n ->
        list.add(FeeDraft(4L + i.toLong(), n.name, numStr(n.amount)))
    }
    return list
}

/** 数字输入过滤（整数 / 小数，仅一个小数点） */
private fun filterNum(s: String, integer: Boolean = false): String {
    var t = s.filter { it.isDigit() || (!integer && it == '.') }
    if (!integer && t.count { it == '.' } > 1) {
        t = t.substringBefore('.') + "." + t.substringAfter('.').replace(".", "")
    }
    return t.take(10)
}

/** 取选项中文标签 */
private fun labelOf(options: List<Pair<String, String>>, v: String): String =
    options.firstOrNull { it.first == v }?.second ?: ""

/** 按 id 更新某条费用 */
private fun List<FeeDraft>.update(id: Long, block: (FeeDraft) -> FeeDraft): List<FeeDraft> =
    map { if (it.id == id) block(it) else it }

/** 费用分类结果 */
private data class FeeSplit(
    val medical: Float,
    val rehab: Float,
    val assist: Float,
    val others: List<NamedCost>
)

/** 把动态费用按名称归入 医疗/康复/辅助，其余作为自定义费用 */
private fun splitFees(fees: List<FeeDraft>): FeeSplit {
    var medical = 0f
    var rehab = 0f
    var assist = 0f
    val other = LinkedHashMap<String, Float>()
    fees.forEach { f ->
        val amt = f.amountText.toFloatOrNull() ?: 0f
        val n = f.name.trim()
        when {
            n.contains("医疗") -> medical += amt
            n.contains("康复") -> rehab += amt
            n.contains("辅助") || n.contains("器具") -> assist += amt
            else -> other.merge(n.ifBlank { "其他费用" }, amt, Float::plus)
        }
    }
    return FeeSplit(medical, rehab, assist, other.map { NamedCost(it.key, it.value) })
}

/** 数值转简洁字符串（整数不带小数点） */
private fun numStr(v: Float): String =
    if (v <= 0f) "" else if (v % 1f == 0f) v.toInt().toString() else v.toString()

/** 生成纯文本结果（用于复制，口径与原版一致） */
private fun buildResultText(r: CalcResult): String {
    val f = DecimalFormat("#,##0.00")
    return buildString {
        append("【工伤保险基金支付】\n")
        if (r.fundItems.isEmpty()) append("  （无）\n")
        else r.fundItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalFund)} 元\n\n")
        append("【用人单位支付】\n")
        if (r.employerItems.isEmpty()) append("  （无）\n")
        else r.employerItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalEmployer)} 元\n\n")
        append("【一次性赔偿总额】：${f.format(r.grandTotal)} 元\n")
        if (r.note.isNotEmpty()) append("\n备注：${r.note}")
    }
}
