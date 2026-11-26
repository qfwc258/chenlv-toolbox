package com.wb.mdgw.calc

import kotlinx.serialization.Serializable

/**
 * 律师费标准与客户报价单（03）——纯 Kotlin 引擎。
 * 移植自 qwertyzhu/cn-lawyer-ai-toolbox（lawyer-fee-workbench.html），
 * 内置全国 31 省（35 条）政府指导价/律协指引，保留分段累进与风险代理上限算法。
 */

@Serializable
data class FeeSegJson(
    /** 本段终点（元），-1 表示无穷大 */
    val to: Double,
    /** 费率区间 [下限%, 上限%] */
    val r: List<Double>? = null,
    /** 固定金额区间 [下限, 上限] */
    val fix: List<Double>? = null,
    /** 标准未列明的协商段 */
    val neg: Boolean = false
)

@Serializable
data class FeeAdjJson(val down: String? = null, val up: Double? = null)

@Serializable
data class FeePropJson(
    val segs: List<FeeSegJson> = emptyList(),
    val min: Double = 0.0,
    val base: List<Double>? = null,
    val adj: FeeAdjJson? = null
)

/** 重大疑难复杂案件上浮倍数（参照源码 std.cx） */
@Serializable
data class FeeCxJson(
    val crim: Double? = null,
    val prop: Double? = null,
    val np: Double? = null
)

@Serializable
data class FeeStandardJson(
    val id: String = "",
    val p: String = "",
    val n: String = "",
    val d: String = "",
    val st: String = "",
    val g: String = "",
    /** 适用范围原文（如湖南仅限民生类案件）——避免把窄口径标准误用为通用标准 */
    val scope: String = "",
    /** 是否窄口径标准（政府指导价仅覆盖部分案件类型） */
    val narrow: Boolean = false,
    val stNote: String = "",
    val notes: String = "",
    val prop: FeePropJson? = null,
    val cx: FeeCxJson? = null,
    val hr: List<Double>? = null
)

object LawyerFeeEngine {

    enum class Scheme(val id: String, val label: String) {
        STANDARD("std", "常规收费"),
        SEMI_RISK("semi", "基础费＋风险提成"),
        FULL_RISK("risk", "全风险代理"),
        HOURLY("hr", "计时收费");

        companion object { fun from(id: String): Scheme = entries.firstOrNull { it.id == id } ?: STANDARD }
    }

    data class FeeRow(
        val label: String,
        val lo: Double?,
        val hi: Double?,
        val note: String = ""
    )

    data class FeeRange(
        val lo: Double?,
        val hi: Double,
        val rows: List<FeeRow>,
        val minApplied: Boolean = false,
        val loFree: Boolean = false,
        val negotiated: Boolean = false
    )

    /** 司发通〔2021〕87号风险代理分段上限 */
    val RISK_CAP_SEGS = listOf(
        1_000_000.0 to 18.0, 5_000_000.0 to 15.0, 10_000_000.0 to 12.0,
        50_000_000.0 to 9.0, Double.MAX_VALUE to 6.0
    )

    const val RISK_PROHIBITED =
        "刑事诉讼案件、行政诉讼案件、国家赔偿案件、群体性诉讼案件、婚姻继承案件，以及请求给予社会保险待遇、最低生活保障待遇、赡养费、抚养费、扶养费、抚恤金、救济金、工伤赔偿、劳动报酬的案件"

    /** 分段累进计算：返回各段金额区间与合计 */
    fun calcSegs(prop: FeePropJson, amount: Double): FeeRange {
        val rows = mutableListOf<FeeRow>()
        var lo = 0.0
        var hi = 0.0
        var prev = 0.0
        var negotiated = false
        prop.base?.let { b ->
            val baseLo = b.getOrNull(0) ?: 0.0
            val baseHi = b.getOrNull(1) ?: 0.0
            lo += baseLo
            hi += baseHi
            rows += FeeRow("基础费", baseLo, baseHi)
        }
        for (seg in prop.segs) {
            val to = if (seg.to < 0) Double.MAX_VALUE else seg.to
            if (amount <= prev) break
            val portion = minOf(amount, to) - prev
            // 参照源码：s.neg 为标准未列明的协商段；fix/r 均空的旧数据也作协商处理
            if (seg.neg || (seg.r == null && seg.fix == null)) {
                rows += FeeRow("协商确定", null, null, "${fmt0(prev)} – ${fmt0(to)} 元部分协商")
                negotiated = true
                break
            }
            val l: Double
            val h: Double
            if (seg.fix != null) {
                l = seg.fix.getOrNull(0) ?: 0.0
                h = seg.fix.getOrNull(1) ?: 0.0
            } else {
                val r = seg.r!!
                l = portion * (r.getOrNull(0) ?: 0.0) / 100
                h = portion * (r.getOrNull(1) ?: 0.0) / 100
            }
            val rateLabel = seg.r?.let { "（${it.getOrNull(0) ?: 0.0}% – ${it.getOrNull(1) ?: 0.0}%）" } ?: ""
            rows += FeeRow(
                "${fmt0(prev)} – ${if (to == Double.MAX_VALUE) "以上" else fmt0(to)} 元${rateLabel}",
                round2(l), round2(h),
                "${fmt0(portion)} 元 × ${seg.r?.getOrNull(1) ?: 0.0}%"
            )
            lo += l
            hi += h
            prev = to
        }
        var minApplied = false
        if (prop.min > 0) {
            if (lo < prop.min) { lo = prop.min; minApplied = true }
            if (hi < prop.min) { hi = prop.min; minApplied = true }
        }
        var loFree = false
        var finalHi = hi
        val adj = prop.adj
        val downRaw = adj?.down
        val upVal = adj?.up
        if (downRaw == "free") loFree = true
        else if (downRaw != null) lo *= (1 - (downRaw.toDoubleOrNull() ?: 0.0))
        if (upVal != null) finalHi *= (1 + upVal)
        return FeeRange(
            lo = if (loFree) null else round2(lo), hi = round2(finalHi), rows = rows,
            minApplied = minApplied, loFree = loFree, negotiated = negotiated
        )
    }

