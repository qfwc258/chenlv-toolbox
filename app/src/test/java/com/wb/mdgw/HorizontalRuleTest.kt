package com.wb.mdgw

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 MdToGongwen 对 `---` 的处理：
 * - `---` 按正常 Markdown 水平线解读（渲染为空行），不再触发落款区右对齐
 * - `rr ` 前缀用于逐行右对齐（落款用）
 */
class HorizontalRuleTest {

    private fun convert(md: String): GovDoc =
        MdToGongwen.convert(md, MdToGongwen.Options())

    private fun paraTexts(doc: GovDoc): List<Pair<String, Align>> =
        doc.blocks.filterIsInstance<Block.Para>()
            .filter { it.runs.isNotEmpty() }
            .map { it.runs.joinToString("") { r -> r.text } to it.props.align }

    /**
     * 核心场景：`---` 之后的内容不应被右对齐，应保持正文默认对齐（两端对齐）。
     */
    @Test
    fun `horizontal rule does not trigger right alignment`() {
        val md = """
            # 测试标题

            这是一段正文内容。

            ---

            这段在分隔线之后，应保持正文对齐而非右对齐。
        """.trimIndent()

        val doc = convert(md)
        val paras = paraTexts(doc)

        // 找到"分隔线之后"的段落
        val afterHr = paras.find { it.first.contains("分隔线之后") }
        assertNotNull("应存在分隔线之后的段落", afterHr)
        assertNotEquals(
            "分隔线后的段落不应右对齐",
            Align.RIGHT,
            afterHr!!.second
        )
    }

    /**
     * `---` 应被渲染为空行（空段落），而非被忽略或触发落款区。
     */
    @Test
    fun `horizontal rule rendered as empty paragraph`() {
        val md = """
            上文

            ---

            下文
        """.trimIndent()

        val doc = convert(md)
        // 应存在至少一个空段落（--- 渲染为空行）
        val hasEmpty = doc.blocks.filterIsInstance<Block.Para>()
            .any { it.runs.isEmpty() || it.runs.all { r -> r.text.isBlank() } }
        assertTrue("`---` 应渲染为空段落（分隔）", hasEmpty)
    }

    /**
     * `rr ` 前缀应触发右对齐、不缩进。
     */
    @Test
    fun `rr prefix triggers right alignment`() {
        val md = """
            # 答辩状

            正文内容。

            rr 申请人：张三
            rr 二〇二六年八月十五日
        """.trimIndent()

        val doc = convert(md)
        val paras = paraTexts(doc)

        val applicant = paras.find { it.first.contains("申请人") }
        assertNotNull("应存在申请人段落", applicant)
        assertEquals("rr 前缀应右对齐", Align.RIGHT, applicant!!.second)

        val date = paras.find { it.first.contains("二〇二六") }
        assertNotNull("应存在日期段落", date)
        assertEquals("rr 前缀应右对齐", Align.RIGHT, date!!.second)
    }

    /**
     * `rr ` 前缀应被剥离，不出现在输出文本中。
     */
    @Test
    fun `rr prefix is stripped from output`() {
        val md = "rr 申请人：张三"

        val doc = convert(md)
        val paras = paraTexts(doc)

        val applicant = paras.firstOrNull()
        assertNotNull(applicant)
        assertFalse("rr 前缀不应出现在输出文本中", applicant!!.first.contains("rr "))
        assertTrue("应保留实际内容", applicant.first.contains("申请人"))
    }

    /**
     * 多个 `---` 不应累积触发落款区——每个都是独立的水平线。
     */
    @Test
    fun `multiple horizontal rules are independent`() {
        val md = """
            第一段

            ---

            第二段

            ---

            第三段
        """.trimIndent()

        val doc = convert(md)
        val paras = paraTexts(doc)

        // 三段正文都应是非右对齐
        val textParas = paras.filter { it.first.contains("段") }
        assertEquals("应有3个正文段落", 3, textParas.size)
        textParas.forEach { (text, align) ->
            assertNotEquals(
                "段落'$text'不应右对齐（--- 不触发落款区）",
                Align.RIGHT,
                align
            )
        }
    }

    /**
     * 表格中的 `|---|---|` 分隔行不应被误判为水平线。
     */
    @Test
    fun `table separator not confused with horizontal rule`() {
        val md = """
            | 列1 | 列2 |
            | --- | --- |
            | 内容A | 内容B |
        """.trimIndent()

        val doc = convert(md)
        // 应存在表格块
        val hasTable = doc.blocks.filterIsInstance<Block.Table>().isNotEmpty()
        assertTrue("应正确解析表格", hasTable)

        val table = doc.blocks.filterIsInstance<Block.Table>().first()
        // 表头行 + 数据行
        assertTrue("表格应至少有2行（表头+数据）", table.rows.size >= 2)
        // 找到数据行（含"内容A"）
        val dataRow = table.rows.find { row -> row.any { cell -> cell.joinToString("") { it.text }.contains("内容A") } }
        assertNotNull("表格应包含数据行'内容A'", dataRow)
        assertEquals("数据行第一列应为'内容A'", "内容A", dataRow!![0].joinToString("") { it.text })
    }
}
