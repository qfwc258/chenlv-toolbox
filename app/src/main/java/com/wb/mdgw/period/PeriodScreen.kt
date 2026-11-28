package com.wb.mdgw.period

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.BrandTokens
import com.wb.mdgw.calc.CalcCard
import com.wb.mdgw.calc.CalcCopyShareBar
import com.wb.mdgw.calc.CalcScrollBody
import com.wb.mdgw.calc.SegmentedButton2
import com.wb.mdgw.period.LegalCalendarEngine.VerificationStatus
import java.time.LocalDate

/**
 * 期间时效模块：法律期间与诉讼时效速算、法定工作日间隔、日期前后推算、周岁计算。
 * 功能与数据移植自参照仓库 qwertyzhu/cn-lawyer-ai-toolbox（Apache-2.0）
 * 的 DateCalculator.tsx 与 PreservationDeadlineTool.tsx。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("期间时效", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp))
                        Text("法定期间 · 诉讼时效 · 保全期限", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text("数据与算法移植自 qwertyzhu/cn-lawyer-ai-toolbox（Apache-2.0）",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("期间速算", "工作日", "日期推算", "周岁").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = 12.sp, maxLines = 1) }
                    )
                }
            }
            when (tab) {
                0 -> LegalDeadlineTab()
                1 -> WorkdayTab()
                2 -> DateShiftTab()
                3 -> AgeTab()
            }
        }
    }
}

// ---------------- Tab 1：法律期间与诉讼时效 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalDeadlineTab() {
    val today = remember { LegalCalendarEngine.todayChinaYmd() }
    var presetId by remember { mutableStateOf(LegalPresets.ALL.first().id) }
    var start by remember { mutableStateOf(today) }
    var manual by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    val preset = LegalPresets.byId(presetId)

    val computed = remember(start, manual, preset) {
        if (!LegalCalendarEngine.isValidIsoYmd(start)) null
        else preset.params(start, manual).let { LegalCalendarEngine.computeLegalDeadline(it) }
    }
    val remain = computed?.let { LegalCalendarEngine.diffDaysYmd(today, it.finalEndYmd) }

    val summary = remember(computed, preset, today, remain) {
        if (computed == null || remain == null) "" else buildLegalSummary(preset, computed, today, remain)
    }

    CalcScrollBody {
        Text("内置 ${LegalCalendarEngine.COVERED_MIN_YEAR}–${LegalCalendarEngine.COVERED_MAX_YEAR} 年国务院放假调休安排，依据《民法典》第202–203条推算",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CalcCard {
            Text("期限模板", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Button(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("${preset.category} · ${preset.shortLabel}")
            }
            Text(preset.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(preset.rule, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        CalcCard {
            DateField(preset.inputLabel, start) { start = it }
            if (!preset.isCriminal37) {
                DateField("文书载明具体届满日（选填，优先）", manual) { manual = it }
            }
        }

        if (computed == null || remain == null) {
            WarningBox("请先输入有效的起算日期（当前未计算）。日期为空或格式不完整时不会生成届满日与倒计时，避免误登记办案期限。")
        } else {
            val c = computed!!
            ResultHero(
                label = when {
                    c.verificationStatus == VerificationStatus.CONFLICT_INVALID -> "日期冲突 · 请核对"
                    c.verificationStatus == VerificationStatus.MANUAL_OVERRIDE -> "文书载明届满日"
                    c.verificationStatus == VerificationStatus.PENDING_HOLIDAY_SCHEDULE -> "理论顺延日（待核验当年调休）"
                    preset.isCriminal37 -> "第37日最晚批捕节点"
                    else -> "顺延后届满日"
                },
                value = "${c.finalEndYmd}（${LegalCalendarEngine.weekdayLabel(c.finalEndYmd)}）",
                sub = when {
                    remain > 0 -> "距今天（$today）还剩约 $remain 天"
                    remain == 0L -> "今天即为期限最后一日，请务必高度重视！"
                    else -> "该期限已于 ${-remain} 天前届满（如有中止/中断或送达异议请核实）"
                }
            )

            if (preset.isCriminal37 && c.day30Ymd != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcCard(modifier = Modifier.weight(1f)) {
                        Text("节点一 · 第30日", fontSize = 11.sp, color = Color(0xFF8A6D3B))
                        Text(c.day30Ymd!!, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    CalcCard(modifier = Modifier.weight(1f)) {
                        Text("节点二 · 第37日", fontSize = 11.sp, color = Color(0xFF8A6D3B))
                        Text(c.finalEndYmd, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (c.verificationStatus != VerificationStatus.MANUAL_OVERRIDE &&
                c.verificationStatus != VerificationStatus.CONFLICT_INVALID
            ) {
                CalcCard {
                    InfoLine("期间起算日", if (preset.rolloverMode == LegalCalendarEngine.RolloverMode.PRESERVATION_FREEZE)
                        "${c.countStartYmd}（起算日计入）" else "${c.countStartYmd}（次日起算）")
                    InfoLine("自然历理论届满日", c.rawEndYmd)
                    if (c.extended) {
                        Text("触发《民法典》第203条 /《民诉法》第85条顺延：${c.reasons.joinToString("、")}，已顺延至 ${c.finalEndYmd}。",
                            fontSize = 11.sp, color = Color(0xFF9A773E))
                    } else {
                        Text("理论届满日为工作日，无需顺延。", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (c.holidayWarningText.isNotBlank()) WarningBox(c.holidayWarningText)
            if (remain in 0..10) {
                Text("当前期限临近届满，建议尽快准备材料并核对送达回证与立案节点。",
                    fontSize = 11.sp, color = Color(0xFF8A6D3B))
            }
            CalcCopyShareBar(summary, "期间时效-${preset.shortLabel}.txt")
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择高频法律期限模板") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    LegalPresets.ALL.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p.shortLabel, fontSize = 13.sp) },
                            supportingContent = { Text("${p.category} · ${p.label}", fontSize = 11.sp) },
                            leadingContent = {
                                RadioButton(selected = p.id == presetId, onClick = {
                                    presetId = p.id
                                    manual = ""
                                    showPicker = false
                                })
                            },
                            modifier = Modifier.clickable {
                                presetId = p.id
                                manual = ""
                                showPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } }
        )
    }
}

private fun buildLegalSummary(
    preset: LegalPreset,
    c: LegalCalendarEngine.ComputedDeadline,
    today: String,
    remain: Long
): String = buildString {
    val status = when (c.verificationStatus) {
        VerificationStatus.PENDING_HOLIDAY_SCHEDULE -> "【待核验当年国务院放假通知】"
        VerificationStatus.MANUAL_OVERRIDE -> "【按文书载明日期】"
        VerificationStatus.CONFLICT_INVALID -> "【日期冲突，请核对】"
        VerificationStatus.EXACT -> ""
    }
    appendLine("【法律期间速算 · ${preset.label}】$status")
    appendLine("${preset.inputLabel}：${LegalCalendarEngine.formatCnYmd(c.startYmd)}")
    if (preset.isCriminal37 && c.day30Ymd != null) {
        appendLine("公安最晚提请批捕（第30日）：${LegalCalendarEngine.formatCnYmd(c.day30Ymd!!)}")
        appendLine("检察院最晚决定批捕（第37日）：${LegalCalendarEngine.formatCnYmd(c.finalEndYmd)}")
    } else {
        appendLine("理论届满日：${LegalCalendarEngine.formatCnYmd(c.rawEndYmd)}")
        appendLine("届满日：${LegalCalendarEngine.formatCnYmd(c.finalEndYmd)}${if (c.extended) "（遇休假日顺延）" else ""}")
    }
    appendLine(if (remain >= 0) "距今日（$today）剩余约 $remain 天" else "距今日（$today）已超期 ${-remain} 天")
    if (c.holidayWarningText.isNotBlank()) appendLine(c.holidayWarningText)
}

// ---------------- Tab 2：工作日与间隔 ----------------

@Composable
private fun WorkdayTab() {
    val today = remember { LegalCalendarEngine.todayChinaYmd() }
    var from by remember { mutableStateOf(today) }
    var to by remember { mutableStateOf(LegalCalendarEngine.addDaysYmd(today, 30)) }
    val interval = remember(from, to) { LegalCalendarEngine.calculateWorkdayInterval(from, to) }

    CalcScrollBody {
        CalcCard {
            Text("区间工作日与法定节假日核算（含首尾逐日计算，调休补班计为工作日）",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DateField("开始日期", from) { from = it }
            DateField("结束日期", to) { to = it }
        }

        if (interval == null) {
            WarningBox("请填写完整的开始日期与结束日期。")
        } else if (interval.exceededMaxRange) {
            WarningBox("所选日期跨度超过 200 年上限，请缩小起止年份范围后再计算。")
        } else {
            if (interval.outsideHolidayCoverage) {
                WarningBox("所选区间包含超出内置 ${LegalCalendarEngine.COVERED_MIN_YEAR}–${LegalCalendarEngine.COVERED_MAX_YEAR} 年放假调休表的年份；未覆盖年份按常规双休日统计，请人工核对当年放假通知。")
            }
            ResultHero("自然日间隔（不含首日）", "${interval.absDays} 天",
                "折合 ${interval.weeks} 周零 ${interval.remDays} 天 · 含首尾共 ${interval.inclusiveTotalDays} 天")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallStat("区间工作日（含首尾）", "${interval.workdays} 天", Modifier.weight(1f))
                SmallStat("公休日与法定节假日", "${interval.weekendOrHoliday} 天", Modifier.weight(1f))
            }
            if (interval.holidaysHit.isNotEmpty() || interval.adjustedHit.isNotEmpty()) {
                CalcCard {
                    if (interval.holidaysHit.isNotEmpty()) {
                        Text("命中法定节假日：${interval.holidaysHit.joinToString("、")}", fontSize = 11.sp)
                    }
                    if (interval.adjustedHit.isNotEmpty()) {
                        Text("命中调休补班日：${interval.adjustedHit.joinToString("、")}", fontSize = 11.sp)
                    }
                }
            }
            val text = buildString {
                appendLine("【工作日与日期间隔】${from} 至 ${to}")
                appendLine("自然日间隔（不含首日）：${interval.absDays} 天（含首尾 ${interval.inclusiveTotalDays} 天）")
                appendLine("区间工作日（含首尾）：${interval.workdays} 天")
                appendLine("公休日与法定节假日：${interval.weekendOrHoliday} 天")
                if (interval.holidaysHit.isNotEmpty()) appendLine("命中节假日：${interval.holidaysHit.joinToString("、")}")
                if (interval.adjustedHit.isNotEmpty()) appendLine("命中调休补班：${interval.adjustedHit.joinToString("、")}")
            }
            CalcCopyShareBar(text, "工作日间隔.txt")
        }
    }
}

// ---------------- Tab 3：日期前后推算 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateShiftTab() {
    val today = remember { LegalCalendarEngine.todayChinaYmd() }
    var base by remember { mutableStateOf(today) }
    var offset by remember { mutableStateOf("15") }
    var forward by remember { mutableStateOf(true) }
    val days = offset.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val result = remember(base, days, forward) {
        if (!LegalCalendarEngine.isValidIsoYmd(base)) null else {
            val target = LegalCalendarEngine.addDaysYmd(base, if (forward) days else -days)
            Triple(target, LegalCalendarEngine.isRestDayYmd(target),
                LegalCalendarEngine.extendPastRestDaysYmd(target))
        }
    }
    val outside = result?.let {
        LegalCalendarEngine.isDateOutsideHolidayCoverage(it.first) ||
            LegalCalendarEngine.isDateOutsideHolidayCoverage(it.third.finalYmd)
    } ?: false

    CalcScrollBody {
        CalcCard {
            DateField("基准日期", base) { base = it }
            SegmentedButton2("往后推算（+）", "往前推算（-）", forward, onSelect = { forward = it })
            OutlinedTextField(
                value = offset,
                onValueChange = { offset = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("天数", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (result == null) {
            WarningBox("请先选择有效的基准日期。")
        } else {
            ResultHero("推算结果", "${result.first}（${LegalCalendarEngine.weekdayLabel(result.first)}）",
                "当日性质：${result.second.reason}")
            if (result.third.extended) {
                CalcCard {
                    Text("若属民事诉讼/仲裁法定期间届满日，顺延至首个工作日为：${result.third.finalYmd}",
                        fontSize = 12.sp, color = Color(0xFF8A6D3B))
                }
            }
            if (outside) {
                WarningBox("推算结果超出内置 ${LegalCalendarEngine.COVERED_MIN_YEAR}–${LegalCalendarEngine.COVERED_MAX_YEAR} 年节假日与调休表，当前仅按周末判断顺延，请复核当年国务院放假通知。")
            }
            val text = buildString {
                appendLine("【日期前后推算】${base} ${if (forward) "+" else "-"} $days 天")
                appendLine("结果：${LegalCalendarEngine.formatCnYmd(result.first)}（${result.second.reason}）")
                if (result.third.extended) appendLine("法定期间顺延至：${result.third.finalYmd}")
            }
            CalcCopyShareBar(text, "日期推算.txt")
        }
    }
}

// ---------------- Tab 4：周岁与天数 ----------------

@Composable
private fun AgeTab() {
    val today = remember { LegalCalendarEngine.todayChinaYmd() }
    var birth by remember { mutableStateOf("1995-01-01") }
    val ageResult = remember(birth, today) {
        val b = LegalCalendarEngine.parseYmd(birth)
        val n = LegalCalendarEngine.parseYmd(today)
        if (b == null || n == null || b.isAfter(n)) null else {
            var age = n.year - b.year
            if (n.monthValue < b.monthValue ||
                (n.monthValue == b.monthValue && n.dayOfMonth < b.dayOfMonth)
            ) age--
            Triple(maxOf(0, age), java.time.temporal.ChronoUnit.DAYS.between(b, n), b)
        }
    }
    CalcScrollBody {
        CalcCard {
            DateField("出生日期", birth) { birth = it }
        }
        if (ageResult == null) {
            WarningBox("请先输入有效的出生日期。")
        } else {
            ResultHero("实足周岁", "${ageResult!!.first} 周岁",
                "累计自然天数约 ${ageResult.second} 天（刑事责任年龄、民事行为能力以次日零时口径核验）")
            val text = "【实足周岁】出生 ${birth} ｜ 截至 $today 为 ${ageResult.first} 周岁 ｜ 累计约 ${ageResult.second} 天"
            CalcCopyShareBar(text, "周岁计算.txt")
        }
    }
}

// ---------------- 通用组件 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String, onValueChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = { Text(label, fontSize = 12.sp) },
        readOnly = true,
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { show = true }) {
                Icon(Icons.Default.Event, contentDescription = "选择日期", modifier = Modifier.size(18.dp))
            }
        },
        modifier = Modifier.fillMaxWidth().clickable { show = true }
    )
    if (show) {
        val initial = remember(value) {
            LegalCalendarEngine.parseYmd(value)?.toEpochDay()?.times(86_400_000L)
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onValueChange(LocalDate.ofEpochDay(ms / 86_400_000L).toString())
                    }
                    show = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun ResultHero(label: String, value: String, sub: String) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF11151C), RoundedCornerShape(8.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFFD1B27A))
        Spacer(Modifier.height(8.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(sub, fontSize = 11.sp, color = Color(0xFFC9C6C0))
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xFFF5EFE3), RoundedCornerShape(8.dp)).padding(14.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8A6D3B))
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B4E1F))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 110.dp))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WarningBox(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFFFF4D6), RoundedCornerShape(8.dp)).padding(12.dp)
    ) {
        Text("注意", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A773E))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, color = Color(0xFF6B4E1F))
    }
}
