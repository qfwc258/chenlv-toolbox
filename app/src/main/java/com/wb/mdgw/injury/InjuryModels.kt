package com.wb.mdgw.injury

/**
 * 工伤赔偿计算的输入案件与结果（纯 Kotlin，零 Android / 序列化依赖，便于纯 JVM 单测）。
 *
 * 字段口径与原《湖南工伤计算器》完全一致：
 * - [sexType]：性别（决定法定退休年龄）；[rank]：伤残等级 1~10 或工亡；
 * - [careType]：生活护理依赖程度；[breakRelation]：是否解除劳动关系（仅 5~10 级可解除）。
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
    val otherCosts: List<NamedCost> = emptyList()
)

/** 自定义费用项（名称 + 金额） */
data class NamedCost(
    val name: String = "",
    val amount: Float = 0f
)

data class CalcResult(
    val fundItems: Map<String, Float>,
    val employerItems: Map<String, Float>,
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
