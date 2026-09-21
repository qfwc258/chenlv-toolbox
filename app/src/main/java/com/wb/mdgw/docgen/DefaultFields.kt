package com.wb.mdgw.docgen

import android.content.Context

/**
 * 内置默认字段结构（assets/docgen/default_shared_text.txt）的加载与操作。
 *
 * 承担三件事：
 *  1. 加载并缓存默认结构（分组 / 注释 / 字段），供「添加字段」选择器列出全部默认字段；
 *  2. 给出默认字段 key 全集，生成时把用户未填 / 已删除的标准字段补空，
 *     保证模板里的标准占位符不会残留；
 *  3. 新增字段时按默认结构定位到原分组（分组被删则连同恢复），自定义字段归入「自定义字段」。
 */
object DefaultFields {

    /** 自定义字段统一归入的分组名 */
    const val CUSTOM_GROUP = "自定义字段"

    @Volatile
    private var cache: List<RuleLine>? = null

    /** 默认结构（优先用缓存），assets 缺失时返回空列表 */
    fun lines(context: Context): List<RuleLine> {
        cache?.let { return it }
        val text = runCatching {
            context.assets.open("docgen/default_shared_text.txt")
                .bufferedReader(Charsets.UTF_8)
                .use { r -> r.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = SharedTextParser.parse(text)
        cache = parsed
        return parsed
    }

    /** 默认字段 key（按默认顺序，含 gcsj1 等序号字段与 sq1/sq2/lsf） */
    fun keys(context: Context): List<String> =
        lines(context).filter { it.isField }.map { it.key }

    /**
     * 把一个字段插入当前结构，返回新列表（不修改入参）。
     *
     * - key 已存在：原样返回（不重复插入）；
     * - 默认字段：插回其原分组、按默认顺序排列；分组标题缺失则连同恢复；
     * - 非默认（自定义）字段：归入末尾「自定义字段」分组。
     */
    fun insertField(
        current: List<RuleLine>,
        key: String,
        value: String,
        defaults: List<RuleLine>,
        alias: String = ""
    ): List<RuleLine> {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty() || current.any { it.isField && it.key == trimmedKey }) return current

        val result = current.toMutableList()
        val defIdx = defaults.indexOfFirst { it.isField && it.key == trimmedKey }
        val isDefault = defIdx >= 0

        val groupTitle: String? = if (isDefault) {
            var g: String? = null
            for (i in defIdx downTo 0) {
                if (defaults[i].isGroup) { g = defaults[i].text; break }
            }
            g
        } else {
            CUSTOM_GROUP
        }

        val gIdx = if (groupTitle == null) -1
        else result.indexOfFirst { it.isGroup && it.text == groupTitle }

        if (gIdx < 0) {
            // 分组不存在：在末尾新建分组（前置空行分隔）
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) {
                result.add(RuleLine.blank())
            }
            if (groupTitle != null) result.add(RuleLine.group(groupTitle))
            result.add(RuleLine.field(trimmedKey, value, if (isDefault) "" else alias.trim()))
            return result
        }

        // 分组区块结束位置（遇到下一个分组标题为止）
        var end = result.size
        for (i in gIdx + 1 until result.size) {
            if (result[i].isGroup) { end = i; break }
        }

        // 默认字段按默认顺序插入到第一个「顺序在其后」的字段前；自定义字段插区块末尾
        var insertAt = end
        if (isDefault) {
            for (i in gIdx + 1 until end) {
                val line = result[i]
                if (line.isField) {
                    val otherOrder = defaults.indexOfFirst { it.isField && it.key == line.key }
                    if (otherOrder >= 0 && otherOrder > defIdx) {
                        insertAt = i
                        break
                    }
                }
            }
        }
        result.add(insertAt, RuleLine.field(trimmedKey, value, if (isDefault) "" else alias.trim()))
        return result
    }

    /** 办案过程分组名（gcsj/gcfs/gcnr 三元组所在分组） */
    const val PROCESS_GROUP = "办案过程"

