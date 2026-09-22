package com.wb.mdgw.injury

import kotlinx.serialization.Serializable

/**
 * 工伤赔偿计算的输入案件与结果（纯 Kotlin，零 Android / 序列化依赖，便于纯 JVM 单测）。
 *
 * 字段口径与原《湖南工伤计算器》一致：
 * - [sexType]：性别（决定法定退休年龄）；[rank]：伤残等级 1~10 或工亡；
 * - [careType]：生活护理依赖程度；[breakRelation]：是否解除劳动关系（仅 5~10 级可解除）；
 * - [difficultToArrange]：5~6 级难以安排工作（发伤残津贴）；[pension*]：工亡供养亲属。
 */
data class InjuryCase(
    val name: String = "",
    val sexType: String = SexType.MALE,
    val age: Float = 0f,
    val rank: String = Rank.LEVEL_1,
    val wage: Float = 0f,
    val breakRelation: Boolean = false,
    val careType: String = CareType.NONE,
    val stopMonth: Float = 0f,
    val hospitalDay: Int = 0,
    val medicalCost: Float = 0f,
    val rehabCost: Float = 0f,
    val assistCost: Float = 0f,
    // 医疗/康复/辅助器具之外、用户自定义的实报实销费用（计入工伤保险基金）
    val otherCosts: List<NamedCost> = emptyList(),
    // 5-6 级：难以安排工作，由用人单位按月发放伤残津贴
    val difficultToArrange: Boolean = false,
    // 工亡供养亲属抚恤金：配偶 / 其他亲属人数 / 孤儿或孤寡人数
    val pensionSpouse: Boolean = false,
    val pensionOther: Int = 0,
    val pensionOrphan: Int = 0
)

/** 自定义费用项（名称 + 金额） */
data class NamedCost(
    val name: String = "",
    val amount: Float = 0f
)

data class CalcResult(
    val fundItems: Map<String, Float>,
    val employerItems: Map<String, Float>,
    /** 按月发放的待遇（生活护理费 / 伤残津贴 / 供养亲属抚恤金），不计入一次性总额 */
    val monthlyItems: Map<String, Float>,
    /** 封顶 / 保底后的实际计薪工资（本人工资口径） */
    val effectiveWage: Float,
    val totalFund: Float,
    val totalEmployer: Float,
    val grandTotal: Float,
    val note: String
)

/** 性别：男 / 女工人 / 女干部（对应法定退休年龄 60 / 50 / 55） */
object SexType {
    const val MALE = "male"
    const val FEMALE_WORKER = "female_worker"
    const val FEMALE_CADRE = "female_cadre"
}

/** 伤残等级："1"~"10" 级，或工亡 */
object Rank {
    const val LEVEL_1 = "1"
    const val LEVEL_2 = "2"
    const val LEVEL_3 = "3"
    const val LEVEL_4 = "4"
    const val LEVEL_5 = "5"
    const val LEVEL_6 = "6"
    const val LEVEL_7 = "7"
    const val LEVEL_8 = "8"
    const val LEVEL_9 = "9"
    const val LEVEL_10 = "10"
    const val DEATH = "death"
}

/** 生活护理依赖：无 / 完全不能自理 / 大部分不能自理 / 部分不能自理 */
object CareType {
    const val NONE = "none"
    const val FULL = "full"
    const val MOST = "most"
    const val PART = "part"
}

/**
 * 工伤赔偿核心参数（可本地配置 / 更新）。
 *
 * 内置 [DEFAULT]「湖南 2025 标准」：统筹工资、住院伙食补助、一次性工亡补助金、
 * 各项补助金月数、护理依赖比例、法定退休年龄、伤残津贴比例、停工留薪期上限。
 * 政策逐年更新（统筹工资、工亡补助金等），可在「赔偿标准参数」中修改并保存，
 * 避免随版本硬编码过期。
 */
@Serializable
data class InjuryParams(
    /** 统筹地区上年度职工月平均工资（元） */
    val baseMonthlyWage: Float = 7694f,
    /** 住院伙食补助费标准（元/天） */
    val hospitalFoodPerDay: Float = 20f,
    /** 一次性工亡补助金（元） */
    val deathOneTime: Float = 1083760f,
    /** 一次性伤残补助金对应月数（1-10 级） */
    val disabilityOnceMonths: Map<Int, Float> = mapOf(
        1 to 27f, 2 to 25f, 3 to 23f, 4 to 21f,
        5 to 18f, 6 to 16f, 7 to 13f, 8 to 11f, 9 to 9f, 10 to 7f
    ),
    /** 一次性工伤医疗补助金对应月数（5-10 级，解除劳动关系时） */
    val medicalOnceMonths: Map<Int, Float> = mapOf(
        5 to 24f, 6 to 18f, 7 to 15f, 8 to 10f, 9 to 8f, 10 to 6f
    ),
    /** 一次性伤残就业补助金对应月数（5-10 级，解除劳动关系时） */
    val employOnceMonths: Map<Int, Float> = mapOf(
        5 to 36f, 6 to 30f, 7 to 15f, 8 to 10f, 9 to 8f, 10 to 6f
    ),
    /** 生活护理费比例（统筹工资） */
    val lifeCareRate: Map<String, Float> = mapOf(
        CareType.NONE to 0f, CareType.FULL to 0.5f,
        CareType.MOST to 0.4f, CareType.PART to 0.3f
    ),
    /** 法定退休年龄（岁） */
    val retireAge: Map<String, Float> = mapOf(
        SexType.MALE to 60f,
        SexType.FEMALE_WORKER to 50f,
        SexType.FEMALE_CADRE to 55f
    ),
    /** 伤残津贴比例（本人工资，1-6 级按月） */
    val disabilityAllowanceRate: Map<Int, Float> = mapOf(
        1 to 0.9f, 2 to 0.85f, 3 to 0.8f, 4 to 0.75f, 5 to 0.7f, 6 to 0.6f
    ),
    /** 停工留薪期上限（月，法定一般不超 12 个月，提示用） */
    val stopWorkMaxMonth: Float = 12f
) {
    companion object {
        val DEFAULT = InjuryParams()
    }
}
