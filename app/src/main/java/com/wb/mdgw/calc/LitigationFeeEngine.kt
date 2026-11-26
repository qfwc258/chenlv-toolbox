package com.wb.mdgw.calc

/**
 * 诉讼费与诉讼全成本计算器（02）——纯 Kotlin 引擎，零 Android 依赖。
 * 依据《诉讼费用交纳办法》第十三条，移植自 qwertyzhu/cn-lawyer-ai-toolbox
 * （src/tools/website/LitigationFeeCalculator.tsx），保留原始算法口径。
 */
object LitigationFeeEngine {

    /** 案件类型：是否支持简易/调解/撤诉减半、是否使用标的额 */
    enum class CaseType(
        val id: String,
        val label: String,
        val hint: String,
        val supportsHalved: Boolean,
        val usesAmount: Boolean
    ) {
        PROPERTY("property", "财产案件（合同/借款/侵权）", "按争议标的额分段累进计算", true, true),
        INTELLECTUAL("intellectual", "知识产权案件", "有争议金额按财产标准，无争议金额每件 500–1000 元", true, true),
        LABOR("labor", "劳动争议案件", "每件交纳 10 元（减半 5 元）", true, false),
        DIVORCE("divorce", "离婚案件", "每件 50–300 元，财产超 20 万部分加收 0.5%", true, true),
        PERSONALITY("personality", "侵害人格权案件", "每件 100–500 元，赔偿超 5 万分段加收", true, true),
        EXECUTION("execution", "申请强制执行费", "按执行金额分段，无金额每件 50–500 元", false, true),
        PRESERVATION("preservation", "财产保全申请费", "按保全金额分段，最高 5000 元封顶", false, true),
        ADMINISTRATIVE("administrative", "行政案件", "商标/专利/海事每件 100 元，其他 50 元", true, false),
        NON_PROPERTY("nonProperty", "其他非财产案件", "每件交纳 50–100 元", true, false);

        companion object {
            fun from(id: String): CaseType = entries.firstOrNull { it.id == id } ?: PROPERTY
        }
    }

    data class Result(
        val headline: String,
        val sub: String,
        val steps: List<String>,
        val firstFull: Double? = null,
        val firstHalf: Double? = null,
        val preservation: Double? = null,
        val appeal: Double? = null,
        val execution: Double? = null
    ) {
        val hasLifecycle: Boolean get() = firstFull != null
    }

    data class Tier(val max: Double, val rate: Double, val label: String)

    val PROPERTY_TIERS = listOf(
        Tier(10_000.0, 0.0, "0 – 1 万元部分：50.00 元"),
        Tier(100_000.0, 0.025, "1 万 – 10 万元部分 (2.5%)"),
        Tier(200_000.0, 0.02, "10 万 – 20 万元部分 (2.0%)"),
        Tier(500_000.0, 0.015, "20 万 – 50 万元部分 (1.5%)"),
        Tier(1_000_000.0, 0.01, "50 万 – 100 万元部分 (1.0%)"),
        Tier(2_000_000.0, 0.009, "100 万 – 200 万元部分 (0.9%)"),
        Tier(5_000_000.0, 0.008, "200 万 – 500 万元部分 (0.8%)"),
        Tier(10_000_000.0, 0.007, "500 万 – 1,000 万元部分 (0.7%)"),
        Tier(20_000_000.0, 0.006, "1,000 万 – 2,000 万元部分 (0.6%)"),
        Tier(Double.MAX_VALUE, 0.005, "超过 2,000 万元部分 (0.5%)")
    )

    /** 财产案件受理费：1 万元内固定 50，超出分段累进 */
    fun calcProperty(amount: Double): Pair<Double, List<String>> {
        if (amount <= 0) return 50.0 to listOf("标的额不超过 1 万元：固定 50 元")
        val steps = mutableListOf<String>()
        var total = 50.0
        steps += "0 – 1 万元部分：50.00 元"
        var prev = 10_000.0
        for (i in 1 until PROPERTY_TIERS.size) {
            val tier = PROPERTY_TIERS[i]
            if (amount <= prev) break
            val part = kotlin.math.min(amount, tier.max) - prev
            val fee = part * tier.rate
            total += fee
            steps += "${tier.label}：${fmt(part)} × ${String.format("%.1f", tier.rate * 100)}% = ${fmt2(fee)} 元"
            prev = tier.max
        }
        return total to steps
    }

