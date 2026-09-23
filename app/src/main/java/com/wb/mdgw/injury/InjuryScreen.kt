package com.wb.mdgw.injury

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wb.mdgw.FileUtils
import java.nio.charset.Charset
import java.text.DecimalFormat

/** 顶部模式：残疾赔偿 / 死亡赔偿（死亡即工亡） */
private enum class Mode { DISABILITY, DEATH }

/** 右上角三点菜单打开的面板：赔偿参数 / 计算方式 / 政策说明 */
private enum class Panel { NONE, PARAMS, METHOD, POLICY }

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
    // 5-6 级难以安排工作（发伤残津贴）
    var difficultToArrange by remember { mutableStateOf(saved?.difficultToArrange ?: false) }
    // 工亡供养亲属抚恤金
    var pensionSpouse by remember { mutableStateOf(saved?.pensionSpouse ?: false) }
    var pensionOtherText by remember {
        mutableStateOf(saved?.pensionOther?.let { if (it > 0) it.toString() else "" } ?: "")
    }
    var pensionOrphanText by remember {
        mutableStateOf(saved?.pensionOrphan?.let { if (it > 0) it.toString() else "" } ?: "")
    }
    var fees by remember { mutableStateOf(buildInitialFees(saved)) }
    // 核心参数（湖南 2025 默认，可本地覆盖）
    var params by remember { mutableStateOf(InjuryParamsStore.load(context)) }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    var showRankGrid by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }

    val isLevel1to4 = rank in LEVEL_1_TO_4
    val isDeath = mode == Mode.DEATH
    val rankInt = runCatching { rank.toInt() }.getOrDefault(1)

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
            otherCosts = split.others,
            difficultToArrange = !isDeath && difficultToArrange,
            pensionSpouse = isDeath && pensionSpouse,
            pensionOther = if (isDeath) pensionOtherText.toIntOrNull() ?: 0 else 0,
            pensionOrphan = if (isDeath) pensionOrphanText.toIntOrNull() ?: 0 else 0
        )
    }

    val result = remember(
        mode, nameText, sexType, ageText, rank, wageText, breakRelation, careType,
        stopMonthText, hospitalDayText, fees, params, difficultToArrange,
        pensionSpouse, pensionOtherText, pensionOrphanText
    ) { calculator.calculate(parseCase(), params) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun resetAll() {
        mode = Mode.DISABILITY
        nameText = ""; sexType = SexType.MALE; ageText = ""; rank = Rank.LEVEL_1
        wageText = ""; breakRelation = false; careType = CareType.NONE
        stopMonthText = ""; hospitalDayText = ""; fees = defaultFees()
        difficultToArrange = false
        pensionSpouse = false; pensionOtherText = ""; pensionOrphanText = ""
        InjuryStore.erase(context)
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶部：模式 Tab + 右上角三点菜单 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ModeTabs(mode) { mode = it } }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多：参数 / 计算方式 / 政策说明")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("赔偿标准参数") },
                        onClick = { menuOpen = false; panel = Panel.PARAMS }
                    )
                    DropdownMenuItem(
                        text = { Text("计算方式说明") },
                        onClick = { menuOpen = false; panel = Panel.METHOD }
                    )
                    DropdownMenuItem(
                        text = { Text("政策说明与适用规定") },
                        onClick = { menuOpen = false; panel = Panel.POLICY }
                    )
                }
            }
        }
        HorizontalDivider()

        // ---------- 中间滚动内容 ----------
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isDeath) {
                    FormCard("伤残信息") {
                        TextInputRow("伤者姓名", nameText, "输入") { nameText = it.take(20) }
                        SelectRow("性别", labelOf(SEX_OPTIONS, sexType)) {
                            picker = PickerRequest("选择性别", SEX_OPTIONS, sexType) { sexType = it }
                        }
                        NumberInputRow("年龄", ageText, "岁") { ageText = filterNum(it) }
                        SelectRow("伤残等级", labelOf(DISABILITY_RANK_OPTIONS, rank)) {
                            showRankGrid = true
                        }
                        NumberInputRow("本人月缴费工资", wageText, "元/月") { wageText = filterNum(it) }
                        // 计薪工资封顶/保底透明展示
                        val capped = wageText.toFloatOrNull() ?: 0f
                        val lo = params.baseMonthlyWage * 0.6f
                        val hi = params.baseMonthlyWage * 3f
                        val eff = capped.coerceIn(lo, hi)
                        Text(
                            "计薪工资（封顶/保底后）：¥ ${"%,.0f".format(eff)}　本人工资 ¥ ${"%,.0f".format(capped)}（范围 ¥${lo.toInt()}~¥${hi.toInt()}）",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "本人工资，指工伤职工因工作遭受事故伤害或患职业病前 12 个月平均月缴费工资。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
                        // 劳动关系：1-4 级保留（不可解除）；5-10 级可明确选择
                        if (isLevel1to4) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("劳动关系", fontSize = 15.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "保留劳动关系（依法不得解除）",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                "1-4 级伤残保留劳动关系、退出工作岗位，不得解除；按月领取伤残津贴。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            SelectRow(
                                "劳动关系",
                                if (breakRelation) "解除劳动关系" else "不解除劳动关系"
                            ) {
                                picker = PickerRequest(
                                    "劳动关系", BREAK_OPTIONS,
                                    if (breakRelation) "true" else "false"
                                ) { breakRelation = it == "true" }
                            }
                            Text(
                                "5-10 级解除时领取一次性医疗/就业补助金；距退休不足 5 年每少 1 年减 20%，最高扣减 90%。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                            )
                        }
                        SelectRow("护理依赖", labelOf(CARE_OPTIONS, careType)) {
                            picker = PickerRequest("护理依赖", CARE_OPTIONS, careType) { careType = it }
                        }
                        // 5-6 级：难以安排工作 → 按月发伤残津贴
                        if (rankInt in 5..6) {
                            val ratePct = (params.disabilityAllowanceRate[rankInt] ?: 0f) * 100
                            SwitchRow(
                                "难以安排工作（按月发伤残津贴 ${ratePct.toInt()}%）",
                                difficultToArrange
                            ) { difficultToArrange = it }
                        }
                        NumberInputRow("停工留薪期", stopMonthText, "月") { stopMonthText = filterNum(it) }
                        Text(
                            "停工留薪期一般不超 ${params.stopWorkMaxMonth.toInt()} 个月（伤情严重可延长，不超 24 个月）。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
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
                    // 工亡：供养亲属抚恤金（可选）
                    FormCard("供养亲属抚恤金（可选）") {
                        SwitchRow("有配偶（本人工资 40%）", pensionSpouse) { pensionSpouse = it }
                        NumberInputRow("其他亲属人数", pensionOtherText, "人", integer = true) {
                            pensionOtherText = filterNum(it, integer = true)
                        }
                        NumberInputRow("孤儿 / 孤寡人数", pensionOrphanText, "人", integer = true) {
                            pensionOrphanText = filterNum(it, integer = true)
                        }
                        Text(
                            "其他亲属每人 30%、孤儿/孤寡每人 40%（30% 基础上 +10%），各亲属抚恤金之和不超过本人工资，按月发放。",
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

                // ---------- 结果：基金 / 单位 ----------
                ResultGroupCard("工伤保险基金支付（一次性）", result.fundItems, result.totalFund)
                ResultGroupCard("用人单位支付（一次性）", result.employerItems, result.totalEmployer)

                // ---------- 结果：按月长期待遇 ----------
                if (result.monthlyItems.isNotEmpty()) {
                    MonthlyResultCard("按月长期待遇（不计入一次性总额）", result.monthlyItems)
                }

                // ---------- 一次性总额 ----------
                Card(shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp),
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
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("工伤赔偿", buildResultText(result)))
                                    toast("结果已复制")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Text(" 复制")
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val text = buildExportText(parseCase(), result, params)
                                        val uri = FileUtils.writeCache(
                                            context, "工伤赔偿清单.txt",
                                            text.toByteArray(Charset.forName("UTF-8"))
                                        )
                                        context.startActivity(
                                            Intent.createChooser(
                                                FileUtils.shareIntent(uri, "工伤赔偿清单.txt", "text/plain"),
                                                "导出清单"
                                            )
                                        )
                                    }.onFailure { toast("导出失败：${it.message}") }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Text(" 导出清单")
                            }
                        }
                        if (result.note.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("备注：${result.note}", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "计算口径依据《工伤保险条例》及湖南省实施办法（2025）。本结果仅供参考，以社保 / 仲裁 / 法院认定为准。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                        )
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
                    onClick = {
                        InjuryStore.saveCase(context, parseCase())
                        InjuryParamsStore.save(context, params)
                        toast("案件与参数已保存")
                    },
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

    // ---------- 伤残等级选择（两列紧凑网格：1-10 级两列、工亡整行，十级可见无需滚动） ----------
    if (showRankGrid) {
        AlertDialog(
            onDismissRequest = { showRankGrid = false },
            title = { Text("伤残等级", fontSize = 16.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DISABILITY_RANK_OPTIONS.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { (v, l) ->
                                RankChip(l, v == rank, Modifier.weight(1f)) {
                                    rank = v
                                    if (v in LEVEL_1_TO_4) breakRelation = false
                                    showRankGrid = false
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        RankChip("工亡", rank == Rank.DEATH, Modifier.fillMaxWidth()) {
                            rank = Rank.DEATH
                            showRankGrid = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRankGrid = false }) { Text("取消") }
            }
        )
    }

    // ---------- 三点菜单：全屏面板（参数 / 计算方式 / 政策说明） ----------
    if (panel != Panel.NONE) {
        val title = when (panel) {
            Panel.PARAMS -> "赔偿标准参数"
            Panel.METHOD -> "计算方式说明"
            Panel.POLICY -> "政策说明与适用规定"
            Panel.NONE -> ""
        }
        Dialog(
            onDismissRequest = { panel = Panel.NONE },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { panel = Panel.NONE }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                    HorizontalDivider()
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (panel) {
                            Panel.PARAMS -> ParamsPanel(
                                params = params,
                                onChange = { params = it },
                                onReset = {
                                    params = InjuryParams.DEFAULT
                                    InjuryParamsStore.reset(context)
                                    toast("已恢复湖南 2025 默认")
                                }
                            )
                            Panel.METHOD -> {
                                val lines = buildCalcMethodLines(parseCase(), params)
                                if (lines.isEmpty()) {
                                    Text("请先填写伤残等级 / 工亡相关项。", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.outline)
                                } else lines.forEach {
                                    Text("· $it", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Panel.POLICY -> POLICY_NOTES.forEach {
                                Text("· $it", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Panel.NONE -> {}
                        }
                    }
                }
            }
        }
    }
}

/** 等级选择小卡片（选中高亮），用于紧凑网格，避免 RadioButton 占高导致十级被裁切 */
@Composable
private fun RankChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    )
}

/**
 * 赔偿标准参数面板（在全屏弹窗中展示）：统筹工资、住院伙食、人均可支配收入、
 * 各项补助月数均可编辑；弹窗已提供标题与滚动，这里只放内容。
 */
@Composable
private fun ParamsPanel(
    params: InjuryParams,
    onChange: (InjuryParams) -> Unit,
    onReset: () -> Unit
) {
    var baseText by remember(params.baseMonthlyWage) {
        mutableStateOf(params.baseMonthlyWage.toInt().toString())
    }
    var yearText by remember(params.baseMonthlyWage) {
        mutableStateOf((params.baseMonthlyWage * 12f).toInt().toString())
    }
    var foodText by remember(params.hospitalFoodPerDay) {
        mutableStateOf(params.hospitalFoodPerDay.toInt().toString())
    }
    var urbanText by remember(params.urbanIncome) {
        mutableStateOf(params.urbanIncome.toInt().toString())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---------- 统筹与单价标准 ----------
        SectionTitle("统筹与单价标准")
        NumberInputRow("统筹工资 / 月", baseText, "元", integer = true) {
            baseText = filterNum(it, integer = true)
            baseText.toFloatOrNull()?.let { v -> onChange(params.copy(baseMonthlyWage = v)) }
        }
        NumberInputRow("统筹工资 / 年", yearText, "元", integer = true) {
            yearText = filterNum(it, integer = true)
            yearText.toFloatOrNull()?.let { v -> onChange(params.copy(baseMonthlyWage = v / 12f)) }
        }
        Text(
            "统筹地区上年度职工平均工资，本人工资按 60%~300% 封顶保底；年 = 月 × 12，两栏任改其一，另一栏自动换算。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )
        NumberInputRow("住院伙食补助 / 天", foodText, "元", integer = true) {
            foodText = filterNum(it, integer = true)
            foodText.toFloatOrNull()?.let { v -> onChange(params.copy(hospitalFoodPerDay = v)) }
        }
        NumberInputRow("城镇人均可支配收入/年", urbanText, "元", integer = true) {
            urbanText = filterNum(it, integer = true)
            urbanText.toFloatOrNull()?.let { v -> onChange(params.copy(urbanIncome = v)) }
        }
        Text(
            "一次性工亡补助金 = 人均可支配收入 × 20 = ${(params.urbanIncome * 20f).toInt()} 元；单价与标准均可在政策更新时修改保存。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---------- 补助月数标准（默认湖南 2025，可改以适配外地） ----------
        SectionTitle("补助月数标准（默认湖南 2025）")
        Text(
            "各地补助月数不同，可在此修改以计算外地案件；留空或 0 视为该项不发放。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
        )
        MonthEditor("一次性伤残补助金（1-10 级）", params.disabilityOnceMonths, (1..10).toList()) { lv, v ->
            onChange(params.copy(disabilityOnceMonths = params.disabilityOnceMonths.toMutableMap().apply { put(lv, v) }))
        }
        MonthEditor("一次性工伤医疗补助金（5-10 级）", params.medicalOnceMonths, (5..10).toList()) { lv, v ->
            onChange(params.copy(medicalOnceMonths = params.medicalOnceMonths.toMutableMap().apply { put(lv, v) }))
        }
        MonthEditor("一次性伤残就业补助金（5-10 级）", params.employOnceMonths, (5..10).toList()) { lv, v ->
            onChange(params.copy(employOnceMonths = params.employOnceMonths.toMutableMap().apply { put(lv, v) }))
        }

        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
            Text("恢复湖南 2025 默认")
        }
    }
}

/** 参数卡片内的分组小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

/**
 * 补助月数编辑器：按等级在 2 列紧凑网格中逐个编辑月数。
 * [levels] 决定显示哪些等级（如 1-10 级或 5-10 级）。
 */
@Composable
private fun MonthEditor(
    title: String,
    months: Map<Int, Float>,
    levels: List<Int>,
    onLevelChange: (Int, Float) -> Unit
) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        levels.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { lv ->
                    LevelMonthField(lv, months[lv] ?: 0f, Modifier.weight(1f)) { onLevelChange(lv, it) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
}

/** 单个等级的月数输入框（紧凑：等级标签 + 小数字框） */
@Composable
private fun LevelMonthField(level: Int, value: Float, modifier: Modifier = Modifier, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value > 0f) value.toInt().toString() else "") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        Text("${level}级", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(30.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = filterNum(it, integer = true)
                onValue(text.toFloatOrNull() ?: 0f)
            },
            singleLine = true,
            placeholder = { Text("月", fontSize = 11.sp) },
            textStyle = TextStyle(fontSize = 13.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** 按月长期待遇卡片 */
@Composable
private fun MonthlyResultCard(title: String, items: Map<String, Float>) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            items.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${"%,.2f".format(v)} 元/月", fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "按月发放的长期待遇，未计入上方一次性总额。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
            )
        }
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
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 政策说明与适用规定条目（在全屏弹窗中展示，静态口径参考，不参与计算） */
private val POLICY_NOTES = listOf(
    "伤残津贴：1-4 级由工伤保险基金按月发放；5-6 级难以安排工作的，由用人单位按月发放。",
    "1-4 级办理退休手续后停发伤残津贴，基本养老保险待遇低于伤残津贴的，由工伤保险基金补足差额。",
    "5-10 级解除劳动关系时，一次性医疗/就业补助金距退休不足 5 年每少 1 年减 20%，最高减除 90%。",
    "生活护理费由基金按月支付：全部不能自理 50%、大部分 40%、部分 30%（按统筹工资）。",
    "住院伙食补助：2022 年 1 月 1 日前住院为 10 元/天，之后为 20 元/天。",
    "一次性工亡补助金 = 上年度全国城镇居民人均可支配收入 × 20；丧葬补助金 = 6 个月统筹工资。",
    "供养亲属抚恤金：配偶 40%、其他亲属每人 30%、孤儿/孤寡每人 40%，总和不超过生前本人工资。",
    "应参保而未参保的，由用人单位按《工伤保险条例》规定的项目和标准支付全部费用。",
    "本人工资指工伤前 12 个月平均月缴费工资，高于统筹工资 300% 按 300%、低于 60% 按 60% 计算。",
    "2011 年 1 月 1 日以后发生并认定的工伤，按现行标准执行。"
)

/** 生成计算方式说明条目（与实际计算口径一致） */
private fun buildCalcMethodLines(case: InjuryCase, params: InjuryParams): List<String> {
    val f = DecimalFormat("#,##0")
    val base = params.baseMonthlyWage
    val lo = base * 0.6f
    val hi = base * 3f
    val wage = case.wage.coerceIn(lo, hi)
    val list = mutableListOf<String>()
    if (case.rank == Rank.DEATH) {
        list += "一次性工亡补助金 = 全国城镇居民人均可支配收入 ¥${f.format(params.urbanIncome)} × 20（¥ ${f.format(params.urbanIncome * 20f)}）"
        list += "丧葬补助金 = 统筹工资 ¥${f.format(base)} × 6 个月"
        if (case.pensionSpouse || case.pensionOther > 0 || case.pensionOrphan > 0) {
            list += "供养亲属抚恤金 = 本人工资 ¥${f.format(wage)} ×（配偶40% + 其他亲属30%×人数 + 孤儿/孤寡40%×人数），封顶100%，按月发放"
        }
        return list
    }
    val r = case.rank.toIntOrNull() ?: return list
    list += "一次性伤残补助金 = 本人工资 ¥${f.format(wage)} × ${params.disabilityOnceMonths[r]?.toInt() ?: 0} 个月"
    if (case.careType != CareType.NONE) {
        val rate = ((params.lifeCareRate[case.careType] ?: 0f) * 100).toInt()
        list += "生活护理费 = 统筹工资 ¥${f.format(base)} × $rate%（按月发放）"
    }
    val allowRate = params.disabilityAllowanceRate[r] ?: 0f
    val allowEligible = r in 1..4 || (r in 5..6 && case.difficultToArrange)
    if (allowRate > 0 && allowEligible) {
        val payer = if (r in 1..4) "基金" else "单位"
        list += "伤残津贴 = 本人工资 ¥${f.format(wage)} × ${(allowRate * 100).toInt()}%（$payer 按月发放）"
    }
    if (case.breakRelation && r in 5..10) {
        list += "一次性工伤医疗补助金 = 本人工资 ¥${f.format(wage)} × ${params.medicalOnceMonths[r]?.toInt() ?: 0} 个月（解除时按距退休扣减，最高扣 90%）"
        list += "一次性伤残就业补助金 = 本人工资 ¥${f.format(wage)} × ${params.employOnceMonths[r]?.toInt() ?: 0} 个月（解除时按距退休扣减，最高扣 90%）"
    }
    if (case.hospitalDay > 0) {
        list += "住院伙食补助费 = ¥${params.hospitalFoodPerDay.toInt()} /天 × ${case.hospitalDay} 天"
    }
    if (case.stopMonth > 0) {
        list += "停工留薪期工资 = 本人工资 ¥${f.format(wage)} × ${case.stopMonth.toInt()} 个月（原待遇不变）"
    }
    return list
}

/** 分组卡片：标题 + 分隔线 + 内容 */
@Composable
private fun FormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
            .padding(vertical = 8.dp),
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
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            placeholder = { Text("输入", fontSize = 13.sp) },
            textStyle = TextStyle(fontSize = 14.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal
            ),
            modifier = Modifier.width(112.dp)
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
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
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
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (items.isEmpty()) {
                Text("（无）", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            } else {
                items.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${"%,.2f".format(v)} 元", fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("小计", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
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
        append("【工伤保险基金支付（一次性）】\n")
        if (r.fundItems.isEmpty()) append("  （无）\n")
        else r.fundItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalFund)} 元\n\n")
        append("【用人单位支付（一次性）】\n")
        if (r.employerItems.isEmpty()) append("  （无）\n")
        else r.employerItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalEmployer)} 元\n\n")
        if (r.monthlyItems.isNotEmpty()) {
            append("【按月长期待遇（不计入一次性总额）】\n")
            r.monthlyItems.forEach { append("  ${it.key}：${f.format(it.value)} 元/月\n") }
            append("\n")
        }
        append("【一次性赔偿总额】：${f.format(r.grandTotal)} 元\n")
        if (r.note.isNotEmpty()) append("\n备注：${r.note}")
    }
}

/** 生成可分享/导出的完整清单文本（含参数口径与法律依据） */
private fun buildExportText(case: InjuryCase, r: CalcResult, params: InjuryParams): String {
    val f = DecimalFormat("#,##0.00")
    return buildString {
        append("工伤赔偿计算清单\n")
        append("============================\n")
        append("伤者/死者：${case.name.ifBlank { "（未填）" }}\n")
        append("伤残等级：${if (case.rank == Rank.DEATH) "工亡" else "${case.rank} 级"}\n")
        append("本人工资：${f.format(case.wage)} 元/月\n")
        append("计薪工资（封顶保底后）：${f.format(r.effectiveWage)} 元/月\n")
        append("劳动关系：${if (case.rank == Rank.DEATH) "—" else if (case.breakRelation) "解除" else "保留"}\n")
        append("\n【工伤保险基金支付（一次性）】\n")
        if (r.fundItems.isEmpty()) append("  （无）\n")
        else r.fundItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalFund)} 元\n")
        append("\n【用人单位支付（一次性）】\n")
        if (r.employerItems.isEmpty()) append("  （无）\n")
        else r.employerItems.forEach { append("  ${it.key}：${f.format(it.value)} 元\n") }
        append("  小计：${f.format(r.totalEmployer)} 元\n")
        if (r.monthlyItems.isNotEmpty()) {
            append("\n【按月长期待遇（不计入一次性总额）】\n")
            r.monthlyItems.forEach { append("  ${it.key}：${f.format(it.value)} 元/月\n") }
        }
        append("\n【一次性赔偿总额】：${f.format(r.grandTotal)} 元\n")
        if (r.note.isNotEmpty()) append("\n备注：${r.note}\n")
        append("\n计算口径：统筹工资 ${f.format(params.baseMonthlyWage)} 元/月，")
        append("住院伙食补助 ${params.hospitalFoodPerDay.toInt()} 元/天；")
        append("依据《工伤保险条例》及湖南省实施办法。结果仅供参考，以社保/仲裁/法院认定为准。\n")
        val method = buildCalcMethodLines(case, params)
        if (method.isNotEmpty()) {
            append("\n【计算方式】\n")
            method.forEach { append("  · $it\n") }
        }
        append("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n")
    }
}
