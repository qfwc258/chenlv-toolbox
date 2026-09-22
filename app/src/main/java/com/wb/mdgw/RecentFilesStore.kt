package com.wb.mdgw

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 一条「最近打开」记录。
 *
 * @param uri      已申请持久化读权限的 SAF content uri（字符串）
 * @param name     显示文件名
 * @param kind     所属模块：[KIND_TABLEFORM] / [KIND_WORD] / [KIND_PDF]，各模块互不串列
 * @param openedAt 最近一次打开时间（System.currentTimeMillis）
 * @param subtitle 模块自填的副信息（如「3 个表格」「12 页」），可为空
 */
@Serializable
data class RecentFile(
    val uri: String,
    val name: String,
    val kind: String,
    val openedAt: Long,
    val subtitle: String = ""
) {
    companion object {
        const val KIND_TABLEFORM = "tableform"
        const val KIND_WORD = "word"
        const val KIND_PDF = "pdf"
        /** 每个模块最多保留的最近记录数 */
        const val MAX_PER_KIND = 10
    }
}

/**
 * 跨模块通用的「最近打开」存储：单文件 [RecentFile] 列表，按 [RecentFile.kind] 分组。
 *
 * - 同一模块、同一 uri 再次打开 → 置顶去重并刷新时间 / 副信息；
 * - 每个模块最多保留 [RecentFile.MAX_PER_KIND] 条；
 * - 读写全部失败静默，不影响正常打开流程。
 */
object RecentFilesStore : JsonFileStore<List<RecentFile>>() {
    override val fileName: String = "recent_files.json"
    override fun serializer() = ListSerializer(RecentFile.serializer())

    /** 某模块的最近文件，按时间倒序 */
    fun list(context: Context, kind: String): List<RecentFile> =
        read(context).orEmpty().filter { it.kind == kind }.sortedByDescending { it.openedAt }

    /** 新增 / 置顶一条记录（同 kind+uri 去重），并裁掉超出上限的旧记录 */
    fun touch(
        context: Context,
        kind: String,
        uri: android.net.Uri,
        name: String,
        subtitle: String = ""
    ) {
        val uriStr = uri.toString()
        val all = read(context).orEmpty()
            .filterNot { it.kind == kind && it.uri == uriStr }
            .toMutableList()
        all.add(RecentFile(uriStr, name, kind, System.currentTimeMillis(), subtitle))
        val otherKinds = all.filter { it.kind != kind }
        val thisKindKept = all.filter { it.kind == kind }
            .sortedByDescending { it.openedAt }
            .take(RecentFile.MAX_PER_KIND)
        write(context, otherKinds + thisKindKept)
    }

    /** 移除某模块中的一条记录（文件失效 / 用户手动移除） */
    fun remove(context: Context, kind: String, uri: String) {
        val next = read(context).orEmpty().filterNot { it.kind == kind && it.uri == uri }
        write(context, next)
    }

    /** 清空某模块的最近记录 */
    fun clear(context: Context, kind: String) {
        write(context, read(context).orEmpty().filter { it.kind != kind })
    }
}
