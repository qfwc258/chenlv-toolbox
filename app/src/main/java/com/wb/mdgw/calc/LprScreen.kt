package com.wb.mdgw.calc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 04 LPR 逾期付款利息与迟延履行金计算器界面。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LprScreen() {
    val context = LocalContext.current
    var lprHistory by remember { mutableStateOf(LprTableStore.load(context)) }
    var showTableEditor by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(LprEngine.Mode.LPR_FLOATING) }
    var principalStr by remember { mutableStateOf("50") }
    var unitWan by remember { mutableStateOf(true) }
    var startDate by remember { mutableStateOf("2024-06-01") }
    var endDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var term by remember { mutableStateOf(LprEngine.Term.ONE_YEAR) }
    var multiplierStr by remember { mutableStateOf("1") }
    var bpsStr by remember { mutableStateOf("0") }
    var customRateStr by remember { mutableStateOf("6") }
    var dayBasis365 by remember { mutableStateOf(true) }
    var includeDelay by remember { mutableStateOf(false) }
    var delayStartDate by remember { mutableStateOf("2025-08-01") }
    var showModePicker by remember { mutableStateOf(false) }

    val principal = num(principalStr) * if (unitWan) 10_000 else 1
    val input = LprEngine.Input(
        mode = mode, principal = principal, startDate = startDate, endDate = endDate,
        term = term, multiplier = num(multiplierStr, 1.0), bps = num(bpsStr),
        customRate = num(customRateStr, 6.0), dayBasis = if (dayBasis365) 365 else 360,
        includeDelay = includeDelay, delayStartDate = delayStartDate,
        history = lprHistory
    )
    val result = remember(input) { LprEngine.calc(input) }
    val summary = remember(input, result) { LprEngine.summaryText(input, result) }
    // 当前起算日查表得到的 LPR（1年期/5年期），随编辑实时更新
    val snapLpr = remember(startDate, term, lprHistory) {
        LprEngine.lookupLprOn(startDate, term, lprHistory)
    }

    CalcScrollBody {
        Text("内置央行 2019-08 至 2026-11 完整 LPR 报价表，按调息日分段计息；可叠加日万分之 1.75 加倍迟延履行金。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CalcCard("计息方式") {
            Button(onClick = { showModePicker = true }, modifier = Modifier.fillMaxWidth()) { Text(mode.label) }
            if (mode != LprEngine.Mode.DELAY_ONLY) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("贷款期限", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    SegmentedButton2("1 年期", "5 年期+", term == LprEngine.Term.ONE_YEAR) {
                        term = if (it) LprEngine.Term.ONE_YEAR else LprEngine.Term.FIVE_YEAR
                    }
                }
                // 当前查表 LPR 值（可见）+ 报价表编辑入口
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("起算日 ${snapLpr.date} 报价", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1 年期 ${LprEngine.fmt2(snapLpr.r1y)}% ｜ 5 年期 ${LprEngine.fmt2(snapLpr.r5y)}%",
                            fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    TextButton(onClick = { showTableEditor = true }) { Text("报价表", fontSize = 12.sp) }
                }
            }
        }

        CalcCard("本金与期间") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = principalStr, onValueChange = { principalStr = it },
                    label = { Text("本金", fontSize = 12.sp) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.size(8.dp))
                SegmentedButton2("万元", "元", unitWan) { unitWan = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startDate, onValueChange = { startDate = it },
                    label = { Text("起算日", fontSize = 12.sp) }, placeholder = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = endDate, onValueChange = { endDate = it },
                    label = { Text("截止日", fontSize = 12.sp) }, placeholder = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f), singleLine = true)
            }
            Text("折合本金：¥ ${LprEngine.fmt2(principal)} ｜ 共 ${result.totalDays} 天", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (mode == LprEngine.Mode.LPR_FLOATING || mode == LprEngine.Mode.LPR_FIXED) {
            CalcCard("利率参数") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = multiplierStr, onValueChange = { multiplierStr = it },
                        label = { Text("LPR 倍数", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = bpsStr, onValueChange = { bpsStr = it },
                        label = { Text("加点 BP", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                }
                // 参照源码倍数快捷：1 / 1.3 / 1.5 / 4 倍，点击同时清零加点 BP
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1" to "1 倍 LPR", "1.3" to "1.3 倍（上浮30%）", "1.5" to "1.5 倍（上浮50%）", "4" to "4 倍（民间借贷上限）")
                        .forEach { (value, label) ->
                            val active = multiplierStr == value && bpsStr == "0"
                            if (active) {
                                Button(onClick = { multiplierStr = value; bpsStr = "0" }) {
                                    Text(label, fontSize = 11.sp)
                                }
                            } else {
                                OutlinedButton(onClick = { multiplierStr = value; bpsStr = "0" }) {
                                    Text(label, fontSize = 11.sp)
                                }
                            }
                        }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("计息基数", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    SegmentedButton2("365 天", "360 天", dayBasis365) { dayBasis365 = it }
                }
            }
        }

        if (mode == LprEngine.Mode.CUSTOM_RATE) {
            CalcCard("约定利率") {
                CalcNumberField("固定年利率", customRateStr, { customRateStr = it }, unit = "%")
                Text("4 倍 1 年期 LPR 司法保护上限：${result.fourTimesLatestLpr}%", fontSize = 11.sp,
                    color = if (result.exceedsPrivateLendingCap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (mode != LprEngine.Mode.DELAY_ONLY) {
            CalcCard("迟延履行金（可选）") {
                CalcSwitchRow("叠加加倍迟延履行金", includeDelay, { includeDelay = it }, "日万分之 1.75")
                if (includeDelay) {
                    OutlinedTextField(value = delayStartDate, onValueChange = { delayStartDate = it },
                        label = { Text("迟延起算日", fontSize = 12.sp) }, placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        }

        CalcResultHeadline("本息合计", "¥ ${LprEngine.fmt2(result.grandTotal)}",
            "正常利息 ${LprEngine.fmt2(result.normalInterest)} + 迟延金 ${LprEngine.fmt2(result.delayInterest)} 元")

        if (result.segments.isNotEmpty()) {
            CalcCard("分段计息明细") {
                result.segments.forEach { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("${s.from} → ${s.to}（${s.days} 天）", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text("${s.note} ｜ 实际年利率 ${String.format("%.2f", s.effectiveRate)}% ｜ 利息 ¥ ${LprEngine.fmt2(s.interest)}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (result.exceedsPrivateLendingCap) {
            Text("⚠ 约定利率超过 4 倍 1 年期 LPR（${result.fourTimesLatestLpr}%），可能超出民间借贷司法保护上限",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (result.startBeforeLpr) {
            Text("⚠ 起算日早于 2019-08-20 LPR 报价机制启动日，此前实务适用央行同期贷款基准利率，请分段核验",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        CalcCopyShareBar(summary, "LPR利息测算.txt")
    }

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text("计息方式") },
            text = {
                DialogScrollList {
                    LprEngine.Mode.entries.forEach { m ->
                        TextButton(onClick = { mode = m; showModePicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(m.label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModePicker = false }) { Text("关闭") } }
        )
    }

    if (showTableEditor) {
        LprTableEditor(
            history = lprHistory,
            onHistoryChange = { lprHistory = it },
            onDismiss = { showTableEditor = false },
            onSave = {
                LprTableStore.save(context, lprHistory)
                showTableEditor = false
            },
            onReset = {
                LprTableStore.reset(context)
                lprHistory = LprEngine.LPR_HISTORY
            }
        )
    }
}

/** LPR 报价表编辑弹窗：各档日期 + 1年期/5年期数值可改，可增行/删行/恢复默认/保存 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LprTableEditor(
    history: List<LprEngine.Milestone>,
    onHistoryChange: (List<LprEngine.Milestone>) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LPR 报价表（可修改）") },
        text = {
            Column {
                Text("按调息日分段计息。修改后保存即参与计算；恢复默认回到央行 2019-08 至今内置表。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                DialogScrollList(maxHeight = 420.dp) {
                    history.forEachIndexed { idx, m ->
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            OutlinedTextField(
                                value = m.date, onValueChange = { v ->
                                    onHistoryChange(history.toMutableList().also { it[idx] = m.copy(date = v) })
                                },
                                label = { Text("调息日", fontSize = 10.sp) },
                                modifier = Modifier.weight(1.15f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                            )
                            Spacer(Modifier.size(4.dp))
                            OutlinedTextField(
                                value = if (m.r1y == m.r1y.toInt().toDouble()) m.r1y.toInt().toString() else LprEngine.fmt2(m.r1y),
                                onValueChange = { v ->
                                    onHistoryChange(history.toMutableList().also { it[idx] = m.copy(r1y = num(v)) })
                                },
                                label = { Text("1年%", fontSize = 10.sp) },
                                modifier = Modifier.weight(0.85f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Spacer(Modifier.size(4.dp))
                            OutlinedTextField(
                                value = if (m.r5y == m.r5y.toInt().toDouble()) m.r5y.toInt().toString() else LprEngine.fmt2(m.r5y),
                                onValueChange = { v ->
                                    onHistoryChange(history.toMutableList().also { it[idx] = m.copy(r5y = num(v)) })
                                },
                                label = { Text("5年%", fontSize = 10.sp) },
                                modifier = Modifier.weight(0.85f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            TextButton(onClick = { onHistoryChange(history.filterIndexed { i, _ -> i != idx }) }) {
                                Text("删", fontSize = 11.sp)
                            }
                        }
                    }
                    TextButton(onClick = {
                        val lastDate = history.lastOrNull()?.date ?: "2025-05-20"
                        onHistoryChange(history + LprEngine.Milestone(lastDate, 0.0, 0.0))
                    }) { Text("＋ 新增一档", fontSize = 12.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
