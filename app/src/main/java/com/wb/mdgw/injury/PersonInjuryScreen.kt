package com.wb.mdgw.injury

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wb.mdgw.FileUtils
import com.wb.mdgw.UI_RADIUS_MD
import java.nio.charset.Charset

/**
 * 人身损害 / 交通事故赔偿填表页（09 第二 Tab）。
 * 城乡统一标准 + 年龄折算 + 交强险先行赔付 + 责任比例拆分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInjuryScreen() {
    val context = LocalContext.current
    var params by remember { mutableStateOf(PersonInjuryParamsStore.load(context)) }
    var showParams by remember { mutableStateOf(false) }

    var grade by remember { mutableStateOf(10) }
    var age by remember { mutableStateOf("38") }
    var medicalFee by remember { mutableStateOf("30000") }
    var lostDays by remember { mutableStateOf("90") }
    var dailyIncome by remember { mutableStateOf("280") }
    var nurseDays by remember { mutableStateOf("30") }
    var nursePerDay by remember { mutableStateOf("220") }
    var hospDays by remember { mutableStateOf("15") }
    var nutritionDays by remember { mutableStateOf("60") }
    var mentalFee by remember { mutableStateOf("5000") }
    var depShare by remember { mutableStateOf("0") }
    var depYears by remember { mutableStateOf("0") }
    var faultPct by remember { mutableStateOf("100") }
    var hasCompulsory by remember { mutableStateOf(true) }

    fun n(s: String) = s.toDoubleOrNull() ?: 0.0
    val case = PersonInjuryCase(
        grade = grade, age = (age.toIntOrNull() ?: 38).coerceIn(0, 110),
        medicalFee = n(medicalFee), lostDays = n(lostDays), dailyIncome = n(dailyIncome),
        nurseDays = n(nurseDays), nursePerDay = n(nursePerDay), hospDays = n(hospDays),
        nutritionDays = n(nutritionDays), mentalFee = n(mentalFee),
        dependentAnnualShare = n(depShare), dependentYears = n(depYears),
        faultPct = n(faultPct), hasCompulsoryIns = hasCompulsory
    )
    val result = remember(case, params) { PersonInjuryEngine.calc(case, params) }
    val summary = remember(case, params, result) { PersonInjuryEngine.summaryText(case, params, result) }

    Column(Modifier.fillMaxSize()) {
        // 顶部：地区口径入口
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF5EFE3)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(params.regionName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6B4E1F))
                Text("城镇可支配 ${PersonInjuryEngine.fmt0(params.urbanDisposable)} 元/年 · 可点右侧修改地区口径",
                    fontSize = 10.sp, color = Color(0xFF9A7B4F))
            }
            TextButton(onClick = { showParams = true }) { Text("地区口径", fontSize = 12.sp) }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PiCard("伤残等级与年龄") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("伤残等级", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { grade = if (grade <= 1) 0 else grade - 1 }) { Text("−") }
                    Text(if (grade == 0) "死亡" else "$grade 级", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { grade = if (grade >= 10) 10 else grade + 1 }) { Text("+") }
                }
                PiNumberField("受害人年龄", age, { age = it }, "岁")
                Text("赔偿年限 ${result.years} 年 ｜ 伤残系数 ${(result.disabilityCoeff * 100).toInt()}%",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PiCard("损害项目") {
                PiNumberField("医疗费", medicalFee, { medicalFee = it }, "元")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PiNumberField("误工天数", lostDays, { lostDays = it }, "天", Modifier.weight(1f))
                    PiNumberField("日收入", dailyIncome, { dailyIncome = it }, "元", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PiNumberField("护理天数", nurseDays, { nurseDays = it }, "天", Modifier.weight(1f))
                    PiNumberField("护理日费", nursePerDay, { nursePerDay = it }, "元", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PiNumberField("住院天数", hospDays, { hospDays = it }, "天", Modifier.weight(1f))
                    PiNumberField("营养天数", nutritionDays, { nutritionDays = it }, "天", Modifier.weight(1f))
                }
                PiNumberField("精神损害抚慰金", mentalFee, { mentalFee = it }, "元")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PiNumberField("被扶养人年度份额", depShare, { depShare = it }, "比例", Modifier.weight(1f))
                    PiNumberField("被扶养年限", depYears, { depYears = it }, "年", Modifier.weight(1f))
                }
            }

            PiCard("责任与保险") {
                PiNumberField("对方责任比例", faultPct, { faultPct = it }, "%")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("交强险有责限额先行赔付", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = hasCompulsory, onCheckedChange = { hasCompulsory = it })
                }
                Text("医疗 1.8 万 + 伤残死亡 18 万 限额内先行赔付，超出部分按责比拆分。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                Modifier.fillMaxWidth().background(Color(0xFFF5EFE3), UI_RADIUS_MD).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("预计可获赔合计", fontSize = 12.sp, color = Color(0xFF8A6D3B))
                Spacer(Modifier.height(6.dp))
                Text("¥ ${PersonInjuryEngine.fmt2(result.estimatedRecoverable)}", fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFF6B4E1F))
                Spacer(Modifier.height(4.dp))
                Text("赔偿总额 ${PersonInjuryEngine.fmt2(result.grossTotal)} 元 ｜ 交强险 ${PersonInjuryEngine.fmt2(result.compulsoryPay)} 元 ｜ 责比后 ${PersonInjuryEngine.fmt2(result.commercialOrPartyPay)} 元",
                    fontSize = 10.sp, color = Color(0xFF9A7B4F))
            }

            PiCard("逐项计算明细") {
                result.items.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row {
                            Text(item.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("¥ ${PersonInjuryEngine.fmt2(item.amount)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(item.formula, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("人损测算", summary))
                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("复制清单", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    runCatching {
                        val safe = "人身损害测算.txt"
                        val saved = FileUtils.saveToDownloads(context, safe, summary.toByteArray(Charset.forName("UTF-8")), "text/plain")
                        context.startActivity(FileUtils.shareIntent(saved.uri, safe, "text/plain"))
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("保存/分享", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showParams) {
        AlertDialog(
            onDismissRequest = { showParams = false },
            title = { Text("地区口径参数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PiNumberField("地区名称", params.regionName, { params = params.copy(regionName = it) }, "")
                    PiNumberField("城镇人均可支配收入（年）", params.urbanDisposable.toString(), { params = params.copy(urbanDisposable = it.toDoubleOrNull() ?: 0.0) }, "元")
                    PiNumberField("城镇人均消费支出（年）", params.urbanConsumption.toString(), { params = params.copy(urbanConsumption = it.toDoubleOrNull() ?: 0.0) }, "元")
                    PiNumberField("非私营单位年均工资", params.nonPrivateYearWage.toString(), { params = params.copy(nonPrivateYearWage = it.toDoubleOrNull() ?: 0.0) }, "元")
                    PiNumberField("住院伙食补助", params.hospMealPerDay.toString(), { params = params.copy(hospMealPerDay = it.toDoubleOrNull() ?: 0.0) }, "元/天")
                    PiNumberField("营养费", params.nutritionPerDay.toString(), { params = params.copy(nutritionPerDay = it.toDoubleOrNull() ?: 0.0) }, "元/天")
                    TextButton(onClick = { params = PersonInjuryParams.DEFAULT }) { Text("恢复默认") }
                }
            },
            confirmButton = {
                TextButton(onClick = { PersonInjuryParamsStore.save(context, params); showParams = false }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showParams = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PiCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = UI_RADIUS_MD, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun PiNumberField(label: String, value: Any, onValueChange: (String) -> Unit, unit: String, modifier: Modifier = Modifier) {
    val text = when (value) {
        is String -> value
        is Double -> if (value == value.toInt().toDouble()) value.toInt().toString() else String.format("%.2f", value)
        is Int -> value.toString()
        else -> value.toString()
    }
    OutlinedTextField(
        value = text, onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        suffix = if (unit.isBlank()) null else { { Text(unit, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, modifier = modifier.fillMaxWidth()
    )
}
