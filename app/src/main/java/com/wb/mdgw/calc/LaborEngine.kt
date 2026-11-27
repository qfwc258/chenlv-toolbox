package com.wb.mdgw.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 劳动争议经济补偿金测算器（08）——纯 Kotlin/JVM 引擎。
 * 移植自 qwertyzhu/cn-lawyer-ai-toolbox（src/tools/website/LaborCompensationCalculator.tsx）。
 * 支持 N / N+1 / 2N / 未签合同双倍工资，三倍社平封顶与地区口径可自定。
 */
object LaborEngine {

    enum class Scenario(val id: String, val title: String, val badge: String) {
        N("n", "经济补偿金（N）", "协商解除 / 终止"),
        N_PLUS_1("n_plus_1", "经济补偿 + 代通知金（N+1）", "无过失性辞退"),
        TWICE_N("2n", "违法解除赔偿金（2N）", "违法解除 / 终止"),
        DOUBLE_WAGE("double_wage", "未签书面合同双倍工资差额", "用工满 1 月起算");

        companion object { fun from(id: String): Scenario = entries.firstOrNull { it.id == id } ?: N }
    }

    data class Tenure(
        val totalMonths: Double,
        val fullYears: Int,
        val remMonths: Double,
        val nMonths: Double
    )

    data class Input(
        val scenario: Scenario = Scenario.N,
        val tenureMode: String = "manual",
        val startDate: String = "2023-03-01",
        val endDate: String = "2026-11-22",
        val manualYears: Double = 3.0,
        val manualMonths: Double = 4.0,
        val avgSalary: Double = 12_000.0,
        val lastMonthSalary: Double = 12_000.0,
        val unsignedMonths: Double = 6.0,
        val regionName: String = "湖南·长沙 2026",
        val tripleCap: Double = 32_744.0
    )

    data class Result(
        val total: Double,
        val isCapped: Boolean,
        val effectiveBase: Double,
        val effectiveN: Double,
        val noticePay: Double,
        val steps: List<String>,
        val tenure: Tenure
    )

    /** 工龄折算：满 1 年计 1 个月，6 个月以上不满 1 年计 1 个月，不满 6 个月计 0.5 个月 */
    fun calcTenure(input: Input): Tenure {
        val totalMonths = if (input.tenureMode == "dates") {
            val s = runCatching { LocalDate.parse(input.startDate) }.getOrNull()
            val e = runCatching { LocalDate.parse(input.endDate) }.getOrNull()
            if (s != null && e != null && !e.isBefore(s)) {
                val yearsDiff = e.year - s.year
                val monthsDiff = e.monthValue - s.monthValue
                val daysDiff = e.dayOfMonth - s.dayOfMonth
                maxOf(0.0, (yearsDiff * 12 + monthsDiff + (if (daysDiff >= 0) 0 else -1) + (if (daysDiff > 0) 0.5 else 0.0)).toDouble())
            } else 0.0
        } else {
            maxOf(0.0, input.manualYears) * 12 + maxOf(0.0, input.manualMonths)
        }
        val fullYears = (totalMonths / 12).toInt()
        val rem = maxOf(0.0, totalMonths - fullYears * 12)
        val nMonths = when {
            totalMonths <= 0 -> 0.0
            rem == 0.0 -> fullYears.toDouble()
            rem < 6 -> fullYears + 0.5
            else -> (fullYears + 1).toDouble()
        }
        return Tenure(totalMonths, fullYears, String.format("%.1f", rem).toDouble(), nMonths)
    }

