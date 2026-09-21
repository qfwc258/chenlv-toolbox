package com.wb.mdgw.docgen

import android.content.Context
import android.net.Uri
import com.wb.mdgw.FileUtils
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 已导入的文书模板存储：
 *  - 元数据（id / 文件名 / 扩展名）存私有 JSON [META_FILE]；
 *  - 模板字节存私有目录 [DIR_NAME]/<id>.<ext>，避免 JSON 膨胀。
 *
 * 仅接受 docx / pdf；docx 走占位符替换，pdf 按 PC 脚本直接原样复制。
 */
object DocTemplateStore {

    private const val DIR_NAME = "docgen_templates"
    private const val META_FILE = "docgen_templates.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val metaSerializer = ListSerializer(DocTemplate.serializer())

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 全部模板元数据（按导入顺序） */
    fun loadMeta(context: Context): List<DocTemplate> = runCatching {
        val f = File(context.filesDir, META_FILE)
        if (!f.exists() || f.length() == 0L) emptyList()
        else json.decodeFromString(metaSerializer, f.readText())
    }.getOrDefault(emptyList())

    private fun saveMeta(context: Context, list: List<DocTemplate>) {
        runCatching {
            val txt = json.encodeToString(metaSerializer, list)
            context.openFileOutput(META_FILE, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    /**
     * 导入一个模板（SAF Uri）。非 docx/pdf 或读取失败返回 null。
     */
    fun import(context: Context, uri: Uri): DocTemplate? = runCatching {
        val name = FileUtils.displayName(context, uri) ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "docx" && ext != "pdf") return null

        val bytes = FileUtils.readBytes(context, uri) ?: return null
        val id = System.currentTimeMillis().toString(16) + "-" + (0..999999).random().toString(16)
        File(dir(context), "$id.$ext").writeBytes(bytes)

        val template = DocTemplate(id = id, fileName = name, ext = ext)
        saveMeta(context, loadMeta(context) + template)
        template
    }.getOrNull()

    /** 读取模板字节 */
    fun readBytes(context: Context, template: DocTemplate): ByteArray? = runCatching {
        File(dir(context), "${template.id}.${template.ext}").readBytes()
    }.getOrNull()

    /** 删除一个模板（元数据 + 字节，失败静默） */
    fun delete(context: Context, id: String) {
        val list = loadMeta(context)
        val target = list.firstOrNull { it.id == id } ?: return
        runCatching { File(dir(context), "${target.id}.${target.ext}").delete() }
        saveMeta(context, list.filterNot { it.id == id })
    }
}
