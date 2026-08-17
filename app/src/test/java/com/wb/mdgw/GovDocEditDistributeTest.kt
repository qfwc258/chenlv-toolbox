package com.wb.mdgw

import org.junit.Test
import org.junit.Assert.*

class GovDocEditDistributeTest {

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, underline: Boolean = false) =
        TextRun(text, "仿宋_GB2312", 16.0, bold, italic, underline)

    @Test
    fun distributeKeepsUnderlineRunLengthAndNeverSpillsPlainText() {
        // 甲方（盖章）代表：  —— 下划线在段落中部
        val runs = listOf(
            run("甲方"),
            run("（盖章）", underline = true),
            run("代表：")
        )
        // 用户只改了普通文字、整体变长，新增内容由明文 run 吸收
        val out = distributeRunsRespectingFormat(runs, "乙方（盖章）代表：变更生效")
        // 下划线 run 仍锁定为原字段「（盖章）」，绝不吞并明文字
        assertEquals("（盖章）", out[1])
        // 明文 run 吸收了多出来的字
        assertEquals("乙方", out[0])
        assertEquals("代表：变更生效", out[2])
        // 拼接后整体文字正确
        assertEquals("乙方（盖章）代表：变更生效", out.joinToString(""))
    }

    @Test
    fun distributeShrinkKeepsFormatRunLength() {
        // 收缩场景：整体变短，可保证的不变式是「下划线字段长度锁定、整体文字正确拼接」；
        // 精确左边界位置由「按字段拆分」模式负责（见 applyEditGroups）。
        val runs = listOf(
            run("甲方"),
            run("（盖章）", underline = true),
            run("代表：落款区多余文字")
        )
        val out = distributeRunsRespectingFormat(runs, "甲方（盖章）代表：")
        assertEquals(4, out[1].length) // 下划线字段长度锁定为原长
        assertEquals("甲方（盖章）代表：", out.joinToString(""))
    }

    @Test
    fun distributeSingleFormatRunCanGrowFreely() {
        // 整段只有一个带下划线的 run：改长改短都不受限
        val runs = listOf(run("（盖章）", underline = true))
        assertEquals(listOf("（已盖章确认）"), distributeRunsRespectingFormat(runs, "（已盖章确认）"))
        assertEquals(listOf("章"), distributeRunsRespectingFormat(runs, "章"))
    }

    @Test
    fun distributeEmptyNewTextClearsAllRuns() {
        val runs = listOf(run("甲"), run("乙", underline = true), run("丙"))
        assertEquals(listOf("", "", ""), distributeRunsRespectingFormat(runs, ""))
    }

    @Test
    fun groupRunsMergesAdjacentSameStyle() {
        // Word 常把同一段明文拆成多个 run（如带不同 rsid），应被合并为一组
        val runs = listOf(run("甲方"), run("代表："), run("（盖章）", underline = true), run("落款"))
        val groups = groupRuns(runs)
        assertEquals(3, groups.size) // 明文组 + 下划线组 + 明文组
        assertEquals(listOf(0, 1), groups[0].runIndices)
        assertEquals("甲方代表：", groups[0].text)
        assertEquals(listOf(2), groups[1].runIndices)
        assertTrue(groups[1].underline)
        assertEquals(listOf(3), groups[2].runIndices)
    }

    @Test
    fun groupRunsKeepsDistinctStylesSeparate() {
        val runs = listOf(
            run("a", bold = true),
            run("b", italic = true),
            run("c")
        )
        val groups = groupRuns(runs)
        assertEquals(3, groups.size)
        assertFalse(groups[0].underline)
        assertTrue(groups[0].bold)
        assertTrue(groups[1].italic)
        assertFalse(groups[2].bold || groups[2].italic || groups[2].underline)
    }

    @Test
    fun proportionalSplitSumsToTotal() {
        val counts = proportionalSplit(listOf(2, 4, 3), 12)
        assertEquals(12, counts.sum())
        assertEquals(3, counts.size)
        // 按 2:4:3 比例下取整：2、8-2=6、12-8=4
        assertEquals(listOf(2, 6, 4), counts)

        // 总长度 0 时全 0
        assertEquals(listOf(0, 0), proportionalSplit(listOf(1, 1), 0))
        // 单 run 时全部归它
        assertEquals(listOf(7), proportionalSplit(listOf(0), 7))
    }
}
