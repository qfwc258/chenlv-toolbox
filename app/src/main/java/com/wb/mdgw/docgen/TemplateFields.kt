package com.wb.mdgw.docgen

import java.util.concurrent.ConcurrentHashMap

/**
 * 文书类型 ↔ 主要字段联动：从 pylaw/mb 下「数字前缀」模板里扫描预留字段，
 * 生成该类型的字段表单结构。
 *
 * 设计要点（对应需求确认项）：
 *  1a 模板为准、内置兜底：扫描到占位符时用扫描结果生成结构；扫不到（无权限 / 无模板 /
 *     模板无占位符）时返回空，由调用方回退到内置 [DefaultFields] 结构。
 *  2a 严格前缀：文件名以类型码开头，且码后不是数字——码 `1` 不会误吃 `10-xx.docx`。
 *  5  裸拼音占位符：直接复用 [DocxPlaceholders] 的扫描结果（standard + 疑似 unknown）。
 *
 * 字段的「分组 / 中文标签 / 默认值」尽量沿用内置结构：内置里出现的 key 按内置分组与顺序
 * 排列，值由 [valueOf] 注入（有效默认值）；内置里没有的 key 归到 [TEMPLATE_GROUP]。
 * 这样模板增删字段时表单自动跟随，同时保留用户熟悉的分组与默认值。
 */
object TemplateFields {

    /** 模板里出现、但内置结构没有的字段统一归入此分组 */
    const val TEMPLATE_GROUP = "模板字段"

    /**
     * 严格数字前缀匹配：文件名（去掉扩展名后）以 [code] 开头，且紧跟码的字符不是数字。
     * 例：code=1 时 `1-委托书.docx`/`1委托书.docx` 命中，`10-委托书.docx`/`x1.docx` 不命中。
     */
    fun matchesStrictPrefix(fileName: String, code: String): Boolean {
        if (code.isEmpty()) return false
        val stem = fileName.substringBeforeLast('.', fileName)
        if (!stem.startsWith(code)) return false
        if (stem.length == code.length) return true
        return !stem[code.length].isDigit()
    }

    /** 按严格前缀筛出某类型的模板（仅 docx 含占位符，pdf 原样复制不参与字段扫描） */
    fun pickTemplates(templates: List<DocTemplateFile>, code: String): List<DocTemplateFile> =
        templates.filter { it.isDocx && matchesStrictPrefix(it.fileName, code) }

    /** 模板集合签名：目录 + 类型码 + 文件名/大小/修改时间，供缓存失效 */
    fun signature(dir: String, code: String, templates: List<DocTemplateFile>): String =
        buildString {
            append(dir).append('|').append(code)
            templates.sortedBy { it.fileName }.forEach {
                append('|').append(it.fileName).append(':').append(it.file.length())
                    .append(':').append(it.file.lastModified())
            }
        }

    // 扫描结果缓存（IO 密集部分）；key = signature。空结果不缓存，授权后可重新扫描。
    private val keyCache = ConcurrentHashMap<String, List<String>>()

    fun clearCache() = keyCache.clear()

    /** 扫描某类型模板里的全部占位符 key（standard 优先、unknown 追加，去重保序） */
    fun scanKeys(
        templates: List<DocTemplateFile>,
        code: String,
        standardKeys: List<String>
    ): List<String> {
        val docx = pickTemplates(templates, code).mapNotNull { it.readBytes() }
        if (docx.isEmpty()) return emptyList()
        val scan = DocxPlaceholders.scan(docx, standardKeys)
        return (scan.standard + scan.unknown).distinct()
    }

    /** 带缓存的扫描；缓存命中直接返回，避免每次进页面重复解压 docx */
    fun scanKeysCached(
        cacheKey: String,
        templates: List<DocTemplateFile>,
        code: String,
        standardKeys: List<String>
    ): List<String> {
        keyCache[cacheKey]?.let { return it }
        val result = scanKeys(templates, code, standardKeys)
        if (result.isNotEmpty()) keyCache[cacheKey] = result
        return result
    }

    /**
     * 由扫描到的 key 列表 + 内置结构，生成该类型的字段结构（纯函数，可单测）。
     *
     * - 内置结构按分组切块：某块只要有字段命中扫描 key，就输出分组标题 + 块内注释 + 命中的字段；
     *   块内字段保持内置顺序，值由 [valueOf] 注入有效默认值。
     * - 扫描到但内置没有的 key：统一追加到 [TEMPLATE_GROUP] 分组。
     * - [scannedKeys] 为空时返回空列表（调用方据此回退内置结构）。
     */
    fun buildStructure(
        scannedKeys: List<String>,
        builtinLines: List<RuleLine>,
        valueOf: (String) -> String
    ): List<RuleLine> {
        if (scannedKeys.isEmpty()) return emptyList()
        val wanted = scannedKeys.toHashSet()

        val result = mutableListOf<RuleLine>()
        var title: String? = null
        val comments = mutableListOf<RuleLine>()
        val fields = mutableListOf<RuleLine>()

        fun reset() {
            title = null
            comments.clear()
            fields.clear()
        }

        fun flushBlock() {
            val kept = fields.filter { it.key in wanted }
            if (kept.isEmpty()) {
                reset()
                return
            }
            title?.let { result.add(RuleLine.group(it)) }
            result.addAll(comments)
            kept.forEach { result.add(it.copy(value = valueOf(it.key))) }
            reset()
        }

        builtinLines.forEach { line ->
            when {
                line.isGroup -> {
                    flushBlock()
                    title = line.text
                }
                line.isComment -> comments.add(line)
                line.isField -> fields.add(line)
                // 空行由分组逻辑重建，忽略
            }
        }
        flushBlock()

        // 内置结构没有的 key（模板预留的新字段）——按扫描顺序归入模板字段分组
        val builtinKeys = builtinLines.filter { it.isField }.map { it.key }.toSet()
        val extras = scannedKeys.filter { it !in builtinKeys }
        if (extras.isNotEmpty()) {
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) {
                result.add(RuleLine.blank())
            }
            result.add(RuleLine.group(TEMPLATE_GROUP))
            extras.forEach { result.add(RuleLine.field(it, valueOf(it))) }
        }
        return result
    }
}
