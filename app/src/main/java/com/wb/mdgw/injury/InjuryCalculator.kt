package com.wb.mdgw.injury

import kotlin.math.min

/**
 * 湖南工伤赔偿计算器。
 *
 * 计算依据（口径以湖南 2025 标准、内置 [InjuryParams.DEFAULT] 为准，可在 UI 覆盖）：
 * - 本人工资：按 60%~300% 统筹地区上年度职工月平均工资封顶/保底；
 * - 一次性伤残补助金、一次性工伤医疗补助金、一次性伤残就业补助金：对应月数 × 本人工资；
 * - 生活护理费：统筹工资 × 护理等级比例，按月发放，不计入一次性总额；
 * - 伤残津贴：1-4 级保留劳动关系、退出岗位，法定按月（90/85/80/75%）；5-6 级难以安排
 *   工作时由用人单位按月（70/60%）；均不计入一次性总额；
 * - 1-4 级保留劳动关系（不得解除）；5-10 级解除劳动关系时领取医疗/就业补助金，并按距
 *   退休年龄扣减；
 * - 工亡：一次性工亡补助金 + 丧葬补助金（6 个月统筹工资）；供养亲属抚恤金按月发放，
 *   不计入一次性总额；
 * - 停工留薪期工资：按本人工资由单位支付。
 *
 * 本类为纯 Kotlin 计算逻辑，不依赖 Android，便于在 JVM 上直接单测。金额中间过程用
 * Double、最终统一四舍五入到「分」，避免 Float 误差出现 71,999.99 这类尾差。
 */
class InjuryCalculator {

    /** 金额四舍五入到分（货币最小单位），消除浮点尾差 */
    private fun r2(v: Double): Float = (Math.round(v * 100.0) / 100.0).toFloat()

    /**
     * 年龄扣减系数（Double 保证比例精确）：距法定退休≥5 年不扣减；不足 5 年每少 1 年
     * 扣 20%；按规定最高减除额不超过全额的 90%，即系数下限 0.1（最少发 10%）。
     */
    private fun deductRate(age: Float, retireAge: Float): Double {
        val remainYear = retireAge - age
        return when {
            remainYear >= 5f -> 1.0
            else -> maxOf(0.1, 1.0 - (5f - remainYear) * 0.2)
        }
    }

    /**
     * 供养亲属抚恤金总比例：配偶 40%、其他亲属每人 30%；孤寡老人或孤儿在其他亲属
     * 30% 的基础上再增加 10%，即每人 40%。各供养亲属抚恤金之和不得超过因工死亡
     * 职工生前的工资（总比例封顶 100%）。
     */
    private fun pensionRatio(case: InjuryCase): Double {
        var r = 0.0
        if (case.pensionSpouse) r += 0.4
        r += case.pensionOther * 0.3
        r += case.pensionOrphan * 0.4
        return min(r, 1.0)
    }