    /** 执行申请费分段 */
    fun calcExecution(amount: Double): Pair<Double, List<String>> {
        if (amount <= 0) return 50.0 to listOf("无执行金额或不超过 1 万元：每件 50 – 500 元（按 50 元起计）")
        val steps = mutableListOf<String>()
        var total = 50.0
        steps += "0 – 1 万元部分：50.00 元"
        val tiers = listOf(
            Triple(10_000.0, 500_000.0, "1 万 – 50 万元部分 (1.5%)") to 0.015,
            Triple(500_000.0, 5_000_000.0, "50 万 – 500 万元部分 (1.0%)") to 0.01,
            Triple(5_000_000.0, 10_000_000.0, "500 万 – 1,000 万元部分 (0.5%)") to 0.005,
            Triple(10_000_000.0, Double.MAX_VALUE, "超过 1,000 万元部分 (0.1%)") to 0.001
        )
        for ((t, rate) in tiers) {
            val (min, max, label) = t
            if (amount <= min) break
            val part = kotlin.math.min(amount, max) - min
            val fee = part * rate
            total += fee
            steps += "$label：${fmt(part)} × ${String.format("%.1f", rate * 100)}% = ${fmt2(fee)} 元"
        }
        return total to steps
    }

    /** 财产保全申请费，5000 封顶 */
    fun calcPreservation(amount: Double): Pair<Double, List<String>> {
        if (amount <= 1000) return 30.0 to listOf("财产数额不超过 1,000 元或不涉及财产数额：30 元")
        val steps = mutableListOf<String>()
        var total = 30.0
        steps += "0 – 1,000 元部分：30.00 元"
        if (amount > 1000) {
            val p1 = kotlin.math.min(amount, 100_000.0) - 1000
            val f1 = p1 * 0.01
            total += f1
            steps += "1,000 元 – 10 万元部分 (1%)：${fmt2(f1)} 元"
        }
        if (amount > 100_000) {
            val p2 = amount - 100_000
            val f2 = p2 * 0.005
            total += f2
            steps += "超过 10 万元部分 (0.5%)：${fmt2(f2)} 元"
        }
        if (total > 5000) {
            steps += "累计 ${fmt2(total)} 元，依法定 5,000 元封顶"
            total = 5000.0
        }
        return total to steps
    }

