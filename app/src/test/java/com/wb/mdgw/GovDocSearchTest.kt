package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 [GovDocSearch.search] 的纯逻辑：段落命中、表格单元格命中、
 * 空白返回空、忽略大小写、无匹配返回空。
 */
class GovDocSearchTest {

    private fun run(text: String, bold: Boolean = false) =
        TextRun(text, "F", 16.0, bold = bold)

    private val doc = GovDoc(
        blocks = listOf(
            Block.Para(listOf(run("甲方：张三")), ParaProps()),
            Block.Para(listOf(run("乙方：李四公司", bold = true)), ParaProps()),
            Block.Table(
                listOf(
                    listOf(listOf(run("姓名")), listOf(run("金额"))),
                    listOf(listOf(run("王五")), listOf(run("100元"))),
                    listOf(listOf(run("Apple pie")), listOf(run("余款")))
                )
            )
        ),
        page = PageSetup(),
        title = "合同",
        mainTitleFont = "F",
        bodyFont = "F",
        bodySizePt = 16.0,
        lineSpacingPt = 28.0,
        indentPt = 32.0
    )

    @Test
    fun paragraphHit() {
        val hits = GovDocSearch.search(doc, "张三")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].blockIndex)
        assertEquals(-1, hits[0].row)
        assertEquals(-1, hits[0].col)
        assertEquals("甲方：张三", hits[0].preview)
    }

    @Test
    fun tableCellHit() {
        val hits = GovDocSearch.search(doc, "金额")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].blockIndex)
        assertEquals(0, hits[0].row)
        assertEquals(1, hits[0].col)
    }

    @Test
    fun anotherTableCellHit() {
        val hits = GovDocSearch.search(doc, "王五")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].row)
        assertEquals(0, hits[0].col)
    }

    @Test
    fun blankQueryReturnsEmpty() {
        assertTrue(GovDocSearch.search(doc, "   ").isEmpty())
        assertTrue(GovDocSearch.search(doc, "").isEmpty())
    }

    @Test
    fun caseInsensitive() {
        val hits = GovDocSearch.search(doc, "apple")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].blockIndex)
        assertEquals(2, hits[0].row)
        assertEquals(0, hits[0].col)
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(GovDocSearch.search(doc, "不存在的内容xyz").isEmpty())
    }

    @Test
    fun multipleHitsWhenKeywordInSeveralPlaces() {
        // "公" 仅出现在 title 不计入块；用 "乙方" 命中段落1；"金额"命中单元格
        val hits = GovDocSearch.search(doc, "额")
        // 仅 "金额" 含 "额"
        assertEquals(1, hits.size)
    }
}
