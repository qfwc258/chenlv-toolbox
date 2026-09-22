package com.wb.mdgw.injury

/**
 * 湖南工伤赔偿计算器（2025 湖南标准）
 * 计算依据：
 * - 本人工资：按 60%~300% 统筹地区上年度职工月平均工资封顶/保底（BASE_2025 = 7694 元）
 * - 一次性伤残补助金、一次性工伤医疗补助金、一次性伤残就业补助金：按对应月数 × 本人工资
 * - 生活护理费：统筹工资 × 护理等级比例，按月发放，不计入一次性总额
 * - 1-4 级保留劳动关系（不得解除）；5-10 级解除劳动关系时领取医疗/就业补助金，并按距退休年龄扣减
 * - 工亡：一次性工亡补助金（1083760 元）+ 丧葬补助金（6 个月统筹工资），供养亲属抚恤金按月发放不计入总额
 *
 * 本类为纯 Kotlin 计算逻辑，不依赖 Android，便于在 JVM 上直接单测。金额中间过程用
 * Double、最终统一四舍五入到「分」，避免 Float 误差出现 71,999.99 这类尾差；法律口径不变。
 */
class InjuryCalculator {
    companion object {
        const val BASE_2025 = 7694f                      // 2025 年湖南统筹地区上年度职工月平均工资（元）
        const val HOSPITAL_FOOD_PER_DAY = 20f            // 住院伙食补助标准（元/天）
        const val INJURY_DEATH_ONE_TIME = 1083760f       // 一次性工亡补助金（元）
        const val FUNERAL_SUBSIDY = 6 * BASE_2025        // 丧葬补助金 = 6 个月统筹工资

        // 一次性伤残补助金对应月数（1-10 级）
        val DISABILITY_ONCE = mapOf(
            1 to 27f, 2 to 25f, 3 to 23f, 4 to 21f,
            5 to 18f, 6 to 16f, 7 to 13f, 8 to 11f, 9 to 9f, 10 to 7f
        )
        // 一次性工伤医疗补助金对应月数（5-10 级，解除劳动关系时）
        val MEDICAL_ONCE = mapOf(5 to 24f, 6 to 18f, 7 to 15f, 8 to 10f, 9 to 8f, 10 to 6f)
        // 一次性伤残就业补助金对应月数（5-10 级，解除劳动关系时）
        val EMPLOY_ONCE = mapOf(5 to 36f, 6 to 30f, 7 to 15f, 8 to 10f, 9 to 8f, 10 to 6f)
        // 生活护理费比例（统筹工资）
        val LIFE_CARE_RATE = mapOf(
            CareType.NONE to 0f, CareType.FULL to 0.5f,
            CareType.MOST to 0.4f, CareType.PART to 0.3f
        )
        // 法定退休年龄
        val RETIRE_AGE = mapOf(
            SexType.MALE to 60f,
            SexType.FEMALE_WORKER to 50f,
            SexType.FEMALE_CADRE to 55f
        )
    }

    /** 金额四舍五入到分（货币最小单位），消除浮点尾差 */
    private fun r2(v: Double): Float = (Math.round(v * 100.0) / 100.0).toFloat()

    /**
     * 年龄扣减系数（Double 保证比例精确）：距法定退休≥5 年不扣减；≤0 年全部扣减；
     * 中间每少 1 年扣 20%。
     */
    private fun deductRate(age: Float, retireAge: Float): Double {
        val remainYear = retireAge - age
        return when {
            remainYear >= 5f -> 1.0
            remainYear <= 0f -> 0.0
            else -> 1.0 - (5f - remainYear) * 0.2
        }
    }

    fun calculate(case: InjuryCase): CalcResult {
        val fundItems = linkedMapOf<String, Float>()      // 工伤保险基金支付
        val employerItems = linkedMapOf<String, Float>() // 用人单位支付
        var note = ""

        // 本人工资封顶/保底：60%~300% 统筹工资
        val wageD = case.wage.coerceIn(BASE_2025 * 0.6f, BASE_2025 * 3f).toDouble()

        if (case.rank == Rank.DEATH) {
            fundItems["一次性工亡补助金"] = r2(INJURY_DEATH_ONE_TIME.toDouble())
            fundItems["丧葬补助金"] = r2(FUNERAL_SUBSIDY.toDouble())
            note = "供养亲属抚恤金按月发放，不计入一次性总额"
        } else {
            val rankInt = case.rank.toInt()

            // 一次性伤残补助金（基金）
            fundItems["一次性伤残补助金"] = r2(DISABILITY_ONCE[rankInt]!!.toDouble() * wageD)

            // 生活护理费（基金，按月）
            val careRate = LIFE_CARE_RATE[case.careType]!!
            if (careRate > 0) {
                fundItems["生活护理费(按月)"] = r2(careRate.toDouble() * BASE_2025)
                note += "生活护理费按月发放，不计入一次性总额；"
            }

            // 解除劳动关系时的一次性医疗/就业补助金（仅 5-10 级）
            if (case.breakRelation && rankInt in 5..10) {
                val retireAge = RETIRE_AGE[case.sexType]!!
                val rate = deductRate(case.age, retireAge)
                fundItems["一次性医疗补助金"] = r2(MEDICAL_ONCE[rankInt]!!.toDouble() * wageD * rate)
                employerItems["一次性就业补助金"] = r2(EMPLOY_ONCE[rankInt]!!.toDouble() * wageD * rate)
                note += "年龄扣减比例:${"%.0f".format(rate * 100)}%"
            }

            // 停工留薪期工资（单位）
            val stopWage = case.stopMonth.toDouble() * wageD
            if (stopWage > 0) employerItems["停工留薪期工资"] = r2(stopWage)
        }

        // 住院伙食补助费（基金）
        val hospitalFood = case.hospitalDay * HOSPITAL_FOOD_PER_DAY
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

        // 一次性总额不含按月发放的生活护理费
        val totalFund = r2(fundItems.filterKeys { it != "生活护理费(按月)" }.values.sum().toDouble())
        val totalEmployer = r2(employerItems.values.sum().toDouble())
        val grandTotal = r2(totalFund.toDouble() + totalEmployer.toDouble())

        return CalcResult(
            fundItems = fundItems,
            employerItems = employerItems,
            totalFund = totalFund,
            totalEmployer = totalEmployer,
            grandTotal = grandTotal,
            note = note
        )
    }
}
