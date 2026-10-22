package com.wb.mdgw.docgen

import android.content.Context
import com.wb.mdgw.JsonFileStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当前案件的替换规则（字段表单）持久化。
 *
 * 只维护「一份当前规则」，结构（分组/注释/字段顺序）完整保留；
 * 手机端编辑后落盘，重启不丢。需要多案件时可由「导入/导出 shared_text.txt」承载。
 *
 * 「当前案件类型」只决定这份规则**从哪套默认结构长出来**（重置 / 首次生成 / 切类型并入），
 * 并不按类型分文件——切类型走 [DefaultFields.mergeStructure] 保留已填内容。
 */
object FieldRuleStore : JsonFileStore<FieldDoc>() {

    override val fileName: String = "docgen_fields.json"

    override fun serializer() = FieldDoc.serializer()

    /** 读取当前规则；不存在时返回空文档 */
    fun load(context: Context): FieldDoc = read(context) ?: FieldDoc()

    /**
     * 读取当前规则；若用户从未导入 / 填写（为空），则加载当前案件类型的内置默认结构
     * （assets/docgen/defaults/<id>.txt），并套用该类型下用户自定义的字段默认值，
     * 落盘一次，使手机端开箱即有标准字段表单。
     */
    fun loadOrDefault(context: Context, caseTypeId: String): FieldDoc {
        val existing = read(context)
        if (existing != null && existing.lines.isNotEmpty()) return existing
        val doc = buildDefaultDoc(context, caseTypeId)
        if (doc.lines.isNotEmpty()) write(context, doc)
        return doc
    }

    /** 覆盖保存当前规则（失败静默，由基类保证不影响编辑） */
    fun save(context: Context, doc: FieldDoc) = write(context, doc)

    /**
     * 构建某类型的默认结构：解析该类型的资源文件后，套用该类型的用户自定义默认值覆盖表。
     */
    fun buildDefaultDoc(context: Context, caseTypeId: String): FieldDoc {
        val raw = DefaultFields.lines(context, caseTypeId)
        val overrides = DefaultValueStore.load(context, caseTypeId)
        val lines = if (overrides.isEmpty()) raw
        else DefaultFields.applyUserDefaults(context, raw, overrides, caseTypeId)
        return FieldDoc(lines)
    }

    /**
     * 重置为当前类型的内置默认结构（套用该类型的用户自定义默认值，
     * 丢弃用户增删的自定义字段与已填值），落盘并返回，供「重置为默认结构」按钮使用。
     */
    fun resetOrDefault(context: Context, caseTypeId: String): FieldDoc {
        val doc = buildDefaultDoc(context, caseTypeId)
        if (doc.lines.isNotEmpty()) write(context, doc)
        return doc
    }

    /**
     * 破坏性操作（重置为默认结构 / 清空所有值）**前**把当前文件原样另存一份
     * `docgen_fields.bak_<时间戳>.json`，最多保留 [MAX_BACKUPS] 份（超出删最旧）。
     * 失败静默、返回是否成功——备份只是保险，绝不影响主流程。
     */
    fun backupCurrent(context: Context): Boolean = runCatching {
        val dir = context.filesDir
        val src = File(dir, fileName)
        if (!src.exists() || src.length() == 0L) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "$BACKUP_PREFIX$stamp.json").writeBytes(src.readBytes())
        dir.listFiles { f -> f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_BACKUPS)
            ?.forEach { old -> runCatching { old.delete() } }
        true
    }.getOrDefault(false)

    /** 是否存在可用备份 */
    fun hasBackup(context: Context): Boolean = latestBackup(context) != null

    /**
     * 用最近一份备份覆盖当前规则并返回；没有备份时返回 null。
     * 备份文件保留不删（`docgen_fields.bak_*` 由 [backupCurrent] 按数量滚动清理）。
     */
    fun restoreLatestBackup(context: Context): FieldDoc? {
        val file = latestBackup(context) ?: return null
        val ok = runCatching {
            File(context.filesDir, fileName).writeBytes(file.readBytes())
        }.isSuccess
        return if (ok) load(context) else null
    }

    private fun latestBackup(context: Context): File? = runCatching {
        context.filesDir.listFiles { f -> f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.firstOrNull { it.length() > 0L }
    }.getOrNull()

    private const val BACKUP_PREFIX = "docgen_fields.bak_"
    private const val MAX_BACKUPS = 5
}
