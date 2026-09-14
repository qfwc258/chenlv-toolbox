package com.wb.mdgw.shot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切分布局规划器的正确性测试：
 * - 切段边界遵循参考脚本算法（desired = ceil(w×ratio)，向上取整、末段截断）
 * - 尺寸自适应两个不变量：单段显示高 ≤ maxSegH（永不跨页）、两列宽 ≤ usableW
 * - 两列行优先编排、多图按选择顺序拼接、比例 clamp、末段不拉伸
 */
class ShotLayoutTest {

    private val imgW3_6 = ShotLayout.MAX_SEG_H_IN / 3.6 // ≈ 2.98872"

    @Test
    fun segmentBoundsFollowRatio() {
        // 1080 宽 × 3.6 = 3888 → 高 8000 分 3 段：[0,3888) [3888,7776) [7776,8000)
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 3.6)
        assertEquals(3, p.segments.size)
        assertEquals(3888, p.segments[0].let { it.yPx to it.hPx }.let { (y, h) -> h })
        val s0 = p.segments[0]
        assertEquals(0, s0.yPx); assertEquals(3888, s0.hPx); assertEquals(1080, s0.wPx)
        val s1 = p.segments[1]
        assertEquals(3888, s1.yPx); assertEquals(3888, s1.hPx)
        val s2 = p.segments[2]
        assertEquals(7776, s2.yPx); assertEquals(224, s2.hPx) // 8000-7776
    }

    @Test
    fun defaultRatioAdaptsToPageHeight() {
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 3.6)
        assertEquals(imgW3_6, p.imgWIn, 1e-6)
        // 不变量一：名义段高不超页面可用高度（单段永不跨页）
        assertTrue(p.imgWIn * 3.6 <= ShotLayout.MAX_SEG_H_IN + 1e-9)
        // 不变量二：两列不溢出可用宽度
        assertTrue(2 * p.imgWIn <= ShotLayout.USABLE_W_IN + 1e-9)
    }

    @Test
    fun smallRatioUsesFullColumnWidth() {
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 1.5)
        assertEquals(ShotLayout.COL_W_IN - 0.02, p.imgWIn, 1e-6) // ≈ 3.7202"
        // 宽度约束生效时段高仍受 maxSegH 限制
        assertTrue(p.imgWIn * 1.5 <= ShotLayout.MAX_SEG_H_IN + 1e-9)
    }

    @Test
    fun extremeTallImageSegmentsStayWithinPage() {
        // 500×20000 的极端长条：12 段，每段显示高 = imgW×1800/500 = 名义段高 ≤ maxSegH
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(500, 20000)), 3.6)
        assertEquals(12, p.segments.size) // ceil(20000/1800)
        p.segments.forEach { assertTrue(it.dispHIn <= ShotLayout.MAX_SEG_H_IN + 1e-9) }
    }

    @Test
    fun everySegmentNeverExceedsPageHeight() {
        // 扫全比例区间 × 各种宽高比，不变量必须恒成立
        for (ratio in listOf(1.0, 1.5, 2.0, 2.5, 2.89, 3.6, 4.0, 5.0)) {
            val p = ShotLayout.plan(
                listOf(
                    ShotLayout.ImageInput(1080, 500),
                    ShotLayout.ImageInput(720, 30000),
                    ShotLayout.ImageInput(2000, 2000)
                ), ratio
            )
            assertTrue(p.imgWIn * ratio <= ShotLayout.MAX_SEG_H_IN + 1e-9)
            assertTrue(2 * p.imgWIn <= ShotLayout.USABLE_W_IN + 1e-9)
            p.segments.forEach { assertTrue("ratio=$ratio dispH=${it.dispHIn}", it.dispHIn <= ShotLayout.MAX_SEG_H_IN + 1e-9) }
        }
    }

    @Test
    fun shortImageSingleSegment() {
        // 高 2000 < desired 3888 → 整图一段，显示高按实际像素算
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 2000)), 3.6)
        assertEquals(1, p.segments.size)
        assertEquals(0, p.segments[0].yPx)
        assertEquals(2000, p.segments[0].hPx)
        assertEquals(p.imgWIn * 2000.0 / 1080.0, p.segments[0].dispHIn, 1e-9)
    }

    @Test
    fun twoColumnOrdering() {
        // 7 张各 1 段的图 → 7 段 → 4 行，末行右格为空；行优先填充
        val p = ShotLayout.plan(List(7) { ShotLayout.ImageInput(1080, 2000) }, 3.6)
        assertEquals(7, p.segments.size)
        assertEquals(4, p.rows)
        assertEquals(p.segments[0], p.cell(0, 0))
        assertEquals(p.segments[1], p.cell(0, 1))
        assertEquals(p.segments[2], p.cell(1, 0))
        assertEquals(p.segments[6], p.cell(3, 0))
        assertNull(p.cell(3, 1)) // 末行不满 → 空格子
        assertNull(p.cell(9, 0)) // 越界安全
        assertNotNull(p.cell(3, 0))
    }

    @Test
    fun multiImageConcatenationInSelectionOrder() {
        // 图 A 3 段 + 图 B 2 段 → 填充顺序 [A1,A2,A3,B1,B2]
        val a = ShotLayout.ImageInput(1000, 11000) // desired 3600 → 4 段？ceil(11000/3600)=4
        val b = ShotLayout.ImageInput(1000, 7000)  // ceil(7000/3600) = 2 段
        val p = ShotLayout.plan(listOf(a, b), 3.6)
        assertEquals(4 + 2, p.segments.size)
        assertEquals(listOf(0, 0, 0, 0, 1, 1), p.segments.map { it.imageIndex })
        assertEquals(listOf(0, 1, 2, 3, 0, 1), p.segments.map { it.segIndex })
    }

    @Test
    fun ratioClamped() {
        val low = ShotLayout.plan(listOf(ShotLayout.ImageInput(1000, 10000)), 0.5)
        val high = ShotLayout.plan(listOf(ShotLayout.ImageInput(1000, 10000)), 99.0)
        // 低比例 clamp 到 1.0：desired=1000 → 10 段
        assertEquals(10, low.segments.size)
        // 高比例 clamp 到 5.0：desired=5000 → 2 段
        assertEquals(2, high.segments.size)
    }

    @Test
    fun lastSegmentNotStretched() {
        // 末段 224px 的显示高 = imgW×224/1080，不等于名义段高 imgW×3.6
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 3.6)
        val last = p.segments.last()
        assertEquals(p.imgWIn * 224.0 / 1080.0, last.dispHIn, 1e-9)
        assertTrue(last.dispHIn < p.imgWIn * 3.6 - 1.0)
    }

    @Test
    fun pagesEstimate() {
        // 默认 3.6：1080×8000 → 3 段、名义段高 ≈ 10.76" → 每页 1 行 → 2 页
        val p36 = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 3.6)
        assertEquals(3, p36.segments.size)
        assertEquals(2, p36.rows)
        assertEquals(2, p36.pages) // ceil(2 行 / 1 行每页)
        // 小比例 1.5：desired=1620 → 5 段、3 行；段高 ≈ 5.58" → 每页 1 行 → 3 页
        val p15 = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 1.5)
        assertEquals(5, p15.segments.size)
        assertEquals(3, p15.rows)
        assertEquals(3, p15.pages)
    }

    @Test
    fun emptyAndInvalidInputsSafe() {
        val empty = ShotLayout.plan(emptyList(), 3.6)
        assertEquals(0, empty.segments.size)
        assertEquals(0, empty.rows)
        assertEquals(0, empty.pages)
        val invalid = ShotLayout.plan(
            listOf(ShotLayout.ImageInput(0, 100), ShotLayout.ImageInput(100, 0), ShotLayout.ImageInput(-5, 10)),
            3.6
        )
        assertEquals(0, invalid.segments.size)
    }

    @Test
    fun estimatedBytesPositive() {
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 8000)), 3.6)
        assertTrue(p.estimatedBytes > 0)
        // 1080×8000 px × 0.4MB/百万px ≈ 3.3MB
        assertTrue("估算值异常: ${p.estimatedBytes}", p.estimatedBytes in 2_000_000..5_000_000)
    }

    // ----------------------- 整图模式（不切分） -----------------------

    @Test
    fun wholeImageModeKeepsOneSegmentPerImage() {
        // 关闭切分：整图作为一格，不切段
        val cfg = ShotLayout.ShotConfig(splitLongImage = false)
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 2000)), cfg)
        assertEquals(1, p.segments.size)
        assertEquals(0, p.segments[0].yPx)
        assertEquals(2000, p.segments[0].hPx)
        // 显示高 = imgW × 整图纵横比
        assertEquals(p.imgWIn * 2000.0 / 1080.0, p.segments[0].dispHIn, 1e-9)
    }

    @Test
    fun wholeImageGridColumns() {
        // 关闭切分 + 3 列：2 张图 → 2 段、3 列、1 行、末列空格子
        val cfg = ShotLayout.ShotConfig(splitLongImage = false, columns = 3)
        val p = ShotLayout.plan(List(2) { ShotLayout.ImageInput(1080, 2000) }, cfg)
        assertEquals(3, p.cols)
        assertEquals(2, p.segments.size)
        assertEquals(1, p.rows)
        assertNotNull(p.cell(0, 0))
        assertNotNull(p.cell(0, 1))
        assertNull(p.cell(0, 2))
    }

    @Test
    fun wholeImageTallNotExceedingPage() {
        // 极竖图（500×3000，aspect=6）：整图模式仍须单格不跨页
        val cfg = ShotLayout.ShotConfig(splitLongImage = false)
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(500, 3000)), cfg)
        assertEquals(1, p.segments.size)
        assertTrue(p.segments[0].dispHIn <= ShotLayout.MAX_SEG_H_IN + 1e-9)
    }

    // ----------------------- 自定义列数 / 行数 -----------------------

    @Test
    fun customRowsMergedWithAutoLower() {
        // 用户行数(1) 小于自动下限(2) → 取自动下限，保证全部放下
        val cfg = ShotLayout.ShotConfig(splitLongImage = true, splitRatio = 3.6, rows = 1)
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 9999)), cfg) // 3 段 → 自动 2 行
        assertEquals(3, p.segments.size)
        assertEquals(2, p.rows)
    }

    @Test
    fun customRowsLargerThanAutoAddsBlankRows() {
        // 用户行数(5) 大于自动下限(1) → 实际 5 行（含空白行），不裁掉图片
        val cfg = ShotLayout.ShotConfig(splitLongImage = true, splitRatio = 3.6, rows = 5)
        val p = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 2000)), cfg) // 1 段 → 自动 1 行
        assertEquals(5, p.rows)
        assertNotNull(p.cell(0, 0))
        assertNull(p.cell(0, 1)) // 一行只有 1 段，右格空
        assertNull(p.cell(4, 0)) // 第 5 行无内容（空行占位）
    }

    @Test
    fun columnsClampedToRange() {
        val tooMany = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 2000)),
            ShotLayout.ShotConfig(splitLongImage = false, columns = 99))
        assertEquals(ShotLayout.MAX_COLS, tooMany.cols)
        val tooFew = ShotLayout.plan(listOf(ShotLayout.ImageInput(1080, 2000)),
            ShotLayout.ShotConfig(splitLongImage = false, columns = 0))
        assertEquals(ShotLayout.MIN_COLS, tooFew.cols)
    }

    @Test
    fun planColsFieldUsedForCellIndexing() {
        // 3 列：3 张图各 3 段（共 9 段）→ 3 行；列优先填充顺序验证
        val cfg = ShotLayout.ShotConfig(splitLongImage = true, splitRatio = 3.6, columns = 3)
        val p = ShotLayout.plan(List(3) { ShotLayout.ImageInput(1080, 8000) }, cfg)
        assertEquals(3, p.cols)
        assertEquals(9, p.segments.size)
        assertEquals(3, p.rows)
        assertEquals(p.segments[0], p.cell(0, 0))
        assertEquals(p.segments[2], p.cell(0, 2))
        assertEquals(p.segments[3], p.cell(1, 0))
        assertEquals(p.segments[8], p.cell(2, 2))
        assertNull(p.cell(3, 0))
    }
}
