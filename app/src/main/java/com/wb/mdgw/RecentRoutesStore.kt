package com.wb.mdgw

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 主页「最近使用」功能模块入口记录。
 *
 * 只存 [Route] 的 name 字符串（不依赖 MainActivity 的私有 enum，避免循环引用），
 * 按使用时间倒序，最多保留 [MAX_RECENT] 个。打开任意功能页时置顶去重。
 *
 * 复用 [JsonFileStore]：私有文件、失败静默、原子覆盖，不阻塞主流程。
 */
@Serializable
data class RecentRoute(val routeName: String, val usedAt: Long) {
    companion object {
        const val MAX_RECENT = 5
    }
}

object RecentRoutesStore : JsonFileStore<List<RecentRoute>>() {
    override val fileName: String = "recent_routes.json"
    override fun serializer() = ListSerializer(RecentRoute.serializer())

    /** 最近使用的功能（route 名），倒序，最多 [RecentRoute.MAX_RECENT] 个 */
    fun list(context: Context): List<String> =
        read(context).orEmpty()
            .sortedByDescending { it.usedAt }
            .map { it.routeName }
            .take(RecentRoute.MAX_RECENT)

    /** 打开功能时置顶去重 */
    fun touch(context: Context, routeName: String) {
        val next = read(context).orEmpty()
            .filterNot { it.routeName == routeName }
            .toMutableList()
        next.add(RecentRoute(routeName, System.currentTimeMillis()))
        write(context, next.takeLast(RecentRoute.MAX_RECENT))
    }
}
