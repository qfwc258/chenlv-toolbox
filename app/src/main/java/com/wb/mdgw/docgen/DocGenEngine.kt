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
    val caseDir: String,
    /** 归档到案件文件夹的 shared_text.txt 展示路径（失败为 null） */
    val sharedTextPath: String? = null,
    /**
     * 模板里出现、但表单没有对应字段的 key：本次按占位符长度留白（所以模板上看不到英文 key），
     * UI 据此提示「这些字段没地方填」，可一键补成表单字段。
     */
    val blankedTemplateKeys: List<String> = emptyList(),
    /**
     * 本次**入选模板**里实际出现的占位符 key 全集（标准 + 疑似）。
     * UI 用它对照表单，算出「你填了内容、但所选模板没有对应占位符」的字段——
     * 那些内容不会出现在生成的文书上。
     */
    val templateKeys: Set<String> = emptySet()
)

/**
 * 文书批量生成引擎（对应 PC 端 generate_data()）。
 *
 * 流程：扫描模板目录 → 按文书类型 code 粗筛文件名 → docx 走 [DocxTemplateFiller]
 * 占位符替换、pdf 原样复制 → 保存到「下载 / 陈律文档 / 案件_<编号> /」→ 打包 zip 供分享。
 *
 * 替换规则：先铺全部案件类型的默认字段并集（用户未填 / 已删除的标准字段置空，由 filler 按占位符
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
     * 案件归档子目录名：案件_<委托人 weitr>_<阶段 jied>，如「案件_周_一审」。
     * 任一段为空 / 全是非法字符时用「未填」占位，避免出现连续下划线。
     */
    fun caseDirName(fieldDoc: FieldDoc): String {
        val m = fieldDoc.toMap()
        val weitr = sanitizeFileName(m["weitr"].orEmpty().trim()).ifBlank { "未填" }
        val jied = sanitizeFileName(m["jied"].orEmpty().trim()).ifBlank { "未填" }
        return "$DIR_PREFIX${weitr}_$jied"
    }

    /**
     * 执行批量生成。
     *
     * @param selectedTypes 选中的文书类型 code 集合；**空集合表示全部**（对应 PC 的 lx=0）
     * @param templates    从模板目录扫描得到的文件列表
     * 案件归档目录由字段 weitr（委托人）、jied（阶段）自动拼成，见 [caseDirName]。
     */
    fun generate(
        context: Context,
        selectedTypes: Set<String>,
        fieldDoc: FieldDoc,
        templates: List<DocTemplateFile>
    ): GenOutput {
        // 1) 全部案件类型的默认字段并集补空；2) 用户值覆盖。
        //    用并集而非当前类型的 key：模板可能是另一种类型的文书，漏补会残留英文占位符。
        val standardKeys = DefaultFields.allKeys(context, CaseTypeStore.load(context).map { it.id })
        val rules = LinkedHashMap<String, String>()
        standardKeys.forEach { rules[it] = "" }
        fieldDoc.toMap().forEach { (k, v) -> rules[k] = v }

        // 3) 扫描**本次入选**的模板里实际出现、但未定义的占位符（如 dlrzj），按长度补空，
        //    保证模板里不残留任何英文 key（对应「未添加的自动替换为空」）；
        //    同时把「模板有 / 表单没有」与「表单有 / 模板没有」两份对账结果带回 UI。
        var blankedKeys = emptyList<String>()
        var matchedKeys = emptySet<String>()
        runCatching {
            val picked = templates.filter { it.isDocx && matchesType(it.fileName, selectedTypes) }
            val docxBytes = picked.mapNotNull { it.readBytes() }
            if (docxBytes.isNotEmpty()) {
                val scan = DocxPlaceholders.scan(docxBytes, standardKeys)
                val matched = LinkedHashSet(scan.standard + scan.unknown)
                matchedKeys = matched
                val blanked = mutableListOf<String>()
                matched.forEach { k -> if (k !in rules) { rules[k] = ""; blanked += k } }
                blankedKeys = blanked
            }
        }

        val caseDir = caseDirName(fieldDoc)

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

        // 归档当前替换规则到案件文件夹（与生成文书同目录，便于留存）
        val sharedTextPath = runCatching {
            val txt = SharedTextParser.export(fieldDoc).toByteArray(Charsets.UTF_8)
            FileUtils.saveToDownloadsInDir(context, caseDir, "shared_text.txt", txt, "text/plain").displayPath
        }.getOrNull()

        return GenOutput(
            results = results,
            successCount = outs.size,
            zipUri = zipUri,
            zipName = zipName,
            caseDir = caseDir,
            sharedTextPath = sharedTextPath,
            blankedTemplateKeys = blankedKeys,
            templateKeys = matchedKeys
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
