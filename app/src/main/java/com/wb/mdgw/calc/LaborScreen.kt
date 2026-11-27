package com.wb.mdgw.calc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 08 劳动争议经济补偿金测算器界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaborScreen() {
    val context = LocalContext.current
    var laborParams by remember { mutableStateOf(LaborParamsStore.load(context)) }
    var scenario by remember { mutableStateOf(LaborEngine.Scenario.N) }
    var tenureModeManual by remember { mutableStateOf(true) }
    var manualYears by remember { mutableStateOf("3") }
    var manualMonths by remember { mutableStateOf("4") }
    var startDate by remember { mutableStateOf("2023-03-01") }
    var endDate by remember { mutableStateOf("2026-11-22") }
    var avgSalary by remember { mutableStateOf("12000") }
    var lastMonthSalary by remember { mutableStateOf("12000") }
    var unsignedMonths by remember { mutableStateOf("6") }
    var showScenarioPicker by remember { mutableStateOf(false) }

    val input = LaborEngine.Input(
        scenario = scenario,
        tenureMode = if (tenureModeManual) "manual" else "dates",
        startDate = startDate, endDate = endDate,
        manualYears = num(manualYears), manualMonths = num(manualMonths),
        avgSalary = num(avgSalary), lastMonthSalary = num(lastMonthSalary),
        unsignedMonths = num(unsignedMonths), regionName = laborParams.regionName,
        tripleCap = laborParams.tripleCap
    )
    val result = remember(input) { LaborEngine.calc(input) }
    val summary = remember(input, result) { LaborEngine.summaryText(input, result) }

    CalcScrollBody {
        Text("测算经济补偿金、代通知金、违法解除 2N 与未签合同双倍工资；三倍社平封顶与地区口径可自定并自动保存。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CalcCard("主张情形") {
            Button(onClick = { showScenarioPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(scenario.title) }
            Text(scenario.badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        CalcCard("工龄与工资") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("工龄录入", fontSize = 13.sp, modifier = Modifier.weight(1f))
                SegmentedButton2("手动输入", "按起止日期", tenureModeManual) { tenureModeManual = it }
            }
            if (tenureModeManual) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcNumberField("工作年数", manualYears, { manualYears = it }, unit = "年", modifier = Modifier.weight(1f))
                    CalcNumberField("剩余月数", manualMonths, { manualMonths = it }, unit = "月", modifier = Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startDate, onValueChange = { startDate = it },
                        label = { Text("入职日", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = endDate, onValueChange = { endDate = it },
                        label = { Text("离职日", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
            CalcNumberField("离职前12个月税前月均工资", avgSalary, { avgSalary = it }, unit = "元")
            if (scenario == LaborEngine.Scenario.N_PLUS_1) {
                CalcNumberField("解除前 1 个月工资（代通知金）", lastMonthSalary, { lastMonthSalary = it }, unit = "元")
            }
            if (scenario == LaborEngine.Scenario.DOUBLE_WAGE) {
                CalcNumberField("未签合同计薪月数（上限 11）", unsignedMonths, { unsignedMonths = it }, unit = "月")
            }
        }

        CalcCard("地区口径（三倍社平封顶）· 可修改并保存") {
            CalcNumberField("地区名称", laborParams.regionName,
                { laborParams = laborParams.copy(regionName = it) }, placeholder = "如 湖南·长沙 2026")
            CalcNumberField("三倍社平封顶基数",
                if (laborParams.tripleCap == laborParams.tripleCap.toInt().toDouble()) laborParams.tripleCap.toInt().toString()
                else laborParams.tripleCap.toString(),
                { laborParams = laborParams.copy(tripleCap = num(it)) }, unit = "元")
            Text("默认湖南·长沙 2026：2025 长沙城镇职工月均 10914.58 ×3 ≈ 32744 元；高薪封顶与 12 年年限上限以此为准。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { LaborParamsStore.save(context, laborParams) }, modifier = Modifier.weight(1f)) {
                    Text("保存地区口径", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    LaborParamsStore.reset(context)
                    laborParams = LaborParams.DEFAULT
                }, modifier = Modifier.weight(1f)) { Text("恢复默认", fontSize = 12.sp) }
            }
        }

        CalcResultHeadline("测算参考总额", "¥ ${LaborEngine.fmt2(result.total)}",
            if (result.isCapped) "已触发高薪三倍社平封顶" else "${scenario.title} · ${result.tenure.fullYears}年${result.tenure.remMonths.toInt()}个月")

        CalcCard("逐项计算明细") { CalcStepList(result.steps) }
        CalcCopyShareBar(summary, "劳动补偿金测算.txt")
    }

    if (showScenarioPicker) {
        AlertDialog(
            onDismissRequest = { showScenarioPicker = false },
            title = { Text("主张情形") },
            text = {
                DialogScrollList {
                    LaborEngine.Scenario.entries.forEach { s ->
                        TextButton(onClick = { scenario = s; showScenarioPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(s.title, fontSize = 13.sp)
                                Text(s.badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScenarioPicker = false }) { Text("关闭") } }
        )
    }
}
