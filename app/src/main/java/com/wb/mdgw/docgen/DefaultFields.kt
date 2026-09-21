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
        // 定位过程分组：优先「含 gcsj/gcfs/gcnr 字段的分组」（重命名后仍可识别），
        // 其次固定名「办案过程」，都没有则在文末新建。
        val byContent = processGroupTitle(result)
        val gIdx = when {
            byContent != null -> result.indexOfFirst { it.isGroup && it.text == byContent }
            else -> result.indexOfFirst { it.isGroup && it.text == PROCESS_GROUP }
        }
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

    // ==================== 分组管理 ====================

    /** 含办案过程三元组（gcsj/gcfs/gcnr）字段的分组标题；没有返回 null */
    fun processGroupTitle(lines: List<RuleLine>): String? {
        var g: String? = null
        for (l in lines) {
            when {
                l.isGroup -> g = l.text
                l.isField && FieldLabels.baseKey(l.key) in FieldLabels.REPEATABLE_BASE -> return g
            }
        }
        return null
    }

    /** 列出当前结构中的全部分组标题（去重、保序） */
    fun listGroups(lines: List<RuleLine>): List<String> =
        lines.filter { it.isGroup }.map { it.text }.distinct()

    /** 字段（按全局行索引）所属分组标题；位于第一个分组之前返回 null */
    fun groupOfField(lines: List<RuleLine>, fieldIndex: Int): String? {
        var g: String? = null
        for (i in 0..fieldIndex) {
            if (lines[i].isGroup) g = lines[i].text
        }
        return g
    }

    /** 重命名分组；新名为空或与其他分组重名时原样返回 */
    fun renameGroup(lines: List<RuleLine>, oldTitle: String, newTitle: String): List<RuleLine> {
        val nt = newTitle.trim()
        if (nt.isEmpty() || nt == oldTitle) return lines
        if (lines.any { it.isGroup && it.text == nt }) return lines
        return lines.map { if (it.isGroup && it.text == oldTitle) it.copy(text = nt) else it }
    }

    /**
     * 新增分组：插到 [afterTitle] 分组区块之后；省略或参照不存在时追加到文末。
     * 已存在同名分组时原样返回。
     */
    fun addGroup(lines: List<RuleLine>, title: String, afterTitle: String? = null): List<RuleLine> {
        val t = title.trim()
        if (t.isEmpty() || lines.any { it.isGroup && it.text == t }) return lines
        val result = lines.toMutableList()

        if (afterTitle == null) {
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) result.add(RuleLine.blank())
            result.add(RuleLine.group(t))
            return collapseBlankLines(result)
        }
        val gIdx = result.indexOfFirst { it.isGroup && it.text == afterTitle }
        if (gIdx < 0) {
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) result.add(RuleLine.blank())
            result.add(RuleLine.group(t))
            return collapseBlankLines(result)
        }
        var end = result.size
        for (i in gIdx + 1 until result.size) {
            if (result[i].isGroup) { end = i; break }
        }
        // 区块末尾的空行不纳入插入点，保持「空行 + 新标题」分隔
        var insertAt = end
        while (insertAt > gIdx + 1 && result[insertAt - 1].type == RuleLine.TYPE_BLANK) insertAt--
        result.add(insertAt, RuleLine.group(t))
        result.add(insertAt, RuleLine.blank())
        return collapseBlankLines(result)
    }

    /**
     * 把字段（全局行索引）移动到目标分组区块末尾；目标分组不存在则在文末新建。
     * 仅移动字段行；注释行不处理。移动后全局索引会变化，调用方应退出编辑态。
     */
    fun moveFieldToGroup(lines: List<RuleLine>, fieldIndex: Int, targetGroup: String): List<RuleLine> {
        val t = targetGroup.trim()
        if (t.isEmpty()) return lines
        val field = lines.getOrNull(fieldIndex) ?: return lines
        if (!field.isField) return lines
        if (groupOfField(lines, fieldIndex) == t) return lines

        val result = lines.toMutableList()
        result.removeAt(fieldIndex)

        val gIdx = result.indexOfFirst { it.isGroup && it.text == t }
        if (gIdx < 0) {
            if (result.isNotEmpty() && result.last().type != RuleLine.TYPE_BLANK) result.add(RuleLine.blank())
            result.add(RuleLine.group(t))
            result.add(field)
            return collapseBlankLines(result)
        }
        var end = result.size
        for (i in gIdx + 1 until result.size) {
            if (result[i].isGroup) { end = i; break }
        }
        var insertAt = end
        while (insertAt > gIdx + 1 && result[insertAt - 1].type == RuleLine.TYPE_BLANK) insertAt--
        result.add(insertAt, field)
        return collapseBlankLines(result)
    }

    /** 判断字段是否为办案过程三元组（gcsj/gcfs/gcnr 系列），这类字段不参与单字段排序 */
    private fun isProcessField(l: RuleLine): Boolean =
        l.isField && FieldLabels.baseKey(l.key) in FieldLabels.REPEATABLE_BASE

    /**
     * 计算字段在同一分组内上移/下移的交换目标索引；返回 -1 表示不可移动。
     *
     * 规则：不跨分组标题；注释行（#）与空行跳过、锚定原位；
     * 相邻为办案过程三元组时停止（不跨越、不交换）。
     */
    private fun moveFieldTarget(lines: List<RuleLine>, fieldIndex: Int, up: Boolean): Int {
        val field = lines.getOrNull(fieldIndex) ?: return -1
        if (!field.isField || isProcessField(field)) return -1

        // 分组边界
        var bound = if (up) 0 else lines.size
        if (up) {
            for (i in fieldIndex - 1 downTo 0) {
                if (lines[i].isGroup) { bound = i + 1; break }
            }
            for (i in fieldIndex - 1 downTo bound) {
                val l = lines[i]
                if (l.isField) return if (isProcessField(l)) -1 else i
            }
        } else {
            for (i in fieldIndex + 1 until lines.size) {
                if (lines[i].isGroup) { bound = i; break }
            }
            for (i in fieldIndex + 1 until bound) {
                val l = lines[i]
                if (l.isField) return if (isProcessField(l)) -1 else i
            }
        }
        return -1
    }

    /** 字段是否可在同一分组内上移/下移（供 UI 决定箭头是否禁用） */
    fun canMoveField(lines: List<RuleLine>, fieldIndex: Int, up: Boolean): Boolean =
        moveFieldTarget(lines, fieldIndex, up) >= 0

    /**
     * 在同一分组内上移/下移普通字段（与相邻普通字段交换）。
     * 无法移动时原样返回；移动后全局索引变化，调用方应退出编辑态。
     */
    fun moveField(lines: List<RuleLine>, fieldIndex: Int, up: Boolean): List<RuleLine> {
        val target = moveFieldTarget(lines, fieldIndex, up)
        if (target < 0) return lines
        val result = lines.toMutableList()
        val tmp = result[fieldIndex]
        result[fieldIndex] = result[target]
        result[target] = tmp
        return result
    }

    /**
     * 删除分组。
     * @param deleteFields true=连组内字段/注释一并删除；false=仅删标题，内容并入上一分组。
     */
    fun deleteGroup(lines: List<RuleLine>, title: String, deleteFields: Boolean): List<RuleLine> {
        val gIdx = lines.indexOfFirst { it.isGroup && it.text == title }
        if (gIdx < 0) return lines
        var end = lines.size
        for (i in gIdx + 1 until lines.size) {
            if (lines[i].isGroup) { end = i; break }
        }
        val result = lines.toMutableList()
        if (deleteFields) {
            result.subList(gIdx, end).clear()
        } else {
            result.removeAt(gIdx)
        }
        return collapseBlankLines(result)
    }

    /** 折叠连续空行（最多保留一个），并去掉首尾空行 */
    private fun collapseBlankLines(lines: List<RuleLine>): List<RuleLine> {
        val out = mutableListOf<RuleLine>()
        for (l in lines) {
            if (l.type == RuleLine.TYPE_BLANK) {
                if (out.isNotEmpty() && out.last().type != RuleLine.TYPE_BLANK) out.add(l)
            } else {
                out.add(l)
            }
        }
        while (out.isNotEmpty() && out.first().type == RuleLine.TYPE_BLANK) out.removeAt(0)
        while (out.isNotEmpty() && out.last().type == RuleLine.TYPE_BLANK) out.removeAt(out.lastIndex)
        return out
    }
}
