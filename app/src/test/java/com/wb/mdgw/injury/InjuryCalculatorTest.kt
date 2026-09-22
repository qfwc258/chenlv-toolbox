package com.wb.mdgw.injury

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工伤赔偿计算器单测：覆盖十级全链路、伤残津贴（1-4 级法定 / 5-6 级难安排）、
 * 工亡供养亲属抚恤金、核心参数可覆盖。均为纯 JVM 逻辑，无需 Android 环境。
 */
class InjuryCalculatorTest {
    private val calc = InjuryCalculator()
    private val W = 7694f // 湖南 2025 统筹工资

    @Test
    fun 十级一次性伤残补助金月数为7() {
        val r = calc.calculate(InjuryCase(rank = Rank.LEVEL_10, wage = W))
        assertEquals(7f * W, r.fundItems["一次性伤残补助金"]!!, 0.01f)
    }

    @Test
    fun 十级解除劳动关系按年龄扣减医疗与就业补助金() {
        // 男 58 岁，距退休 2 年 → 扣减比例 1 - 3*0.2 = 0.4
        val c = InjuryCase(
            rank = Rank.LEVEL_10, wage = W, breakRelation = true,
            age = 58f, sexType = SexType.MALE
        )
        val r = calc.calculate(c)
        assertEquals(6f * W * 0.4f, r.fundItems["一次性医疗补助金"]!!, 0.01f)
        assertEquals(6f * W * 0.4f, r.employerItems["一次性就业补助金"]!!, 0.01f)
    }

    @Test
    fun 一级伤残津贴按月90且不计入一次性总额() {
        val r = calc.calculate(InjuryCase(rank = Rank.LEVEL_1, wage = W))
        assertEquals(0.9f * W, r.monthlyItems["伤残津贴(按月)"]!!, 0.01f)
        // 一次性总额仅含一次性伤残补助金（27 个月），不含按月津贴
        assertEquals(27f * W, r.totalFund, 0.01f)
        assertEquals(0f, r.totalEmployer, 0.01f)
    }

    @Test
    fun 五六级难以安排工作发伤残津贴70与60() {
        val r5 = calc.calculate(InjuryCase(rank = Rank.LEVEL_5, wage = W, difficultToArrange = true))
        assertEquals(0.7f * W, r5.monthlyItems["伤残津贴(按月)"]!!, 0.01f)

        val r6 = calc.calculate(InjuryCase(rank = Rank.LEVEL_6, wage = W, difficultToArrange = true))
        assertEquals(0.6f * W, r6.monthlyItems["伤残津贴(按月)"]!!, 0.01f)
    }

    @Test
    fun 工亡供养亲属抚恤金配偶40加其他30按月() {
        // 配偶 + 1 其他亲属 → 0.4 + 0.3 = 0.7
        val r = calc.calculate(
            InjuryCase(rank = Rank.DEATH, wage = W, pensionSpouse = true, pensionOther = 1)
        )
        assertEquals(0.7f * W, r.monthlyItems["供养亲属抚恤金(按月)"]!!, 0.01f)
        // 工亡一次性总额 = 工亡补助金 + 丧葬（6 个月统筹工资）
        val expected = InjuryParams.DEFAULT.deathOneTime + 6f * InjuryParams.DEFAULT.baseMonthlyWage
        assertEquals(expected, r.totalFund, 0.01f)
    }

    @Test
    fun 核心参数可覆盖统筹工资() {
        val custom = InjuryParams(baseMonthlyWage = 5000f)
        val r = calc.calculate(InjuryCase(rank = Rank.LEVEL_10, wage = 5000f), custom)
        assertEquals(7f * 5000f, r.fundItems["一次性伤残补助金"]!!, 0.01f)
    }
}
