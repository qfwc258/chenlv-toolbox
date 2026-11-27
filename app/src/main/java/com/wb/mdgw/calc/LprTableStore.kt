package com.wb.mdgw.calc

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * LPR 报价表本地持久化。
 *
 * 用户可在 LPR 计算器「报价表」弹窗中查看/修改每期 1 年期、5 年期 LPR，
 * 保存后覆盖内置默认表参与分段计息；恢复默认即删除本地覆盖。
 */
object LprTableStore : JsonFileStore<List<LprEngine.Milestone>>() {
    override val fileName: String = "lpr_table.json"
    override fun serializer(): KSerializer<List<LprEngine.Milestone>> =
        ListSerializer(LprEngine.Milestone.serializer())

    fun load(context: Context): List<LprEngine.Milestone> = read(context) ?: LprEngine.LPR_HISTORY
    fun save(context: Context, list: List<LprEngine.Milestone>) = write(context, list)
    fun reset(context: Context) = clear(context)
}