    fun calculate(case: InjuryCase, params: InjuryParams = InjuryParams.DEFAULT): CalcResult {
        val fundItems = linkedMapOf<String, Float>()      // 工伤保险基金支付（一次性）
        val employerItems = linkedMapOf<String, Float>() // 用人单位支付（一次性）
        val monthlyItems = linkedMapOf<String, Float>()  // 按月发放，不计入一次性总额
        var note = ""

        val base = params.baseMonthlyWage.toDouble()
        // 本人工资封顶/保底：60%~300% 统筹工资
        val wageD = case.wage.coerceIn(params.baseMonthlyWage * 0.6f, params.baseMonthlyWage * 3f).toDouble()
        val effectiveWage = wageD.toFloat()

        if (case.rank == Rank.DEATH) {
            fundItems["一次性工亡补助金"] = r2(params.urbanIncome.toDouble() * 20.0)
            fundItems["丧葬补助金"] = r2(6.0 * base)
            // 供养亲属抚恤金（可选，按月）
            val ratio = pensionRatio(case)
            if (ratio > 0) {
                monthlyItems["供养亲属抚恤金(按月)"] = r2(ratio * wageD)
                note += "供养亲属抚恤金按月发放，不计入一次性总额；"
            }
            note += "一次性工亡补助金=上年度全国城镇居民人均可支配收入×20；丧葬补助金=6个月统筹工资。"
        } else {
            val rankInt = case.rank.toInt()

            // 一次性伤残补助金（基金）
            fundItems["一次性伤残补助金"] = r2((params.disabilityOnceMonths[rankInt] ?: 0f).toDouble() * wageD)

            // 生活护理费（基金，按月）
            val careRate = params.lifeCareRate[case.careType] ?: 0f
            if (careRate > 0) {
                monthlyItems["生活护理费(按月)"] = r2(careRate.toDouble() * base)
                note += "生活护理费按月发放，不计入一次性总额；"
            }

            // 伤残津贴（按月）：1-4 级法定保留劳动关系、退出岗位，由工伤保险基金按月；
            // 5-6 级难以安排工作时由用人单位按月发放（需勾选 difficultToArrange）
            val allowRate = params.disabilityAllowanceRate[rankInt] ?: 0f
            val allowEligible = rankInt in 1..4 || (rankInt in 5..6 && case.difficultToArrange)
            if (allowRate > 0 && allowEligible) {
                val byFund = rankInt in 1..4
                val label = if (byFund) "伤残津贴(基金按月)" else "伤残津贴(单位按月)"
                monthlyItems[label] = r2(allowRate.toDouble() * wageD)
                note += (if (byFund) "工伤保险基金" else "用人单位") + "按月发放伤残津贴，不计入一次性总额；"
            }

            // 解除劳动关系时的一次性医疗/就业补助金（仅 5-10 级）
            if (case.breakRelation && rankInt in 5..10) {
                val retireAge = params.retireAge[case.sexType] ?: 60f
                val rate = deductRate(case.age, retireAge)
                fundItems["一次性医疗补助金"] = r2((params.medicalOnceMonths[rankInt] ?: 0f).toDouble() * wageD * rate)
                employerItems["一次性就业补助金"] = r2((params.employOnceMonths[rankInt] ?: 0f).toDouble() * wageD * rate)
                note += "年龄扣减比例:${"%.0f".format(rate * 100)}%"
            }

            // 停工留薪期工资（单位）
            val stopWage = case.stopMonth.toDouble() * wageD
            if (stopWage > 0) employerItems["停工留薪期工资"] = r2(stopWage)
        }

        // 住院伙食补助费（基金）
        val hospitalFood = case.hospitalDay * params.hospitalFoodPerDay
        if (hospitalFood > 0) fundItems["住院伙食补助费"] = r2(hospitalFood.toDouble())

        // 实报实销项目（基金）
        if (case.medicalCost > 0) fundItems["医疗费"] = r2(case.medicalCost.toDouble())
        if (case.rehabCost > 0) fundItems["康复费"] = r2(case.rehabCost.toDouble())
        if (case.assistCost > 0) fundItems["辅助器具费"] = r2(case.assistCost.toDouble())

        // 用户自定义的其他实报实销费用（同名合并，避免 Map 覆盖）
        case.otherCosts.forEach { c ->
            if (c.amount > 0) {
                val key = c.name.trim().ifBlank { "其他费用" }
                fundItems[key] = r2((fundItems[key] ?: 0f).toDouble() + c.amount.toDouble())
            }
        }

        // 一次性总额不含按月发放的项目
        val totalFund = r2(fundItems.values.sum().toDouble())
        val totalEmployer = r2(employerItems.values.sum().toDouble())
        val grandTotal = r2(totalFund.toDouble() + totalEmployer.toDouble())

        return CalcResult(
            fundItems = fundItems,
            employerItems = employerItems,
            monthlyItems = monthlyItems,
            effectiveWage = effectiveWage,
            totalFund = totalFund,
            totalEmployer = totalEmployer,
            grandTotal = grandTotal,
            note = note
        )
    }
}
