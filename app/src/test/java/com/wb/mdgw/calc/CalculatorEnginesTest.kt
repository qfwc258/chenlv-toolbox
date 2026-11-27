package com.wb.mdgw.calc

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 新增离线工具（02/03/04/08）纯 JVM 引擎单测。
 */
class CalculatorEnginesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---------- 02 诉讼费 ----------

    @Test
    fun `litigation property fee tiers are progressive`() {
        val (f0, _) = LitigationFeeEngine.calcProperty(0.0)
        assertEquals(50.0, f0, 0.001)
        val (f1, _) = LitigationFeeEngine.calcProperty(10_000.0)
        assertEquals(50.0, f1, 0.001)
        // 10万 = 50 + 90000*2.5% = 2300
        val (f2, _) = LitigationFeeEngine.calcProperty(100_000.0)
        assertEquals(2300.0, f2, 0.001)
        // 20万 = 2300 + 100000*2% = 4300
        val (f3, _) = LitigationFeeEngine.calcProperty(200_000.0)
        assertEquals(4300.0, f3, 0.001)
        // 50万 = 4300 + 300000*1.5% = 8800
        val (f4, _) = LitigationFeeEngine.calcProperty(500_000.0)
        assertEquals(8800.0, f4, 0.001)
    }

    @Test
    fun `litigation preservation capped at 5000 and execution fee`() {
        val (pres, _) = LitigationFeeEngine.calcPreservation(2_000_000.0)
        assertEquals(5000.0, pres, 0.001)
        val (_, _) = LitigationFeeEngine.calcPreservation(1000.0).also { assertEquals(30.0, it.first, 0.001) }
        // 执行 100万 = 50 + 490000*1.5% + 500000*1% = 50+7350+5000 = 12400
        val (exec, _) = LitigationFeeEngine.calcExecution(1_000_000.0)
        assertEquals(12400.0, exec, 0.001)
    }

    @Test
    fun `litigation calculate halved and lifecycle`() {
        val r = LitigationFeeEngine.calculate(LitigationFeeEngine.CaseType.PROPERTY, 200_000.0, halved = true)
        assertTrue(r.headline.contains("2,150.00"))
        assertTrue(r.hasLifecycle)
        assertEquals(4300.0, r.firstFull!!, 0.001)
        val labor = LitigationFeeEngine.calculate(LitigationFeeEngine.CaseType.LABOR, 0.0, halved = true)
        assertEquals("¥ 5.00", labor.headline)
    }

    // ---------- 04 LPR ----------

    @Test
    fun `lpr lookup and fixed mode interest`() {
        assertEquals(3.85, LprEngine.lookupLprOn("2020-06-01", LprEngine.Term.ONE_YEAR).r1y, 0.001)
        assertEquals(3.45, LprEngine.lookupLprOn("2023-09-01", LprEngine.Term.ONE_YEAR).r1y, 0.001)
        // 固定：50万，年利率按起算日 1 年期 LPR 3.45%，365 天
        val r = LprEngine.calc(LprEngine.Input(
            mode = LprEngine.Mode.LPR_FIXED, principal = 500_000.0,
            startDate = "2023-09-01", endDate = "2024-09-01", dayBasis = 365
        ))
        assertEquals(366, r.totalDays)
        assertEquals(500_000.0 * 0.0345 * 366 / 365, r.normalInterest, 1.0)
        assertEquals(1, r.segments.size)
    }

    @Test
    fun `lpr floating splits at rate change boundaries`() {
        // 2024-06-01 到 2024-12-01 跨越 2024-07-22 (3.45→3.35) 和 2024-10-21 (3.35→3.10)
        val r = LprEngine.calc(LprEngine.Input(
            mode = LprEngine.Mode.LPR_FLOATING, principal = 1_000_000.0,
            startDate = "2024-06-01", endDate = "2024-12-01"
        ))
        assertTrue("应分 3 段，实际 ${r.segments.size}", r.segments.size == 3)
        assertEquals(3.45, r.segments[0].baseRate, 0.001)
        assertEquals(3.35, r.segments[1].baseRate, 0.001)
        assertEquals(3.10, r.segments[2].baseRate, 0.001)
    }

    @Test
    fun `lpr delay only uses daily 1p75 per ten-thousand`() {
        val r = LprEngine.calc(LprEngine.Input(
            mode = LprEngine.Mode.DELAY_ONLY, principal = 100_000.0,
            startDate = "2025-01-01", endDate = "2025-01-11"
        ))
        assertEquals(10, r.totalDays)
        assertEquals(100_000.0 * 0.000175 * 10, r.delayInterest, 0.001)
        assertEquals(0.0, r.normalInterest, 0.001)
    }

    @Test
    fun `lpr custom history is used in calculation`() {
        // 用户自定义表：仅一档 2020-01-01 起 1年期 6%、5年期 7%
        val custom = listOf(LprEngine.Milestone("2020-01-01", 6.0, 7.0))
        val r = LprEngine.calc(LprEngine.Input(
            mode = LprEngine.Mode.LPR_FIXED, principal = 100_000.0,
            startDate = "2024-01-01", endDate = "2025-01-01", dayBasis = 365,
            history = custom
        ))
        assertEquals(1, r.segments.size)
        assertEquals(6.0, r.segments[0].baseRate, 0.001)
        assertEquals(366, r.totalDays) // 2024 闰年
        assertEquals(100_000.0 * 0.06 * 366 / 365, r.normalInterest, 1.0)
        // 4倍上限应基于自定义表最新 1 年期 6% → 24%
        assertEquals(24.0, r.fourTimesLatestLpr, 0.001)
        // 反序列化 round-trip（store 用 ListSerializer）
        val listSer = kotlinx.serialization.builtins.ListSerializer(LprEngine.Milestone.serializer())
        val encoded = json.encodeToString(listSer, custom)
        assertEquals(custom, json.decodeFromString(listSer, encoded))
    }

    @Test
    fun `labor default region hunan changsha 2026`() {
        assertEquals("湖南·长沙 2026", LaborParams.DEFAULT.regionName)
        assertEquals(32744.0, LaborParams.DEFAULT.tripleCap, 0.001)
        assertEquals(32744.0, LaborEngine.Input().tripleCap, 0.001)
    }

    // ---------- 08 劳动补偿金 ----------

    @Test
    fun `labor tenure n months rounding`() {
        val input = LaborEngine.Input(tenureMode = "manual", manualYears = 3.0, manualMonths = 4.0)
        val t = LaborEngine.calcTenure(input)
        assertEquals(40.0, t.totalMonths, 0.001)
        assertEquals(3, t.fullYears)
        assertEquals(3.5, t.nMonths, 0.001)
        val input2 = LaborEngine.Input(tenureMode = "manual", manualYears = 3.0, manualMonths = 8.0)
        assertEquals(4.0, LaborEngine.calcTenure(input2).nMonths, 0.001)
    }

    @Test
    fun `labor N plus 1 and 2N with cap`() {
        val input = LaborEngine.Input(
            scenario = LaborEngine.Scenario.N_PLUS_1,
            manualYears = 3.0, manualMonths = 4.0,
            avgSalary = 12_000.0, lastMonthSalary = 12_000.0,
            tripleCap = 25_611.0
        )
        val r = LaborEngine.calc(input)
        assertEquals(12_000.0 * 3.5, r.effectiveBase * r.effectiveN, 0.001)
        assertEquals(12_000.0 * 3.5 + 12_000.0, r.total, 0.001)
        assertFalse(r.isCapped)

        val cappedInput = input.copy(scenario = LaborEngine.Scenario.TWICE_N, avgSalary = 40_000.0)
        val cr = LaborEngine.calc(cappedInput)
        assertTrue(cr.isCapped)
        assertEquals(25_611.0, cr.effectiveBase, 0.001)
        assertEquals(25_611.0 * 3.5 * 2, cr.total, 0.01)
    }

    @Test
    fun `labor double wage max 11 months`() {
        val r = LaborEngine.calc(LaborEngine.Input(
            scenario = LaborEngine.Scenario.DOUBLE_WAGE,
            avgSalary = 10_000.0, unsignedMonths = 15.0
        ))
        assertEquals(11.0, r.effectiveN, 0.001)
        assertEquals(110_000.0, r.total, 0.001)
    }

    // ---------- 03 律师费 ----------

    private fun standards(): List<FeeStandardJson> {
        val candidates = listOf(
            File("app/src/main/assets/lawyer_fee_standards.json"),
            File("src/main/assets/lawyer_fee_standards.json")
        )
        val f = candidates.firstOrNull { it.isFile } ?: throw IllegalStateException("找不到律师费标准 JSON")
        return json.decodeFromString(f.readText(Charsets.UTF_8))
    }

    @Test
    fun `lawyer standards asset has 35 entries with prop data`() {
        val s = standards()
        assertEquals(35, s.size)
        assertTrue(s.count { it.prop?.segs?.isNotEmpty() == true } >= 30)
        assertTrue(s.any { it.p == "北京" })
        assertTrue(s.any { it.p == "浙江" })
    }

    @Test
    fun `lawyer beijing property fee progressive`() {
        val bj = standards().first { it.p == "北京" }
        // 北京: 10万内10%, 100万内6%, 1000万内4%, 以上2%; min 3000, down=free
        val prop = bj.prop ?: throw AssertionError("北京标准缺少 prop")
        val r = LawyerFeeEngine.calcSegs(prop, 500_000.0)
        // 10万*10%=1万 + 40万*6%=2.4万 = 3.4万
        assertEquals(34_000.0, r.hi, 1.0)
        assertTrue(r.loFree)
    }

    @Test
    fun `lawyer risk cap 87 hao progressive`() {
        // 100万*18%=18万 + 400万*15%=60万 = 78万 (累计500万)
        val r = LawyerFeeEngine.calcRiskCap(5_000_000.0)
        assertEquals(780_000.0, r.hi, 1.0)
        val rr = LawyerFeeEngine.riskFee(10_000.0, 10.0, 2_000_000.0, capOn = true)
        val raw = 10_000.0 + 2_000_000.0 * 0.10
        assertEquals(raw, rr.raw, 0.01)
        // cap = 18万 + 100万*15% = 33万
        assertEquals(330_000.0, rr.cap, 1.0)
    }

    @Test
    fun `lawyer hourly fee with cap`() {
        assertEquals(5000.0, LawyerFeeEngine.hourlyFee(500.0, 10.0, 0.0), 0.001)
        assertEquals(3000.0, LawyerFeeEngine.hourlyFee(500.0, 10.0, 3000.0), 0.001)
    }

    @Test
    fun `湖南10万标的为定额1500至3000且带窄口径警告`() {
        val hn = standards().first { it.p == "湖南" }
        val q = LawyerFeeEngine.quote(
            LawyerFeeEngine.QuoteInput(scheme = LawyerFeeEngine.Scheme.STANDARD, standard = hn, amount = 100_000.0)
        )
        // 参照源码 hn2016：10万以下为 fix[1500,3000]，是定额区间而非比例累进
        assertTrue(q.headline.contains("1,500"))
        assertTrue(q.headline.contains("3,000"))
        assertTrue(q.scopeWarning.contains("民生"))
        assertTrue(q.steps.any { it.contains("仅限") })
        assertTrue(hn.narrow)
        assertTrue(hn.scope.contains("民生"))
    }

    @Test
    fun `湖南复杂案件按cx上浮3倍`() {
        val hn = standards().first { it.p == "湖南" }
        val base = LawyerFeeEngine.quote(
            LawyerFeeEngine.QuoteInput(standard = hn, amount = 100_000.0)
        )
        val complex = LawyerFeeEngine.quote(
            LawyerFeeEngine.QuoteInput(standard = hn, amount = 100_000.0, complex = true)
        )
        assertEquals(3.0, complex.cxMul, 0.01)
        // 上限 3000 × 3 = 9000
        assertTrue(complex.headline.contains("9,000"))
        assertTrue(complex.steps.any { it.contains("3.0 倍") })
        assertTrue(base.cxMul == 1.0)
    }

    @Test
    fun `数据包含参照字段scope cx neg`() {
        val s = standards()
        assertTrue(s.count { it.scope.isNotBlank() } >= 30)
        assertTrue(s.any { it.cx?.prop != null })
        assertTrue(s.first { it.p == "山东" }.prop?.segs?.any { it.neg } == true)
    }

    @Test
    fun `LPR倍数快捷值含1点3与1点5与4倍`() {
        // 快捷按钮为 UI 提供固定候选，校验引擎对这些倍数的计息单调性
        val input = LprEngine.Input(
            mode = LprEngine.Mode.LPR_FIXED, principal = 100_000.0,
            startDate = "2024-06-01", endDate = "2025-06-01", multiplier = 1.0
        )
        val x1 = LprEngine.calc(input).normalInterest
        val x13 = LprEngine.calc(input.copy(multiplier = 1.3)).normalInterest
        val x15 = LprEngine.calc(input.copy(multiplier = 1.5)).normalInterest
        val x4 = LprEngine.calc(input.copy(multiplier = 4.0)).normalInterest
        assertTrue(x1 < x13 && x13 < x15 && x15 < x4)
        assertEquals(x1 * 1.3, x13, 0.01)
    }
}