    /** 风险代理收费合计上限（分段累进） */
    fun calcRiskCap(amount: Double): FeeRange {
        var hi = 0.0
        var prev = 0.0
        val rows = mutableListOf<FeeRow>()
        for ((to, rate) in RISK_CAP_SEGS) {
            if (amount <= prev) break
            val portion = minOf(amount, to) - prev
            val fee = portion * rate / 100
            hi += fee
            rows += FeeRow("${fmt0(prev)} – ${if (to == Double.MAX_VALUE) "以上" else fmt0(to)} 元（${rate}%）", null, round2(fee))
            prev = to
        }
        return FeeRange(lo = null, hi = round2(hi), rows = rows)
    }

    /** 风险代理：基础费 + 实现金额 × 提成率，受 87 号上限约束 */
    data class RiskResult(val raw: Double, val cap: Double, val fee: Double, val over: Boolean, val baseOver: Boolean)

    fun riskFee(base: Double, ratePct: Double, realized: Double, capOn: Boolean): RiskResult {
        val raw = round2(base + realized * ratePct / 100)
        val cap = calcRiskCap(if (realized > 0) realized else 0.0).hi
        val fee = if (capOn) minOf(raw, maxOf(cap, base)) else raw
        return RiskResult(raw, cap, fee, raw > cap + 0.005, base > cap + 0.005)
    }

    /** 计时收费：费率 × 小时，可封顶 */
    fun hourlyFee(rate: Double, hours: Double, cap: Double): Double {
        val raw = round2(rate * hours)
        return if (cap > 0) minOf(raw, cap) else raw
    }

    data class QuoteInput(
        val scheme: Scheme = Scheme.STANDARD,
        val standard: FeeStandardJson? = null,
        val amount: Double = 100_000.0,
        val baseFee: Double = 0.0,
        val riskRatePct: Double = 10.0,
        val realized: Double = 0.0,
        val capOn: Boolean = true,
        val hourlyRate: Double = 500.0,
        val hours: Double = 10.0,
        val hourlyCap: Double = 0.0,
        val forbidRisk: Boolean = false,
        /** 重大疑难复杂案件：按参照源码 std.cx 放大上限 */
        val complex: Boolean = false
    )

    data class QuoteResult(
        val headline: String,
        val sub: String,
        val rows: List<FeeRow>,
        val steps: List<String>,
        val riskCap: FeeRange? = null,
        /** 复杂案件上浮倍数（1 表示未上浮） */
        val cxMul: Double = 1.0,
        /** 窄口径标准的适用范围提示（非空时界面需显著提示） */
        val scopeWarning: String = ""
    )

