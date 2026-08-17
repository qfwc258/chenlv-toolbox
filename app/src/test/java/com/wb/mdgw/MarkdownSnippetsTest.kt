package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆盖 [MarkdownSnippets.apply] 的纯逻辑：光标处插入、占位符定位、
 * 多行片段、选区替换、边界 clamp。这些是文档页「格式工具栏」的核心算法。
 */
class MarkdownSnippetsTest {

    private fun snip(id: String) = MarkdownSnippets.SNIPPETS.first { it.id == id }

    @Test
    fun plainInsertAtCursor() {
        val s = snip("h1") // "# ", caretOffset=2
        val r = MarkdownSnippets.apply("abcd", 2, 2, s)
        assertEquals("ab# cd", r.text)
        assertEquals(4, r.caret) // before(2) + offset(2)
    }

    @Test
    fun boldCaretStaysInsidePlaceholder() {
        val s = snip("bold") // "**加粗**", caretOffset=2
        val r = MarkdownSnippets.apply("你好", 2, 2, s)
        assertEquals("你好**加粗**", r.text)
        assertEquals(4, r.caret) // 光标停在 ** 与 ** 之间
    }

    @Test
    fun multiLineTableCopiedVerbatim() {
        val s = snip("table")
        val r = MarkdownSnippets.apply("", 0, 0, s)
        assertEquals(s.snippet, r.text)
        assertEquals(s.snippet.length, r.caret)
    }

    @Test
    fun selectionIsReplacedBySnippet() {
        val s = snip("quote") // "> ", caretOffset=2
        val r = MarkdownSnippets.apply("abcDEFg", 3, 6, s) // 选中 "DEF"
        assertEquals("abc> g", r.text)
        assertEquals(5, r.caret) // before(3) + offset(2)
    }

    @Test
    fun insertAtVeryStart() {
        val s = snip("ul") // "- ", caretOffset=2
        val r = MarkdownSnippets.apply("xy", 0, 0, s)
        assertEquals("- xy", r.text)
        assertEquals(2, r.caret)
    }

    @Test
    fun signOffsetsCaretToBlockStart() {
        val s = snip("sign") // caretOffset=0（整块，光标回到插入点）
        val r = MarkdownSnippets.apply("正文", 2, 2, s)
        assertEquals("正文" + s.snippet, r.text)
        assertEquals(2, r.caret)
    }

    @Test
    fun outOfRangeSelectionIsClamped() {
        val s = snip("hr") // "---\n", caretOffset=4
        // 越界选区被 clamp 到合法区间，不应抛异常
        val r = MarkdownSnippets.apply("ab", 5, 9, s)
        assertEquals("ab---\n", r.text)
        assertEquals(6, r.caret)
    }
}
