package com.wb.mdgw.calc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 02 诉讼费与诉讼全成本计算器界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LitigationFeeScreen() {
    var caseType by remember { mutableStateOf(LitigationFeeEngine.CaseType.PROPERTY) }
    var amountStr by remember { mutableStateOf("10") }
    var unitWan by remember { mutableStateOf(true) }
    var hasAmount by remember { mutableStateOf(true) }
    var halved by remember { mutableStateOf(false) }
    var adminSpecial by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }

    val amount = num(amountStr) * if (unitWan) 10_000 else 1
    val result = remember(caseType, amount, hasAmount, halved, adminSpecial) {
        LitigationFeeEngine.calculate(caseType, amount, hasAmount, halved, adminSpecial)
    }
    val summary = remember(caseType, amount, result) { LitigationFeeEngine.summaryText(caseType, amount, result) }

    CalcScrollBody {
        Text("选择案件类型后自动测算受理费、保全费与执行费（依据《诉讼费用交纳办法》）",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CalcCard("案件类型") {
            Button(onClick = { showTypePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(caseType.label)
            }
            if (caseType == LitigationFeeEngine.CaseType.ADMINISTRATIVE) {
                CalcSwitchRow("商标 / 专利 / 海事行政案件", adminSpecial,
                    { adminSpecial = it }, "取消则为一般行政案件 50 元/件")
            }
            if (caseType == LitigationFeeEngine.CaseType.INTELLECTUAL) {
                CalcSwitchRow("涉及争议金额", hasAmount, { hasAmount = it },
                    "无争议金额按 500–1000 元/件计收")
            }
            if (caseType.supportsHalved) {
                CalcSwitchRow("简易程序 / 调解 / 撤诉（减半）", halved, { halved = it })
            }
        }

        if (caseType.usesAmount && (caseType != LitigationFeeEngine.CaseType.INTELLECTUAL || hasAmount)) {
            CalcCard("标的金额") {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amountStr, onValueChange = { amountStr = it },
                        label = { Text("金额", fontSize = 12.sp) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    Spacer(Modifier.size(8.dp))
                    SegmentedButton2("万元", "元", unitWan) { unitWan = it }
                }
                Text("折合：${LitigationFeeEngine.fmt2(amount)} 元", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        CalcResultHeadline("测算结果", result.headline, result.sub)

        if (result.hasLifecycle) {
            CalcCard("诉讼全成本一览") {
                CostRow("一审全额受理费", LitigationFeeEngine.yuan(result.firstFull!!))
                CostRow("简易程序减半", LitigationFeeEngine.yuan(result.firstHalf!!))
                CostRow("财产保全申请费", LitigationFeeEngine.yuan(result.preservation!!))
                CostRow("二审受理费", LitigationFeeEngine.yuan(result.appeal!!))
                CostRow("执行申请费", LitigationFeeEngine.yuan(result.execution!!))
            }
        }

        CalcCard("分段计算明细") { CalcStepList(result.steps) }
        CalcCopyShareBar(summary, "诉讼费测算.txt")
    }

    if (showTypePicker) {
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text("选择案件类型") },
            text = {
                DialogScrollList {
                    LitigationFeeEngine.CaseType.entries.forEach { t ->
                        TextButton(onClick = { caseType = t; showTypePicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(t.label, fontSize = 13.sp)
                                Text(t.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTypePicker = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun CostRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = androidx.compose.ui.graphics.Color(0xFF6B4E1F))
    }
}
