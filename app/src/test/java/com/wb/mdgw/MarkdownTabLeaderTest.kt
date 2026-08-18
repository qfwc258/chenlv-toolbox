package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Markdown 制表位前导符填空线（扩展语法）的解析与往返。
 *
 * 覆盖：
 *  - `tab `（下划线填空线）/ `tab. `（点线目录）/ `tab- `（虚线）/ `tab@Ncm `（指定制表位）
 *  - 段落 props.tabs 写入对应 leader；run 文本含 `\t`（预览 flex 前导线、导出 w:tab 的触发点）
 *  - 生成的 docx 含 `w:tabs` 与 `w:leader`
 *  - 反向 toMarkdown() 还原为 `tab` 语法，保证往返一致
 */
class MarkdownTabLeaderTest {

    private val options = MdToGongwen.Options() // 默认诉讼文书规范（A4，左右 2.5cm）

    @Test
    fun `tab 前缀生成下划线填空线`() {
        val doc = MdToGongwen.convert("tab 甲方（盖章）：", options)
        val p = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals(Align.LEFT, p.props.align)
        assertEquals(0.0, p.props.firstLineIndentPt, 0.001)

        assertEquals(1, p.props.tabs.size)
        assertEquals("underline", p.props.tabs[0].leader)
        assertEquals("right", p.props.tabs[0].align)
        // 默认制表位位置 = 可用正文宽度（21 - 2.5 - 2.5 = 16cm → 453.5pt）
        assertEquals(453.5, p.props.tabs[0].posPt, 1.0)

        val joined = p.runs.joinToString("") { it.text }
        assertTrue("run 文本应含制表符 \\t", joined.contains('\t'))
        assertEquals("甲方（盖章）：", joined.replace("\t", ""))
    }

    @Test
    fun `tab. 前缀生成点线目录且右侧文字右对齐`() {
        val doc = MdToGongwen.convert("tab. 第一章 总则::1", options)
        val p = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals("dot", p.props.tabs[0].leader)

        val runs = p.runs
        assertEquals("第一章 总则", runs.first().text)
        assertTrue("中间应有一个 \\t run", runs.any { it.text.contains('\t') })
        assertEquals("1", runs.last().text)
    }

    @Test
    fun `tab- 前缀生成虚线`() {
        val doc = MdToGongwen.convert("tab- 项目::说明", options)
        val p = doc.blocks.filterIsInstance<Block.Para>().single()
        assertEquals("dash", p.props.tabs[0].leader)
    }

    @Test
    fun `tab@Ncm 指定制表位位置`() {
        val doc = MdToGongwen.convert("tab@12cm 乙方（签字）：", options)
        val p = doc.blocks.filterIsInstance<Block.Para>().single()
        // 12cm → 12 * 566.929 / 20 = 340.2pt
        assertEquals(340.2, p.props.tabs[0].posPt, 1.0)
    }

    @Test
    fun `普通段落不以 tab 开头时被误判`() {
        // "table ..." 不应被解析为填空线
        val doc = MdToGongwen.convert("table of contents", options)
        val p = doc.blocks.filterIsInstance<Block.Para>().single()
        assertTrue(p.props.tabs.isEmpty())
        assertEquals("table of contents", p.runs.joinToString("") { it.text })
    }

    @Test
    fun `填空线段落导出的 docx 含 w:tabs 与 w:leader`() {
        val doc = MdToGongwen.convert("tab 甲方（盖章）：", options)
        val xml = String(doc.toDocx())
        assertTrue("应含 w:tabs", xml.contains("<w:tabs>"))
        assertTrue("应含 w:leader", xml.contains("w:leader=\"underline\""))
        assertTrue("应含 w:tab 制表符", xml.contains("<w:tab/>"))
    }

    @Test
    fun `toMarkdown 反向还原为 tab 语法`() {
        val doc = MdToGongwen.convert("tab 甲方（盖章）：", options)
        val md = doc.toMarkdown()
        assertTrue(md.contains("tab 甲方（盖章）："))

        val toc = MdToGongwen.convert("tab. 第一章 总则::1", options)
        assertTrue(toc.toMarkdown().contains("tab. 第一章 总则::1"))
    }
}
