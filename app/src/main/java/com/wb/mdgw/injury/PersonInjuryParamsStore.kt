package com.wb.mdgw.injury

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer

/** 人身损害赔偿「地区口径」本地持久化；无覆盖则返回默认参考值。 */
object PersonInjuryParamsStore : JsonFileStore<PersonInjuryParams>() {
    override val fileName: String = "person_injury_params.json"
    override fun serializer(): KSerializer<PersonInjuryParams> = PersonInjuryParams.serializer()

    fun load(context: Context): PersonInjuryParams = read(context) ?: PersonInjuryParams.DEFAULT
    fun save(context: Context, p: PersonInjuryParams) = write(context, p)
    fun reset(context: Context) = clear(context)
}
