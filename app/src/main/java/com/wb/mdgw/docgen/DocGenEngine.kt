package com.wb.mdgw.docgen

import android.content.Context
import android.net.Uri
import com.wb.mdgw.DocxTemplateFiller
import com.wb.mdgw.FileUtils
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一次批量生成的汇总结果。
 */
data class GenOutput(
    /** 每个入选模板的处理结果（成功 / 失败） */
    val results: List<GenResult>,
    val successCount: Int,
    /** 打包后的 zip（含全部成功文件），供一键分享；无成功文件时为 null */
    val zipUri: Uri?,
    val zipName: String,
    /** 输出子目录名，如「案件_1」 */
    val caseDir: String
)

/**
 * 文书批量生成引擎（对应 PC 端 generate_data()）。
 *
 * 流程：扫描模板目录 → 按文书类型 code 粗筛文件名 → docx 走 [DocxTemplateFiller]
 * 占位符替换、pdf 原样复制 → 保存到「下载 / 陈律文档 / 案件_<编号> /」→ 打包 zip 供分享。
 *
 * 替换规则：先铺内置默认字段全集（用户未填 / 已删除的标准字段置空，由 filler 按占位符
 * 长度留白，保证模板里不残留占位符），再用用户当前填写 / 新增的字段值覆盖。
 */
object DocGenEngine {

    private const val DIR_PREFIX = "案件_"
    private const val MAX_NAME = 50
    private val ILLEGAL_CHARS = Regex("[<>:\"/\\\\|?*]")

    /** 清理文件名非法字符并限长（与 PC sanitize_filename 一致） */
    fun sanitizeFileName(name: String): String =
        ILLEGAL_CHARS.replace(name, "").trim().take(MAX_NAME)

    /**
     * 执行批量生成。
     *
     * @param caseNumber   案件编号（决定输出子目录 案件_<编号>，可含中文；空则回退为 1）
     * @param selectedTypes 选中的文书类型 code 集合；**空集合表示全部**（对应 PC 的 lx=0）
     * @param templates    从模板目录扫描得到的文件列表
     */
    fun generate(
        context: Context,
        caseNumber: String,
        selectedTypes: Set<String>,
        fieldDoc: FieldDoc,
        templates: List<DocTemplateFile>
    ): GenOutput {
        // 1) 默认字段全集补空；2) 用户值覆盖
        val rules = LinkedHashMap<String, String>()
        DefaultFields.keys(context).forEach { rules[it] = "" }
        fieldDoc.toMap().forEach { (k, v) -> rules[k] = v }

        val caseDir = DIR_PREFIX + sanitizeFileName(caseNumber.trim().ifBlank { "1" })

        val results = mutableListOf<GenResult>()
        data class Out(val name: String, val bytes: ByteArray, val mime: String)
        val outs = mutableListOf<Out>()

        for (template in templates) {
            if (!matchesType(template.fileName, selectedTypes)) continue
            try {
                val bytes = template.readBytes()
                    ?: throw IllegalStateException("模板文件读取失败，请检查目录权限")

                val outBytes = when {
                    template.isPdf -> bytes // PDF 原样复制
                    template.isDocx -> DocxTemplateFiller.fill(bytes, rules)
                    else -> continue
                }
                val mime = if (template.isPdf) FileUtils.PDF_MIME else FileUtils.DOCX_MIME
                val outName = sanitizeFileName(template.fileName)

                val saved = FileUtils.saveToDownloadsInDir(context, caseDir, outName, outBytes, mime)
                outs += Out(outName, outBytes, mime)
                results += GenResult(
                    templateName = template.fileName,
                    success = true,
                    outputName = outName,
                    displayPath = saved.displayPath
                )
            } catch (e: Exception) {
                results += GenResult(
                    templateName = template.fileName,
                    success = false,
                    error = e.message ?: "处理失败"
                )
            }
        }

        // 打包 zip（重名自动加序号）
        var zipUri: Uri? = null
        val zipName = "$caseDir.zip"
        if (outs.isNotEmpty()) {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zip ->
                val used = mutableSetOf<String>()
                for (o in outs) {
                    var name = o.name
                    var i = 1
                    while (name in used) {
                        val dot = o.name.lastIndexOf('.')
                        name = if (dot > 0) {
                            o.name.substring(0, dot) + "_" + i + o.name.substring(dot)
                        } else {
                            o.name + "_" + i
                        }
                        i++
                    }
                    used += name
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(o.bytes)
                    zip.closeEntry()
                }
            }
            zipUri = FileUtils.writeCache(context, zipName, bos.toByteArray())
        }

        return GenOutput(
            results = results,
            successCount = outs.size,
            zipUri = zipUri,
            zipName = zipName,
            caseDir = caseDir
        )
    }

    /**
     * 文件名类型筛选：空集合（全部）恒真；否则文件名包含任一选中 code 即入选
     * （沿用 PC 的 `if lx in fileName` 粗匹配）。
     */
    private fun matchesType(fileName: String, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        return selected.any { fileName.contains(it) }
    }
}
