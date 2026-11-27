package com.wb.mdgw.injury

import kotlinx.serialization.Serializable

/**
 * 人身损害 / 交通事故赔偿计算器——纯 Kotlin 引擎。
 * 移植自 qwertyzhu/cn-lawyer-ai-toolbox（src/tools/website/InjuryCompensationCalculator.tsx Tab 2）。
 * 城乡统一标准：按年龄折算赔偿年限、伤残系数、丧葬费、被扶养人生活费、交强险先行赔付与责任比例拆分。
 */

@Serializable
data class PersonInjuryParams(
    val regionName: String = "湖南 2026（城乡统一·湖南高院）",
    val urbanDisposable: Double = 53_369.0,   // 湖南2025年度城镇居民人均可支配收入（2026案件适用）
    val urbanConsumption: Double = 33_678.0,  // 湖南2025年度城镇居民人均消费支出
    val nonPrivateYearWage: Double = 105_294.0, // 湖南2025城镇非私营在岗职工年均工资（丧葬费=52647=÷2）
    val hospMealPerDay: Double = 100.0,        // 人损住院伙食补助（元/天）
    val nutritionPerDay: Double = 50.0         // 人损营养费（元/天）
) {
    companion object {
        val DEFAULT = PersonInjuryParams()
        const val COMPULSORY_MEDICAL_CAP = 18_000.0   // 交强险有责医疗限额
        const val COMPULSORY_DEATH_CAP = 180_000.0    // 交强险有责伤残死亡限额
    }
}

@Serializable
data class PersonInjuryCase(
    val grade: Int = 10,              // 1~10 级，0 = 死亡
    val age: Int = 38,
    val medicalFee: Double = 30_000.0,
    val lostDays: Double = 90.0,
    val dailyIncome: Double = 280.0,
    val nurseDays: Double = 30.0,
    val nursePerDay: Double = 220.0,
    val hospDays: Double = 15.0,
    val nutritionDays: Double = 60.0,
    val mentalFee: Double = 5_000.0,
    val dependentAnnualShare: Double = 0.0,
    val dependentYears: Double = 0.0,
    val faultPct: Double = 100.0,     // 对方责任比例
    val hasCompulsoryIns: Boolean = true
)

data class PersonInjuryItem(val name: String, val amount: Double, val formula: String)

data class PersonInjuryResult(
    val years: Int,
    val disabilityCoeff: Double,
    val items: List<PersonInjuryItem>,
    val medicalCategoryTotal: Double,
    val disabilityCategoryTotal: Double,
    val grossTotal: Double,
    val faultRatio: Double,
    val compulsoryPay: Double,
    val commercialOrPartyPay: Double,
    val estimatedRecoverable: Double
)

object PersonInjuryEngine {

    /** 伤残等级系数：1级=100% ... 10级=10%，0=死亡（100%） */
    fun coeff(grade: Int): Double = if (grade == 0) 1.0 else (11 - grade).coerceIn(0, 10) * 0.1

    /** 赔偿年限：≥75 岁 5 年；60~75 岁 20-(年龄-60)；<60 岁 20 年 */
    fun compensationYears(age: Int): Int {
        val a = age.coerceIn(0, 110)
        return when {
            a >= 75 -> 5
            a > 60 -> maxOf(5, 20 - (a - 60))
            else -> 20
        }
    }

