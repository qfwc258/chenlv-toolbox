package com.wb.mdgw.calc

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * LPR 逾期付款利息与迟延履行金计算器（04）——纯 Kotlin/JVM 引擎。
 * 移植自 qwertyzhu/cn-lawyer-ai-toolbox（src/tools/website/LprInterestCalculator.tsx）。
 * 内置央行 2019-08 至 2026-11 完整 LPR 报价表，按调息日分段计息，
 * 叠加《最高法关于执行程序中计算迟延履行期间的债务利息适用法律若干问题的解释》日万分之 1.75。
 */
object LprEngine {

    @Serializable
    data class Milestone(val date: String, val r1y: Double, val r5y: Double)

    /** 央行 LPR 报价变更历史默认表（核验至 2026-11-20，2025-05-20 后无变动）；用户可在界面覆盖 */
    val LPR_HISTORY = listOf(
        Milestone("2019-08-20", 4.25, 4.85),
        Milestone("2019-09-20", 4.20, 4.85),
        Milestone("2019-11-20", 4.15, 4.80),
        Milestone("2020-02-20", 4.05, 4.75),
        Milestone("2020-04-20", 3.85, 4.65),
        Milestone("2021-12-20", 3.80, 4.65),
        Milestone("2022-01-20", 3.70, 4.60),
        Milestone("2022-05-20", 3.70, 4.45),
        Milestone("2022-08-22", 3.65, 4.30),
        Milestone("2023-06-20", 3.55, 4.20),
        Milestone("2023-08-21", 3.45, 4.20),
        Milestone("2024-02-20", 3.45, 3.95),
        Milestone("2024-07-22", 3.35, 3.85),
        Milestone("2024-10-21", 3.10, 3.60),
        Milestone("2025-05-20", 3.00, 3.50)
    )

    const val DELAY_DAILY_RATE = 0.000175
    val LATEST_LPR: Milestone get() = LPR_HISTORY.last()
    val FOUR_TIMES_LATEST: Double get() = String.format("%.1f", LATEST_LPR.r1y * 4).toDouble()

    /** 把用户编辑后的表规整为按日期升序、非空；空表回退为默认表 */
    fun normalizeHistory(list: List<Milestone>?): List<Milestone> =
        if (list.isNullOrEmpty()) LPR_HISTORY else list.sortedBy { it.date }

    enum class Mode(val id: String, val label: String) {
        LPR_FLOATING("lpr_floating", "LPR 分段浮动计息"),
        LPR_FIXED("lpr_fixed", "起算日 LPR 固定"),
        CUSTOM_RATE("custom_rate", "合同约定固定年利率"),
        DELAY_ONLY("delay_only", "仅计算加倍迟延履行金");

        companion object { fun from(id: String): Mode = entries.firstOrNull { it.id == id } ?: LPR_FLOATING }
    }

    enum class Term(val id: String, val label: String) {
        ONE_YEAR("1y", "1 年期"), FIVE_YEAR("5y", "5 年期以上");

        companion object { fun from(id: String): Term = entries.firstOrNull { it.id == id } ?: ONE_YEAR }
    }

    data class Segment(
        val from: String,
        val to: String,
        val days: Long,
        val baseRate: Double,
        val effectiveRate: Double,
        val interest: Double,
        val note: String
    )

    data class Input(
        val mode: Mode = Mode.LPR_FLOATING,
        val principal: Double = 500_000.0,
        val startDate: String = "2024-06-01",
        val endDate: String = "",
        val term: Term = Term.ONE_YEAR,
        val multiplier: Double = 1.0,
        val bps: Double = 0.0,
        val customRate: Double = 6.0,
        val dayBasis: Int = 365,
        val includeDelay: Boolean = false,
        val delayStartDate: String = "2025-08-01",
        /** 用户可编辑的 LPR 报价表；为空时用内置默认表 */
        val history: List<Milestone> = LPR_HISTORY
    )

    data class Result(
        val totalDays: Long,
        val segments: List<Segment>,
        val normalInterest: Double,
        val delayDays: Long,
        val delayInterest: Double,
        val totalInterest: Double,
        val grandTotal: Double,
        val fourTimesLatestLpr: Double,
        val exceedsPrivateLendingCap: Boolean,
        val startBeforeLpr: Boolean
    )

    fun lookupLprOn(dateIso: String, term: Term, history: List<Milestone> = LPR_HISTORY): Milestone {
        val table = normalizeHistory(history)
        var active = table.first()
        for (item in table) {
            if (item.date <= dateIso) active = item else break
        }
        return active
    }

    private fun rateOf(m: Milestone, term: Term): Double = if (term == Term.ONE_YEAR) m.r1y else m.r5y