    /**
     * 主计算入口。
     * @param amount 标的额（元）
     * @param hasAmount 是否涉及金额（知产案件用）
     * @param halved 是否简易/调解/撤诉减半
     * @param adminSpecial 是否商标/专利/海事行政案件
     */
    fun calculate(
        type: CaseType,
        amount: Double,
        hasAmount: Boolean = true,
        halved: Boolean = false,
        adminSpecial: Boolean = false
    ): Result {
        val half: (Double) -> Double = { v -> if (halved && type.supportsHalved) v / 2 else v }
        return when (type) {
            CaseType.PROPERTY -> {
                val (total, steps) = calcProperty(amount)
                val (pres, _) = calcPreservation(amount)
                val (exec, _) = calcExecution(amount)
                Result(
                    headline = yuan(half(total)),
                    sub = if (halved) "全额 ${fmt2(total)} 元，简易程序/调解/撤诉减半" else "一审普通程序全额受理费",
                    steps = steps,
                    firstFull = total, firstHalf = total / 2, preservation = pres, appeal = total, execution = exec
                )
            }
            CaseType.INTELLECTUAL -> {
                if (!hasAmount) {
                    Result(
                        headline = if (halved) "¥ 250.00 – ¥ 500.00" else "¥ 500.00 – ¥ 1,000.00",
                        sub = if (halved) "无争议金额知产案件（减半估算）" else "无争议金额或价额，每件交纳 500 至 1,000 元",
                        steps = listOf("未涉及争议金额或价额：依法每件交纳 500 元至 1,000 元") +
                            if (halved) listOf("简易程序/调解/撤诉减半：250 – 500 元") else emptyList()
                    )
                } else {
                    val (total, steps) = calcProperty(amount)
                    val (pres, _) = calcPreservation(amount)
                    val (exec, _) = calcExecution(amount)
                    Result(
                        headline = yuan(half(total)),
                        sub = if (halved) "有争议金额全额 ${fmt2(total)} 元，简易/调解/撤诉减半" else "知识产权涉案金额按财产案件标准全额计收",
                        steps = steps,
                        firstFull = total, firstHalf = total / 2, preservation = pres, appeal = total, execution = exec
                    )
                }
            }
            CaseType.LABOR -> Result(
                headline = if (halved) "¥ 5.00" else "¥ 10.00",
                sub = if (halved) "劳动争议每件 10 元（简易/调解/撤诉减半）" else "劳动争议案件每件固定交纳 10 元",
                steps = listOf("《诉讼费用交纳办法》第十三条第（四）项：劳动争议案件每件交纳 10 元")
            )
            CaseType.DIVORCE -> {
                val extra = if (amount > 200_000) (amount - 200_000) * 0.005 else 0.0
                Result(
                    headline = "${yuan(half(50 + extra))} – ${yuan(half(300 + extra))}",
                    sub = if (halved) "含超出 20 万元财产分割部分（已减半）" else "每件 50 – 300 元，财产总额超 20 万元部分加收 0.5%",
                    steps = listOf(
                        "基础受理费：每件 50 – 300 元",
                        if (amount > 200_000) "财产超 20 万元部分：(${fmt(amount)} - 200,000) × 0.5% = ${fmt2(extra)} 元"
                        else "财产总额未超过 20 万元，不另行加收"
                    )
                )
            }
            CaseType.PERSONALITY -> {
                var extra = 0.0
                val steps = mutableListOf("基础受理费：每件 100 – 500 元")
                if (amount > 50_000) {
                    val p1 = kotlin.math.min(amount, 100_000.0) - 50_000
                    extra += p1 * 0.01
                    steps += "5 万 – 10 万元部分 (1%)：${fmt2(p1 * 0.01)} 元"
                }
                if (amount > 100_000) {
                    val p2 = amount - 100_000
                    extra += p2 * 0.005
                    steps += "超过 10 万元部分 (0.5%)：${fmt2(p2 * 0.005)} 元"
                }
                Result(
                    headline = "${yuan(half(100 + extra))} – ${yuan(half(500 + extra))}",
                    sub = if (halved) "侵害人格权案件（已减半估算）" else "每件 100 – 500 元，损害赔偿超 5 万元部分分段加收",
                    steps = steps
                )
            }
            CaseType.EXECUTION -> {
                val (total, steps) = calcExecution(amount)
                Result(
                    headline = yuan(total),
                    sub = "申请执行费（依法由被执行人负担，一般无需申请人预交）",
                    steps = steps
                )
            }
            CaseType.PRESERVATION -> {
                val (total, steps) = calcPreservation(amount)
                Result(
                    headline = yuan(total),
                    sub = "财产保全申请费（法定最高封顶 5,000 元，不含保全担保/保函费）",
                    steps = steps
                )
            }
            CaseType.ADMINISTRATIVE -> {
                val base = if (adminSpecial) 100.0 else 50.0
                Result(
                    headline = yuan(half(base)),
                    sub = if (adminSpecial) "商标、专利、海事行政案件每件 100 元" else "其他行政案件每件 50 元",
                    steps = listOf(if (adminSpecial) "商标、专利、海事行政案件：100 元/件" else "一般行政案件：50 元/件")
                )
            }
            CaseType.NON_PROPERTY -> Result(
                headline = if (halved) "¥ 25.00 – ¥ 50.00" else "¥ 50.00 – ¥ 100.00",
                sub = if (halved) "其他非财产案件（已减半）" else "其他非财产案件每件交纳 50 至 100 元",
                steps = listOf("《诉讼费用交纳办法》第十三条第（二）项第3目：每件交纳 50 元至 100 元")
            )
        }
    }

    fun summaryText(type: CaseType, amount: Double, result: Result): String = buildString {
        appendLine("【诉讼费与诉讼全成本测算 · ${type.label}】")
        appendLine("标的额：${fmt2(amount)} 元")
        appendLine("受理费：${result.headline}")
        if (result.hasLifecycle) {
            appendLine("一审全额：${fmt2(result.firstFull!!)} 元 ｜ 简易减半：${fmt2(result.firstHalf!!)} 元")
            appendLine("财产保全费：${fmt2(result.preservation!!)} 元 ｜ 二审：${fmt2(result.appeal!!)} 元 ｜ 执行费：${fmt2(result.execution!!)} 元")
        }
        appendLine("计算明细：")
        result.steps.forEach { appendLine("- $it") }
    }

    fun fmt(amount: Double): String = String.format("%,.0f", amount)
    fun fmt2(amount: Double): String = String.format("%,.2f", amount)
    fun yuan(amount: Double): String = "¥ ${fmt2(amount)}"
}