    fun calc(input: Input): Result {
        val tenure = calcTenure(input)
        val avg = maxOf(0.0, input.avgSalary)
        val lastMonth = if (input.lastMonthSalary > 0) input.lastMonthSalary else avg
        val cap = maxOf(1000.0, input.tripleCap)

        if (input.scenario == Scenario.DOUBLE_WAGE) {
            val m = minOf(11.0, maxOf(0.0, input.unsignedMonths))
            val total = avg * m
            return Result(
                total = total, isCapped = false, effectiveBase = avg, effectiveN = m,
                noticePay = 0.0, tenure = tenure,
                steps = listOf(
                    "法定规则：用工满 1 个月次日起至满 1 年前一日，未签书面劳动合同每月另付 1 倍工资差额，法定最高 11 个月。",
                    "计算公式：月均应发工资 ${fmt2(avg)} 元 × ${m.toInt()} 个月 = ${fmt2(total)} 元",
                    "仲裁时效提示：未签书面合同二倍工资差额适用 1 年仲裁时效（多数地区按月逐月起算或连续计算，建议尽快核验时效）。"
                )
            )
        }

        val isCapped = avg > cap
        val effectiveBase = if (isCapped) cap else avg
        val effectiveN = if (isCapped) minOf(tenure.nMonths, 12.0) else tenure.nMonths
        val baseN = effectiveBase * effectiveN
        var total = baseN
        var noticePay = 0.0
        val steps = mutableListOf<String>()

        steps += "工龄折算（N）：累计工作 ${tenure.fullYears} 年 ${tenure.remMonths.toInt()} 个月 → 依法折算为 ${fmtN(tenure.nMonths)} 个月" +
            if (isCapped && tenure.nMonths > 12) "（触发高薪封顶，支付年限以 12 年封顶 → 实计 12 个月）" else ""

        if (isCapped) {
            steps += "高薪三倍封顶触发（《劳动合同法》第47条第2款）：离职前 12 个月税前月均工资 ${fmt2(avg)} 元 ＞ 设定的三倍社平基数 ${fmt2(cap)} 元，月补偿基数按 ${fmt2(cap)} 元封顶。"
        } else {
            steps += "月补偿基数：离职前 12 个月应发（税前）月均工资 ${fmt2(avg)} 元（未超三倍社平基数，全额计入）。"
        }

        when (input.scenario) {
            Scenario.N -> {
                total = baseN
                steps += "经济补偿金（N）：${fmt2(effectiveBase)} × ${fmtN(effectiveN)} = ${fmt2(baseN)} 元"
            }
            Scenario.N_PLUS_1 -> {
                noticePay = lastMonth
                total = baseN + noticePay
                steps += "经济补偿金（N）：${fmt2(effectiveBase)} × ${fmtN(effectiveN)} = ${fmt2(baseN)} 元"
                steps += "代通知金（+1）：按解除前 1 个月工资标准核计 ${fmt2(noticePay)} 元（《劳动合同法实施条例》第20条，不适用 12 年年限封顶）"
            }
            Scenario.TWICE_N -> {
                total = baseN * 2
                steps += "违法解除赔偿金（2N）：经济补偿标准 ${fmt2(baseN)} 元 × 2 = ${fmt2(total)} 元"
                steps += "实务提醒：主张 2N 赔偿金后，通常不再重复支持 N 或代通知金（+1）。"
            }
            Scenario.DOUBLE_WAGE -> Unit
        }

        return Result(total, isCapped, effectiveBase, effectiveN, noticePay, steps, tenure)
    }

    fun summaryText(input: Input, r: Result): String = buildString {
        appendLine("【${input.regionName} · 劳动争议经济补偿测算 · ${input.scenario.title}】")
        if (input.scenario == Scenario.DOUBLE_WAGE) {
            appendLine("未签合同计薪月数：${r.effectiveN.toInt()} 个月")
        } else {
            appendLine("折算工龄：${fmtN(r.tenure.nMonths)} 个月（${r.tenure.fullYears}年${r.tenure.remMonths.toInt()}个月）")
        }
        appendLine("离职前12个月税前月均工资：${fmt2(input.avgSalary)} 元")
        if (r.isCapped) appendLine("高薪三倍封顶基数：${fmt2(input.tripleCap)} 元（已触发封顶）")
        appendLine("测算参考总额：¥ ${fmt2(r.total)}")
        appendLine("计算明细：")
        r.steps.forEach { appendLine("- $it") }
    }

    fun fmt2(v: Double): String = String.format("%,.2f", v)
    fun fmtN(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
}