    fun calc(input: Input): Result {
        val end = input.endDate.ifBlank { LocalDate.now().toString() }
        val start = input.startDate
        val table = normalizeHistory(input.history)
        val latest = table.last()
        val fourTimesLatest = String.format("%.1f", latest.r1y * 4).toDouble()
        val totalDays = diffDays(start, end)
        val segments = mutableListOf<Segment>()
        var normalInterest = 0.0

        if (totalDays > 0 && input.principal > 0 && input.mode != Mode.DELAY_ONLY) {
            when (input.mode) {
                Mode.CUSTOM_RATE -> {
                    val interest = input.principal * (input.customRate / 100) * totalDays / input.dayBasis
                    normalInterest = interest
                    segments += Segment(start, end, totalDays, input.customRate, input.customRate, interest,
                        "合同约定固定年利率 ${input.customRate.format2()}%")
                }
                Mode.LPR_FIXED -> {
                    val snap = lookupLprOn(start, input.term, table)
                    val base = rateOf(snap, input.term)
                    val eff = maxOf(0.0, base * input.multiplier + input.bps / 100)
                    val interest = input.principal * (eff / 100) * totalDays / input.dayBasis
                    normalInterest = interest
                    segments += Segment(start, end, totalDays, base, eff, interest,
                        "锁定起算日当期${input.term.label}（${snap.date} 报价 ${base.format2()}%）")
                }
                Mode.LPR_FLOATING -> {
                    val cuts = mutableListOf(start)
                    for (item in table) {
                        if (item.date > start && item.date < end) {
                            val prevRate = rateOf(lookupLprOn(addDaysIso(item.date, -1), input.term, table), input.term)
                            val nextRate = rateOf(item, input.term)
                            if (prevRate != nextRate) cuts += item.date
                        }
                    }
                    cuts += end
                    for (i in 0 until cuts.size - 1) {
                        val from = cuts[i]
                        val to = cuts[i + 1]
                        val days = diffDays(from, to)
                        if (days <= 0) continue
                        val snap = lookupLprOn(from, input.term, table)
                        val base = rateOf(snap, input.term)
                        val eff = maxOf(0.0, base * input.multiplier + input.bps / 100)
                        val interest = input.principal * (eff / 100) * days / input.dayBasis
                        normalInterest += interest
                        segments += Segment(from, to, days, base, eff, interest,
                            "${input.term.label} LPR ${base.format2()}%")
                    }
                }
                else -> Unit
            }
        }

        var delayDays = 0L
        var delayInterest = 0.0
        if (input.mode == Mode.DELAY_ONLY) {
            delayDays = totalDays
            delayInterest = input.principal * DELAY_DAILY_RATE * delayDays
        } else if (input.includeDelay && input.principal > 0) {
            delayDays = diffDays(input.delayStartDate, end)
            delayInterest = input.principal * DELAY_DAILY_RATE * delayDays
        }

        val totalInterest = normalInterest + delayInterest
        val effectiveRateCheck = when (input.mode) {
            Mode.CUSTOM_RATE -> input.customRate
            Mode.DELAY_ONLY -> 0.0
            else -> latest.r1y * input.multiplier + input.bps / 100
        }
        return Result(
            totalDays = totalDays,
            segments = segments,
            normalInterest = normalInterest,
            delayDays = delayDays,
            delayInterest = delayInterest,
            totalInterest = totalInterest,
            grandTotal = input.principal + totalInterest,
            fourTimesLatestLpr = fourTimesLatest,
            exceedsPrivateLendingCap = effectiveRateCheck > fourTimesLatest,
            startBeforeLpr = start < "2019-08-20"
        )
    }

    fun summaryText(input: Input, r: Result): String = buildString {
        appendLine("【逾期付款利息 / 迟延履行金计算明细表】")
        appendLine("计息方式：${input.mode.label} ｜ 本金：${fmt2(input.principal)} 元")
        appendLine("起算日：${input.startDate} ｜ 截止日：${input.endDate.ifBlank { LocalDate.now().toString() }} ｜ 总天数：${r.totalDays} 天")
        appendLine("基准：${input.term.label} ｜ 倍数：${input.multiplier.format2()} ｜ 加点：${input.bps.format2()} BP ｜ 计息基数：${input.dayBasis} 天")
        appendLine()
        r.segments.forEach { s ->
            appendLine("【${s.from} → ${s.to}｜${s.days} 天】${s.note}，实际年利率 ${s.effectiveRate.format2()}% → 利息 ${fmt2(s.interest)} 元")
        }
        appendLine("正常利息小计：${fmt2(r.normalInterest)} 元")
        if (r.delayDays > 0) appendLine("加倍迟延履行金（日万分之1.75 × ${r.delayDays} 天）：${fmt2(r.delayInterest)} 元")
        appendLine("利息合计：${fmt2(r.totalInterest)} 元")
        appendLine("本息合计：${fmt2(r.grandTotal)} 元")
        if (r.exceedsPrivateLendingCap) appendLine("⚠ 约定利率超过现行 4 倍 1 年期 LPR（${r.fourTimesLatestLpr.format2()}%），可能超出民间借贷司法保护上限")
        if (r.startBeforeLpr) appendLine("⚠ 起算日早于 2019-08-20（LPR 报价机制启动日），此前实务适用央行同期贷款基准利率，请分段核验")
    }

    fun diffDays(startIso: String, endIso: String): Long {
        val a = runCatching { LocalDate.parse(startIso) }.getOrNull() ?: return 0
        val b = runCatching { LocalDate.parse(endIso) }.getOrNull() ?: return 0
        return maxOf(0, ChronoUnit.DAYS.between(a, b))
    }

    private fun addDaysIso(iso: String, delta: Long): String {
        val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
        return d.plusDays(delta).toString()
    }

    fun fmt2(v: Double): String = String.format("%,.2f", v)
}

private fun Double.format2(): String = String.format("%.2f", this)
