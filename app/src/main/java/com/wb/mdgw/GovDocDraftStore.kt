package com.wb.mdgw

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 公文编辑草稿的本地持久化。
 *
 * 用户在「公文」页打开文件或就地编辑后，会把当前 [GovDoc] 模型序列化成本地 JSON 草稿，
 * 下一次进入公文页（无外部文件、且尚未生成公文）时若检测到草稿则提示恢复，
 * 避免误关或崩溃导致排版成果丢失。导出 Word / PDF 成功后即清除草稿（成品已落盘）。
 *
 * 设计要点：
 *  - 用 kotlinx.serialization 序列化 `GovDoc` 模型（data class / enum / sealed 均已标注
 *    @Serializable），零反射、体积小；
 *  - `originalDocx`（源 Word 字节，可能数 MB）用 @Transient 跳过：草稿恢复走「新建」导出路径，
 *    不携带源文件，既省空间又避免大体积 base64 写入；
 *  - 草稿只存「当前编辑成果」，不含撤销栈等运行时状态；重启后恢复为全新编辑会话。
 */
object GovDocDraftStore {

    private const val DRAFT_FILE = "govdoc_draft.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 保存草稿（覆盖式）。失败静默忽略，绝不影响正常编辑 */
    fun save(context: Context, doc: GovDoc) {
        runCatching {
            val txt = json.encodeToString(GovDoc.serializer(), doc)
            context.openFileOutput(DRAFT_FILE, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    /** 读取草稿；不存在或解析失败返回 null */
    fun load(context: Context): GovDoc? {
        val file = File(context.filesDir, DRAFT_FILE)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { json.decodeFromString<GovDoc>(file.readText()) }.getOrNull()
    }

    /** 是否存在草稿文件 */
    fun has(context: Context): Boolean {
        val f = File(context.filesDir, DRAFT_FILE)
        return f.exists() && f.length() > 0
    }

    /** 清除草稿（导出成功 / 丢弃恢复 / 文档页生成新公文时调用） */
    fun clear(context: Context) {
        runCatching { context.deleteFile(DRAFT_FILE) }
    }
}

/**
 * 记录「本次进程是否已对公文草稿做过恢复/丢弃决策」，
 * 避免公文页在底部 Tab 间切换被重建时反复弹「恢复草稿」对话框。
 */
object GovDocDraftSession {
    var handled: Boolean = false
}