    fun quote(input: QuoteInput): QuoteResult = when (input.scheme) {
        Scheme.STANDARD -> {
            val std = input.standard
            if (std == null || std.prop == null) {
                QuoteResult("标准待核", "所选省份无财产案件分段标准，可能为市场调节价，请按当地律协/律所标准协商。", emptyList(),
                    listOf("该省份收费标准已全面放开或未公布政府指导价，需与律所协商确定。"))
            } else {
                val r = calcSegs(std.prop, input.amount)
                // 参照源码：重大疑难复杂案件按 std.cx.prop 放大上限
                var cxMul = 1.0
                var finalHi = r.hi
                if (input.complex && std.cx?.prop != null && std.cx.prop > 1.0) {
                    cxMul = std.cx.prop!!
                    finalHi *= cxMul
                }
                val finalLo = if (r.lo == null) null else round2(r.lo!! * cxMul)
                val head = if (finalLo == null) "¥ ${fmt2(round2(finalHi))}（下限协商）" else
                    if (finalLo == round2(finalHi)) "¥ ${fmt2(round2(finalHi))}" else "¥ ${fmt2(finalLo)} – ¥ ${fmt2(round2(finalHi))}"
                val steps = mutableListOf<String>()
                steps += "依据：${std.n.ifBlank { std.p + "标准" }}（${std.d}）${statusLabel(std.st)}"
                if (r.minApplied) steps += "已适用最低收费 ${fmt2(std.prop.min)} 元"
                if (r.loFree) steps += "下限已放开（可下浮不限），实际收费由双方协商"
                if (r.negotiated) steps += "部分分段为标准未列明的协商区间"
                if (cxMul > 1.0) steps += "重大疑难复杂案件：上限按标准允许上浮 ${cxMul.format1()} 倍 → ${fmt2(round2(finalHi))} 元"
                if (std.notes.isNotBlank()) steps += std.notes
                // 窄口径标准（如湖南仅限民生类案件）必须显著提示，防止误用为普通财产案件标准
                val scopeWarn = if (std.narrow && std.scope.isNotBlank())
                    "⚠ 该标准为窄口径政府指导价：${std.scope}普通民商事财产案件可能不适用，请核对当地律协/市场调节价。"
                else ""
                if (scopeWarn.isNotBlank()) steps += scopeWarn
                QuoteResult(head, "${std.p} · ${std.d}", r.rows, steps, cxMul = cxMul, scopeWarning = scopeWarn)
            }
        }
        Scheme.SEMI_RISK, Scheme.FULL_RISK -> {
            val base = if (input.scheme == Scheme.FULL_RISK) 0.0 else input.baseFee
            val rr = riskFee(base, input.riskRatePct, input.realized, input.capOn)
            val capInfo = calcRiskCap(input.realized)
            val head = "¥ ${fmt2(rr.fee)}"
            val steps = mutableListOf<String>(
                "基础费 ${fmt2(base)} 元 + 实现金额 ${fmt2(input.realized)} 元 × ${input.riskRatePct.format1()}% = ${fmt2(rr.raw)} 元",
                "司发通〔2021〕87号风险代理合计上限：${fmt2(rr.cap)} 元",
                if (rr.over) "⚠ raw 超上限，已按 capOn 规则处理" else "未超风险代理上限"
            )
            if (rr.baseOver) steps += "⚠ 基础费本身已超上限（实现金额过低时需注意）"
            if (input.forbidRisk) steps += "⚠ 本案属依法禁止风险代理案件，请确认合规"
            QuoteResult(head, if (input.scheme == Scheme.FULL_RISK) "全风险代理" else "基础费＋风险提成",
                listOf(FeeRow("基础费", base, base), FeeRow("风险提成（${input.riskRatePct.format1()}%）", null,
                    round2(input.realized * input.riskRatePct / 100)), FeeRow("87号上限", null, rr.cap)),
                steps, capInfo)
        }
        Scheme.HOURLY -> {
            val fee = hourlyFee(input.hourlyRate, input.hours, input.hourlyCap)
            val rows = listOf(FeeRow("计时收费（${input.hourlyRate.format0()} 元/小时 × ${input.hours.format1()} 小时）", fee, fee))
            val steps = mutableListOf("计时收费：${fmt2(input.hourlyRate)} × ${input.hours.format1()} = ${fmt2(input.hourlyRate * input.hours)} 元")
            if (input.hourlyCap > 0) steps += "已适用封顶 ${fmt2(input.hourlyCap)} 元"
            QuoteResult("¥ ${fmt2(fee)}", "计时收费", rows, steps)
        }
    }

    fun statusLabel(st: String): String = when (st) {
        "current" -> "（现行政府指导价）"
        "bar" -> "（律协行业指引）"
        "expired" -> "（已失效，仅供参考）"
        "liberal" -> "（已全面放开）"
        "unknown" -> "（状态待核）"
        else -> ""
    }

    fun summaryText(input: QuoteInput, r: QuoteResult): String = buildString {
        appendLine("【律师费测算 · ${input.scheme.label}】")
        appendLine("地区：${input.standard?.p ?: "—"} ｜ 依据：${input.standard?.d ?: "—"}")
        if (input.scheme == Scheme.STANDARD || input.scheme == Scheme.SEMI_RISK || input.scheme == Scheme.FULL_RISK) {
            appendLine("争议标的额：${fmt2(input.amount)} 元")
        }
        appendLine("测算律师费：${r.headline}")
        appendLine("计算明细：")
        r.rows.forEach { row ->
            val v = when {
                row.lo != null && row.hi != null && row.lo == row.hi -> fmt2(row.hi!!)
                row.lo != null && row.hi != null -> "${fmt2(row.lo!!)} – ${fmt2(row.hi!!)}"
                row.hi != null -> fmt2(row.hi!!)
                else -> "协商"
            }
            appendLine("- ${row.label}：$v ${row.note}")
        }
        r.steps.forEach { appendLine("- $it") }
    }

    fun round2(v: Double): Double = String.format("%.2f", v).toDouble()
    fun fmt2(v: Double): String = String.format("%,.2f", v)
    fun fmt0(v: Double): String = String.format("%,.0f", v)
}

private fun Double.format0(): String = String.format("%.0f", this)
private fun Double.format1(): String = String.format("%.1f", this)
