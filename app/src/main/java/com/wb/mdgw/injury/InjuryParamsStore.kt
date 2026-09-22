package com.wb.mdgw.injury

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer

/**
 * 工伤赔偿「核心参数」本地持久化。
 *
 * 用户可在 UI「赔偿标准参数」中修改统筹工资、住院伙食补助、一次性工亡补助金等核心数据
 * 并保存；读取时若无本地覆盖则返回 [InjuryParams.DEFAULT]（湖南 2025 标准）。
 */
object InjuryParamsStore : JsonFileStore<InjuryParams>() {

    override val fileName: String = "injury_params.json"

    override fun serializer(): KSerializer<InjuryParams> = InjuryParams.serializer()

    /** 读取；无本地覆盖则返回湖南 2025 默认标准 */
    fun load(context: Context): InjuryParams = read(context) ?: InjuryParams.DEFAULT

    /** 覆盖保存当前参数 */
    fun save(context: Context, p: InjuryParams) = write(context, p)

    /** 恢复默认：删除本地覆盖 */
    fun reset(context: Context) = clear(context)
}
