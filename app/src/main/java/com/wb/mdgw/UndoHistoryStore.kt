package com.wb.mdgw

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 撤销/重做栈的本地持久化。
 *
 * 改造前：撤销栈只是 Composable 里的 `remember { ArrayDeque<...>() }`，
 * 进程被杀或用户主动退出后栈就丢了，下次进入无法继续撤销上次的工作。
 *
 * 改造后：每次栈变化都把整个栈（含 undo + redo）落盘到应用私有目录，
 * 下次进入时自动恢复。栈长度仍由各自 `*_MAX_HISTORY` 限定（默认 40-60 步），
 * 持久化的栈同样受这个上限约束，不会无限增长。
 *
 * 设计要点：
 *  - 复用 [JsonFileStore]：零反射、kotlinx.serialization、文件原子覆盖；
 *  - Markdown 快照 [MdUndoSnapshot] 只保存可序列化字段（text + 选区起止），
 *    不携带 `TextFieldValue.composition`（IME 组合态，重启后无意义）；
 *  - 公文快照 [GovUndoHistory] 用 `GovDoc.serializer()` 直接序列化；
 *    `originalDocx` 在 [GovDoc] 里标了 `@Transient`，重启后栈里的快照同样没有原始字节，
 *    与草稿 [GovDocDraftStore] 行为一致——重启后无法继续「原位修改」导出，只能走重建路径；
 *  - 读写全部失败静默（`runCatching`），绝不影响正常编辑；
 *  - 持久化时机：栈变化时通过 `LaunchedEffect(undoStack, redoStack)` 触发；
 *    写入走 `Dispatchers.IO`，不阻塞主线程；
 *  - 清理时机：打开新文件 / 成功导出 Word / PDF / 加载示例时调用 `clear()`，
 *    避免上一个文档的栈残留在新文档上造成误撤销。
 */
object UndoHistoryStore {

    /**
     * 进程内复用一份 Json 配置，与 [JsonFileStore] 保持一致：
     * ignoreUnknownKeys / encodeDefaults，方便后续字段增减而不破坏老数据。
     */
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ============================================================
    // Markdown 正文撤销栈
    // ============================================================

    /**
     * Markdown / 文本编辑态的单步快照。
     *
     * 只保留可持久化字段：`TextFieldValue` 里还有 `composition`（IME 组合态），
     * 进程退出后那个状态已无意义，重启时不保留。
     */
    @Serializable
    data class MdUndoSnapshot(
        val text: String,
        val selStart: Int,
        val selEnd: Int
    ) {
        companion object {
            /** 从 TextFieldValue 构造快照 */
            fun of(tfv: TextFieldValue): MdUndoSnapshot =
                MdUndoSnapshot(
                    text = tfv.text,
                    selStart = tfv.selection.start.coerceAtLeast(0),
                    selEnd = tfv.selection.end.coerceAtLeast(0)
                )

            /** 把快照还原成 TextFieldValue */
            fun toTfv(s: MdUndoSnapshot): TextFieldValue {
                val len = s.text.length
                val start = s.selStart.coerceIn(0, len)
                val end = s.selEnd.coerceIn(0, len)
                return TextFieldValue(
                    text = s.text,
                    selection = TextRange(start, end)
                )
            }
        }
    }

    /** 单个 Markdown 编辑器的撤销栈容器 */
    @Serializable
    data class MdUndoHistory(
        val undo: List<MdUndoSnapshot> = emptyList(),
        val redo: List<MdUndoSnapshot> = emptyList()
    )

    /** Markdown 撤销栈通用存储（不绑死文件，便于复用） */
    abstract class MdStoreBase : JsonFileStore<MdUndoHistory>() {
        override fun serializer(): KSerializer<MdUndoHistory> = MdUndoHistory.serializer()

        fun save(context: Context, undo: List<MdUndoSnapshot>, redo: List<MdUndoSnapshot>) {
            write(context, MdUndoHistory(undo = undo, redo = redo))
        }

        /** 读取并拆成 (undo, redo)；不存在/失败返回 null */
        fun load(context: Context): Pair<List<MdUndoSnapshot>, List<MdUndoSnapshot>>? {
            return read(context)?.let { it.undo to it.redo }
        }
    }

    /** WORD Tab：Markdown 正文撤销栈 */
    object MdWordUndoStore : MdStoreBase() {
        override val fileName: String = "md_word_undo.json"
    }

    /** 公众号 Tab：Markdown 正文撤销栈 */
    object MdWechatUndoStore : MdStoreBase() {
        override val fileName: String = "md_wechat_undo.json"
    }

    /** PPTX Tab：Markdown 正文撤销栈 */
    object MdPptxUndoStore : MdStoreBase() {
        override val fileName: String = "md_pptx_undo.json"
    }

    // ============================================================
    // 公文 GovDoc 撤销栈
    // ============================================================

    /**
     * 公文编辑器撤销栈容器。
     *
     * 与 [GovDocDraftStore] 互补：
     *  - 草稿 = 当前编辑成果（每次改完就存）；
     *  - 撤销栈 = 历史快照（每次 commit 就存）。
     * 重启后：草稿恢复当前态，撤销栈恢复历史，二者配合即可继续撤销/重做。
     */
    @Serializable
    data class GovUndoHistory(
        val undo: List<GovDoc?> = emptyList(),
        val redo: List<GovDoc?> = emptyList()
    )

    /** 公文编辑器撤销栈存储（word Tab 公文页用） */
    object GovUndoStore : JsonFileStore<GovUndoHistory>() {
        override val fileName: String = "gov_undo.json"
        override fun serializer(): KSerializer<GovUndoHistory> = GovUndoHistory.serializer()

        fun save(context: Context, undo: List<GovDoc?>, redo: List<GovDoc?>) {
            write(context, GovUndoHistory(undo = undo, redo = redo))
        }

        /** 读取并拆成 (undo, redo)；不存在/失败返回 null */
        fun load(context: Context): Pair<List<GovDoc?>, List<GovDoc?>>? {
            return read(context)?.let { it.undo to it.redo }
        }
    }

    // ============================================================
    // 通用入口（按 store 名字分桶，便于跨 Tab 一次性清空）
    // ============================================================

    /**
     * 一次性清空全部撤销栈。
     *
     * 应用设置里的「恢复出厂」按钮、清缓存操作会用到。
     */
    fun clearAll(context: Context) {
        MdWordUndoStore.clear(context)
        MdWechatUndoStore.clear(context)
        MdPptxUndoStore.clear(context)
        GovUndoStore.clear(context)
    }
}
