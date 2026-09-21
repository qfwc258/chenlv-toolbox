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
        defaults: List<RuleLine>
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
            result.add(RuleLine.field(trimmedKey, value))
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
        result.add(insertAt, RuleLine.field(trimmedKey, value))
        return result
    }
}
