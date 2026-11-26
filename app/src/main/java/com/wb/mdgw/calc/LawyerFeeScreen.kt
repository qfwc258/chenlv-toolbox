package com.wb.mdgw.calc

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json

/**
 * 03 律师费标准与客户报价单界面（31 省标准 / 常规 / 半风险 / 全风险 / 计时）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LawyerFeeScreen() {
    val context = LocalContext.current
    val standards = remember {
        runCatching {
            val txt = context.assets.open("lawyer_fee_standards.json").bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<List<FeeStandardJson>>(txt)
        }.getOrElse {
            Toast.makeText(context, "律师费标准数据加载失败：${it.message}", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }
    val provinces = remember(standards) { standards.map { it.p }.distinct().sorted() }

    var province by remember { mutableStateOf("北京") }
    var scheme by remember { mutableStateOf(LawyerFeeEngine.Scheme.STANDARD) }
    var amountStr by remember { mutableStateOf("10") }
    var unitWan by remember { mutableStateOf(true) }
    var baseFee by remember { mutableStateOf("10000") }
    var riskRate by remember { mutableStateOf("10") }
    var realizedStr by remember { mutableStateOf("100") }
    var capOn by remember { mutableStateOf(true) }
    var hourlyRate by remember { mutableStateOf("500") }
    var hours by remember { mutableStateOf("10") }
    var hourlyCap by remember { mutableStateOf("0") }
    var forbidRisk by remember { mutableStateOf(false) }
    var showProvincePicker by remember { mutableStateOf(false) }
    var showSchemePicker by remember { mutableStateOf(false) }

    val standard = remember(standards, province) { standards.firstOrNull { it.p == province } }
    val amount = num(amountStr) * if (unitWan) 10_000 else 1
    val input = LawyerFeeEngine.QuoteInput(
        scheme = scheme, standard = standard, amount = amount,
        baseFee = num(baseFee), riskRatePct = num(riskRate, 10.0),
        realized = num(realizedStr) * if (unitWan) 10_000 else 1,
        capOn = capOn, hourlyRate = num(hourlyRate, 500.0), hours = num(hours, 10.0),
        hourlyCap = num(hourlyCap), forbidRisk = forbidRisk
    )
    val result = remember(input) { LawyerFeeEngine.quote(input) }
    val summary = remember(input, result) { LawyerFeeEngine.summaryText(input, result) }

    CalcScrollBody {
        Text("内置全国 31 省律师服务收费政府指导价/律协指引，按标的额分段累计测算；风险代理受司发通〔2021〕87号上限约束。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CalcCard("报价方案") {
            Button(onClick = { showSchemePicker = true }, modifier = Modifier.fillMaxWidth()) { Text(scheme.label) }
        }

        if (scheme == LawyerFeeEngine.Scheme.STANDARD) {
            CalcCard("地区与标的额") {
                Button(onClick = { showProvincePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("地区：$province") }
                standard?.let { s ->
                    Text("${s.n.ifBlank { s.p + "标准" }}（${s.d}）${LawyerFeeEngine.statusLabel(s.st)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(value = amountStr, onValueChange = { amountStr = it },
                        label = { Text("争议标的额", fontSize = 12.sp) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.size(8.dp))
                    SegmentedButton2("万元", "元", unitWan) { unitWan = it }
                }
                Text("折合：¥ ${LawyerFeeEngine.fmt2(amount)}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (scheme == LawyerFeeEngine.Scheme.SEMI_RISK || scheme == LawyerFeeEngine.Scheme.FULL_RISK) {
            CalcCard("风险代理参数") {
                if (scheme == LawyerFeeEngine.Scheme.SEMI_RISK) {
                    CalcNumberField("基础费（元）", baseFee, { baseFee = it }, unit = "元")
                }
                CalcNumberField("风险提成比例", riskRate, { riskRate = it }, unit = "%")
                CalcNumberField("最终实现/减损金额", realizedStr, { realizedStr = it }, unit = if (unitWan) "万元" else "元")
                CalcSwitchRow("受 87 号文上限约束", capOn, { capOn = it }, "各环节收费合计不超分段上限")
                CalcSwitchRow("本案依法禁止风险代理", forbidRisk, { forbidRisk = it },
                    "刑事/行政/国家赔偿/婚姻继承/工伤/劳动报酬等禁入")
            }
        }

        if (scheme == LawyerFeeEngine.Scheme.HOURLY) {
            CalcCard("计时收费参数") {
                CalcNumberField("小时费率", hourlyRate, { hourlyRate = it }, unit = "元/小时")
                CalcNumberField("预计工作小时", hours, { hours = it }, unit = "小时")
                CalcNumberField("费用封顶（0 为不封顶）", hourlyCap, { hourlyCap = it }, unit = "元")
            }
        }

        CalcResultHeadline("测算律师费", result.headline, result.sub)

        CalcCard("计算明细") {
            result.rows.forEach { row ->
                val v = when {
                    row.lo != null && row.hi != null && row.lo == row.hi -> LawyerFeeEngine.fmt2(row.hi!!)
                    row.lo != null && row.hi != null -> "${LawyerFeeEngine.fmt2(row.lo!!)} – ${LawyerFeeEngine.fmt2(row.hi!!)}"
                    row.hi != null -> LawyerFeeEngine.fmt2(row.hi!!)
                    else -> "协商"
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(row.label, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(v, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            CalcStepList(result.steps)
        }

        Text("法律依据：司发通〔2021〕87号。风险代理禁止范围：${LawyerFeeEngine.RISK_PROHIBITED}",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CalcCopyShareBar(summary, "律师费报价测算.txt")
    }

    if (showSchemePicker) {
        AlertDialog(
            onDismissRequest = { showSchemePicker = false },
            title = { Text("报价方案") },
            text = {
                Column {
                    LawyerFeeEngine.Scheme.entries.forEach { s ->
                        TextButton(onClick = { scheme = s; showSchemePicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(s.label, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSchemePicker = false }) { Text("关闭") } }
        )
    }

    if (showProvincePicker) {
        AlertDialog(
            onDismissRequest = { showProvincePicker = false },
            title = { Text("选择地区（31 省）") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    provinces.forEach { p ->
                        TextButton(onClick = { province = p; showProvincePicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(p, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProvincePicker = false }) { Text("关闭") } }
        )
    }
}
