package com.wb.mdgw.calc

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer

/**
 * 劳动补偿金「地区口径」参数（三倍社平封顶基数）。
 * 默认湖南·长沙 2026：2025 长沙市城镇职工月均工资 10914.58 × 3 ≈ 32744 元。
 * 用户可在界面修改并本地保存，恢复默认即删除覆盖。
 */
@Serializable
data class LaborParams(
    val regionName: String = "湖南·长沙 2026",
    val tripleCap: Double = 32_744.0
) {
    companion object { val DEFAULT = LaborParams() }
}

object LaborParamsStore : JsonFileStore<LaborParams>() {
    override val fileName: String = "labor_params.json"
    override fun serializer(): KSerializer<LaborParams> = LaborParams.serializer()

    fun load(context: Context): LaborParams = read(context) ?: LaborParams.DEFAULT
    fun save(context: Context, p: LaborParams) = write(context, p)
    fun reset(context: Context) = clear(context)
}
