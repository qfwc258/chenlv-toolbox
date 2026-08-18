package com.wb.mdgw

/**
 * Markdown 快捷片段（纯逻辑，不依赖 Compose / Android）。
 *
 * 文档编辑页的「格式工具栏」与「空状态提示」都复用这里的片段定义与插入算法，
 * 插入位置的计算抽成纯函数 [apply]，便于单元测试覆盖。
 */
object MarkdownSnippets {

    /**
     * 一个可插入的 Markdown 片段。
     *
     * @param id       稳定标识（便于测试与去重）
     * @param label    工具栏上展示的短标签
     * @param snippet  实际插入到正文的内容（可多行）
     * @param caretOffset 插入后光标应停在 [snippet] 内部的偏移量；
     *        通常设在占位符中间（如 `**‸**`）或片段末尾（块级前缀）
     */
    data class Snippet(
        val id: String,
        val label: String,
        val snippet: String,
        val caretOffset: Int
    )

    /** 插入结果：新的全文 + 新的光标位置（绝对偏移） */
    data class EditResult(val text: String, val caret: Int)

    /**
     * 在选区 `[selStart, selEnd)` 处插入 [snippet]。
     *
     * 插入后光标落在 `snippet` 内部第 [Snippet.caretOffset] 个字符之后，
     * 便于用户直接接着输入（例如加粗片段光标停在 `**` 与 `**` 之间）。
     *
     * 纯函数：不修改入参、可重复调用、无副作用。
     */
    fun apply(
        text: String,
        selStart: Int,
        selEnd: Int,
        snippet: Snippet
    ): EditResult {
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(s, text.length)
        val before = text.substring(0, s)
        val after = text.substring(e)
        val newText = before + snippet.snippet + after
        val offsetInSnippet = snippet.caretOffset.coerceIn(0, snippet.snippet.length)
        val caret = (before.length + offsetInSnippet).coerceIn(0, newText.length)
        return EditResult(newText, caret)
    }

    /**
     * 文档页格式工具栏的片段集合（顺序即展示顺序）。
     * 块级片段（标题/引用/列表等）caretOffset 指向片段末尾，
     * 包裹型片段（加粗/斜体）caretOffset 指向占位符中间。
     */
    val SNIPPETS: List<Snippet> = listOf(
        Snippet("h1", "H1", "# ", 2),
        Snippet("h2", "H2", "## ", 3),
        Snippet("bold", "加粗", "**加粗**", 2),
        Snippet("italic", "斜体", "*斜体*", 1),
        Snippet("quote", "引用", "> ", 2),
        Snippet("ul", "列表", "- ", 2),
        Snippet("ol", "编号", "1. ", 3),
        Snippet(
            "table", "表格",
            "| 列1 | 列2 |\n| --- | --- |\n| 内容 | 内容 |\n",
            "| 列1 | 列2 |\n| --- | --- |\n| 内容 | 内容 |\n".length
        ),
        Snippet("hr", "分割线", "---\n", 4),
        Snippet("tabline", "填空线", "tab 甲方（盖章）：", 4),
        Snippet(
            "sign", "落款",
            "\n（此致）\n\n敬礼！\n\nrr 申请人：\nrr 二〇二六年八月十一日\n",
            0
        )
    )

    /** 空状态提示用的精简片段（避免占位符文字干扰新手，直接给纯语法） */
    val HINT_SNIPPETS: List<Snippet> = listOf(
        Snippet("h1", "# 标题", "# ", 2),
        Snippet("bold", "**加粗**", "**加粗**", 2),
        Snippet("quote", "> 引用", "> ", 2),
        Snippet("table", "| 表格 |", "| 表格 |\n| --- |\n| 内容 |\n", 0)
    )
}
