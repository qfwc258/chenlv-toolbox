package com.wb.mdgw.wechat

import java.util.LinkedHashMap

/**
 * 极简 CSS 解析器：把主题 CSS 文本解析为「选择器 -> 声明」规则列表，
 * 供 MdToWechat 逐元素内联使用。
 *
 * 支持：
 *   - 多选择器逗号分隔（h1, h2）
 *   - 后代选择器（pre code、ul li）
 *   - 去掉 /* 注释 */、!important
 *   - 兼容普通声明（font-size:16px; color:#333;）
 *
 * 不支持（也不需要）：@media / @keyframes / 嵌套，因为公众号主题只用静态声明。
 */
object CssParser {

    data class CssRule(
        val selectors: List<String>,
        val declarations: Map<String, String>
    ) {
        val isBodyRule: Boolean
            get() = selectors.any { it.trim().equals("body", ignoreCase = true) }
    }

    fun parse(css: String): List<CssRule> {
        val rules = mutableListOf<CssRule>()
        val noComments = css.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        val blocks = noComments.split('}')

        for (block in blocks) {
            val brace = block.indexOf('{')
            if (brace < 0) continue
            val selectorPart = block.substring(0, brace).trim()
            val declPart = block.substring(brace + 1).trim()
            if (selectorPart.isEmpty() || declPart.isEmpty()) continue

            val selectors = selectorPart.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val declarations = parseDeclarations(declPart)
            if (selectors.isNotEmpty() && declarations.isNotEmpty()) {
                rules.add(CssRule(selectors, declarations))
            }
        }
        return rules
    }

    private fun parseDeclarations(decl: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        decl.split(';').forEach { pair ->
            val idx = pair.indexOf(':')
            if (idx <= 0) return@forEach
            val prop = pair.substring(0, idx).trim().lowercase()
            var value = pair.substring(idx + 1).trim()
            if (value.isEmpty()) return@forEach
            value = value.replace("!important", "", ignoreCase = true).trim()
            if (prop.isNotEmpty()) map[prop] = value
        }
        return map
    }
}