    /**
     * 内置默认值表（默认值有效 key → 内置值）。
     * 可重复字段（gcsj/gcfs/gcnr）取第一个序号字段的值作为该类字段的内置默认。
     */
    fun builtinDefaults(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (l in lines(context)) {
            if (!l.isField) continue
            val dk = FieldLabels.defaultKey(l.key)
            if (dk !in map) map[dk] = l.value
        }
        return map
    }

    /**
     * 某字段的有效默认值：用户自定义覆盖 → 内置默认 → 空。
     * @param overrides 用户自定义默认值覆盖表（默认值有效 key → 值）
     */
    fun effectiveDefault(context: Context, key: String, overrides: Map<String, String>): String {
        val dk = FieldLabels.defaultKey(key)
        overrides[dk]?.let { return it }
        return builtinDefaults(context)[dk] ?: ""
    }

    /** 对一份结构的所有字段套用有效默认值（重置 / 新建时使用），非字段行原样保留 */
    fun applyUserDefaults(
        context: Context,
        lines: List<RuleLine>,
        overrides: Map<String, String>
    ): List<RuleLine> = lines.map { l ->
        if (l.isField) l.copy(value = effectiveDefault(context, l.key, overrides)) else l
    }

    /** 默认值管理界面的单条字段（已按默认值有效 key 去重、可重复字段归并） */
    data class DefaultFieldEntry(
        val group: String?,
        val key: String,
        val label: String,
        val long: Boolean
    )

    /**
     * 默认值管理界面字段清单：先按内置结构顺序列出全部字段（过程三元组归并为三项），
     * 再补充当前表单里出现过的自定义字段，保证任意字段都能设默认值。
     */
    fun defaultEntries(context: Context, currentLines: List<RuleLine>): List<DefaultFieldEntry> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<DefaultFieldEntry>()

        var group: String? = null
        for (l in lines(context)) {
            when {
                l.isGroup -> group = l.text
                l.isField -> {
                    val dk = FieldLabels.defaultKey(l.key)
                    if (seen.add(dk)) {
                        result.add(
                            DefaultFieldEntry(
                                group = group,
                                key = dk,
                                label = FieldLabels.displayName(dk),
                                long = FieldLabels.isLong(dk)
                            )
                        )
                    }
                }
            }
        }

        var customGroup: String? = CUSTOM_GROUP
        for (l in currentLines) {
            when {
                l.isGroup -> customGroup = l.text
                l.isField -> {
                    val dk = FieldLabels.defaultKey(l.key)
                    if (seen.add(dk)) {
                        result.add(
                            DefaultFieldEntry(
                                group = customGroup,
                                key = dk,
                                label = FieldLabels.displayName(l.key, l.alias),
                                long = FieldLabels.isLong(l.key)
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /**
     * 插入一整组办案过程三元组（gcsjN/gcfsN/gcnrN），已存在的字段跳过。
     *
     * @param number 序号 N
     * @param values 具体 key（如 gcsj3）→ 初始值，缺失视为空
     */
    fun insertProcessGroup(
        current: List<RuleLine>,
        number: Int,
        values: Map<String, String>
    ): List<RuleLine> {
        val keys = listOf("gcsj", "gcfs", "gcnr").map { "$it$number" }
        if (keys.all { k -> current.any { it.isField && it.key == k } }) return current

        val result = current.toMutableList()
        val gIdx = result.indexOfFirst { it.isGroup && it.text == PROCESS_GROUP }
        if (gIdx < 0) {
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) {
                result.add(RuleLine.blank())
            }
            result.add(RuleLine.group(PROCESS_GROUP))
            keys.forEach { k -> result.add(RuleLine.field(k, values[k] ?: "")) }
            return result
        }

        var end = result.size
        for (i in gIdx + 1 until result.size) {
            if (result[i].isGroup) { end = i; break }
        }
        var insertAt = end
        for (k in keys) {
            if (result.none { it.isField && it.key == k }) {
                result.add(insertAt, RuleLine.field(k, values[k] ?: ""))
                insertAt++
            }
        }
        return result
    }
}