    fun calc(c: PersonInjuryCase, p: PersonInjuryParams): PersonInjuryResult {
        val years = compensationYears(c.age)
        val coeff = coeff(c.grade)
        val medicalFee = maxOf(0.0, c.medicalFee)
        val lostIncome = maxOf(0.0, c.lostDays) * maxOf(0.0, c.dailyIncome)
        val nursingFee = maxOf(0.0, c.nurseDays) * maxOf(0.0, c.nursePerDay)
        val hospMeal = maxOf(0.0, c.hospDays) * maxOf(0.0, p.hospMealPerDay)
        val nutritionFee = maxOf(0.0, c.nutritionDays) * maxOf(0.0, p.nutritionPerDay)
        val mentalFee = maxOf(0.0, c.mentalFee)

        val disabilityOrDeathFee = maxOf(0.0, p.urbanDisposable) * years * coeff
        val funeralFee = if (c.grade == 0) maxOf(0.0, p.nonPrivateYearWage) / 2 else 0.0
        val depShare = maxOf(0.0, c.dependentAnnualShare)
        val depYrs = minOf(20.0, maxOf(0.0, c.dependentYears))
        val dependentFee = if (depShare > 0 && depYrs > 0) maxOf(0.0, p.urbanConsumption) * depYrs * depShare * coeff else 0.0

        val medicalCategoryTotal = medicalFee + hospMeal + nutritionFee
        val disabilityCategoryTotal = disabilityOrDeathFee + funeralFee + lostIncome + nursingFee + mentalFee + dependentFee
        val grossTotal = medicalCategoryTotal + disabilityCategoryTotal

        val faultRatio = (c.faultPct.coerceIn(0.0, 100.0)) / 100.0
        var compulsoryPay = 0.0
        var commercialOrPartyPay = grossTotal * faultRatio
        if (c.hasCompulsoryIns && faultRatio > 0) {
            val compMed = minOf(PersonInjuryParams.COMPULSORY_MEDICAL_CAP, medicalCategoryTotal)
            val compDis = minOf(PersonInjuryParams.COMPULSORY_DEATH_CAP, disabilityCategoryTotal)
            compulsoryPay = compMed + compDis
            val remainder = maxOf(0.0, grossTotal - compulsoryPay)
            commercialOrPartyPay = remainder * faultRatio
        }
        val estimatedRecoverable = compulsoryPay + commercialOrPartyPay

        val items = mutableListOf<PersonInjuryItem>()
        items += PersonInjuryItem(
            if (c.grade == 0) "死亡赔偿金（城乡统一标准）" else "残疾赔偿金（${c.grade}级 · 系数${(coeff * 100).toInt()}%）",
            disabilityOrDeathFee,
            "${fmt0(p.urbanDisposable)} 元/年 × ${years} 年（${c.age}岁）× ${(coeff * 100).toInt()}%"
        )
        if (funeralFee > 0) items += PersonInjuryItem("丧葬费", funeralFee, "非私营年均工资 ${fmt0(p.nonPrivateYearWage)} ÷ 2（6个月）")
        if (medicalFee > 0) items += PersonInjuryItem("医疗费", medicalFee, "按正式医疗票据核算")
        if (lostIncome > 0) items += PersonInjuryItem("误工费", lostIncome, "${fmt0(c.lostDays)} 天 × ${fmt0(c.dailyIncome)} 元/天")
        if (nursingFee > 0) items += PersonInjuryItem("护理费", nursingFee, "${fmt0(c.nurseDays)} 天 × ${fmt0(c.nursePerDay)} 元/天")
        if (hospMeal > 0) items += PersonInjuryItem("住院伙食补助费", hospMeal, "${fmt0(c.hospDays)} 天 × ${fmt0(p.hospMealPerDay)} 元/天")
        if (nutritionFee > 0) items += PersonInjuryItem("营养费", nutritionFee, "${fmt0(c.nutritionDays)} 天 × ${fmt0(p.nutritionPerDay)} 元/天")
        if (dependentFee > 0) items += PersonInjuryItem("被扶养人生活费", dependentFee,
            "${fmt0(p.urbanConsumption)} 元/年 × ${fmt0(depYrs)} 年 × 份额 ${depShare} × 系数 ${(coeff * 100).toInt()}%")
        if (mentalFee > 0) items += PersonInjuryItem("精神损害抚慰金", mentalFee, "结合伤残等级与过错程度由法院酌定")

        return PersonInjuryResult(
            years, coeff, items, medicalCategoryTotal, disabilityCategoryTotal, grossTotal,
            faultRatio, compulsoryPay, commercialOrPartyPay, estimatedRecoverable
        )
    }

    fun summaryText(c: PersonInjuryCase, p: PersonInjuryParams, r: PersonInjuryResult): String = buildString {
        appendLine("【${p.regionName} · 人身损害 / 交通事故赔偿测算 · ${if (c.grade == 0) "死亡" else "${c.grade}级伤残"}】")
        appendLine("受害人年龄：${c.age} 岁 ｜ 赔偿年限：${r.years} 年 ｜ 伤残系数：${(r.disabilityCoeff * 100).toInt()}%")
        appendLine("对方责任比例：${(r.faultRatio * 100).toInt()}% ｜ 交强险：${if (c.hasCompulsoryIns) "有责限额先行赔付" else "无/不计"}")
        appendLine()
        r.items.forEach { appendLine("${it.name}：${fmt2(it.amount)} 元（${it.formula}）") }
        appendLine()
        appendLine("医疗类小计：${fmt2(r.medicalCategoryTotal)} 元 ｜ 伤残死亡类小计：${fmt2(r.disabilityCategoryTotal)} 元")
        appendLine("赔偿总额：${fmt2(r.grossTotal)} 元")
        if (c.hasCompulsoryIns) appendLine("交强险先行赔付：${fmt2(r.compulsoryPay)} 元（医疗 1.8 万 + 伤残死亡 18 万限额）")
        appendLine("按责比后商业险/对方承担：${fmt2(r.commercialOrPartyPay)} 元")
        appendLine("预计可获赔合计：${fmt2(r.estimatedRecoverable)} 元")
        appendLine("结果仅供参考，以交警责任认定、伤残鉴定与法院认定为准。")
    }

    fun fmt2(v: Double): String = String.format("%,.2f", v)
    fun fmt0(v: Double): String = String.format("%,.0f", v)
}
