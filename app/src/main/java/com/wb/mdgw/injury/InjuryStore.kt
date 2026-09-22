package com.wb.mdgw.injury

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable

/**
 * 工伤案件的本地持久化（单案件，覆盖保存，口径与原《湖南工伤计算器》一致）。
 *
 * 核心模型 [InjuryCase] 刻意保持零序列化依赖以便纯 JVM 单测，故这里用 [InjuryCaseDto]
 * 作为 JSON 草稿，在 UI/存储层与核心模型互转（与 catalog 草稿的处理方式一致）。
 */
@Serializable
private data class NamedCostDto(
    val name: String = "",
    val amount: Float = 0f
)

@Serializable
private data class InjuryCaseDto(
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
    val otherCosts: List<NamedCostDto> = emptyList()
)

private fun InjuryCase.toDto() = InjuryCaseDto(
    name, sexType, age, rank, wage, breakRelation, careType,
    stopMonth, hospitalDay, medicalCost, rehabCost, assistCost,
    otherCosts.map { NamedCostDto(it.name, it.amount) }
)

private fun InjuryCaseDto.toCase() = InjuryCase(
    name, sexType, age, rank, wage, breakRelation, careType,
    stopMonth, hospitalDay, medicalCost, rehabCost, assistCost,
    otherCosts.map { NamedCost(it.name, it.amount) }
)

object InjuryStore : JsonFileStore<InjuryCaseDto>() {
    override val fileName: String = "injury_case.json"
    override fun serializer() = InjuryCaseDto.serializer()

    /** 读取上次保存的案件；无则返回 null */
    fun loadCase(context: Context): InjuryCase? = read(context)?.toCase()

    /** 覆盖保存当前案件 */
    fun saveCase(context: Context, case: InjuryCase) = write(context, case.toDto())

    /** 删除已保存案件 */
    fun erase(context: Context) = clear(context)
}
