package com.wb.mdgw.pptx

import org.junit.Assert.*
import org.junit.Test

/**
 * 阶段二组合渲染验证：
 * 1. 45 组合（结构 3 × 色块 5 × 对齐 3）文本框均不溢出「带色块内缩后的内容框」；
 *    色块几何正确（左/上/下色条位置、全色反色隐藏 H3 竖线）。
 * 2. 旧 9 版式经 compOf 组合路径 与 直连 layoutOf 路径 逐单元几何完全一致（零回归）。
 * 3. SlideComposition.key 编解码 round-trip。
 */
class CompositionRenderTest {

    private val MD = """
        # 一级标题示例
        ## 二级标题示例
        这是一段正文内容，用于验证组合渲染时文本框落在内容框内、不溢出色块与页边距。
        ### 三级标题示例
        - 列表项一
        - 列表项二
        > 这是一句引用内容，用于验证引用背景与段前距。
    """.trimIndent()

    private fun style() = PptCssParser.parse("").also {
        PptLayoutEngine.style = it
        PptLayoutEngine.waveParams = PptWaveParams()
        PptExportEngine.style = it
    }

    private fun slidesFor(markdown: String, comp: SlideComposition?): List<PptLayoutEngine.LaidOutSlide> {
        style()
        val r = MdAstParser.parse(markdown)
        val paginated = MdAutoPaginator.paginate(r.blocks, autoPaginate = true, r.coverTitle)
        return PptLayoutEngine.layout(
            paginated, PptThemes.byId("default"),
            { _ -> SlideLayout.STANDARD },
            compOf = if (comp == null) ({ _ -> null }) else ({ _ -> comp }),
            enableWave = false
        )
    }

    /** 复刻引擎 frameFor 的期望值（默认样式常量）用于断言文本框边界。 */
    private fun expectedFrame(comp: SlideComposition): Rect {
        val mx = 40; val cTop = 30; val cw = 640; val cBottom = 375; val canvasH = 405
        val bandW = 144; val bandH = 40
        return when (comp.colorBlock) {
            ColorBlock.NONE, ColorBlock.COVER -> Rect(mx, cTop, cw, cBottom - cTop)
            ColorBlock.LEFT -> {
                val left = maxOf(mx, bandW + comp.bandGap)
                Rect(left, cTop, (mx + cw) - left, cBottom - cTop)
            }
            ColorBlock.TOP -> {
                val top = maxOf(cTop, bandH + comp.bandGap)
                Rect(mx, top, cw, cBottom - top)
            }
            ColorBlock.BOTTOM -> {
                val bottom = canvasH - bandH - comp.bandGap
                Rect(mx, cTop, cw, (bottom - cTop).coerceAtLeast(40))
            }
        }
    }

    private fun allCombos(): List<SlideComposition> {
        val out = mutableListOf<SlideComposition>()
        val gap = { cb: ColorBlock -> if (cb == ColorBlock.NONE || cb == ColorBlock.COVER) 0 else 24 }
        for (st in Structure.values()) for (cb in ColorBlock.values()) for (va in VAlign.values()) for (ha in HAlign.values()) {
            out.add(SlideComposition(st, cb, va, ha, gap(cb)))
        }
        return out
    }

    @Test
    fun all45CombinationsKeepTextBoxesInsideFrame() {
        val combos = allCombos()
        assertTrue("应至少覆盖 3×5×3=45 组合，实际 ${combos.size}", combos.size >= 45)
        for (comp in combos) {
            val slides = slidesFor(MD, comp)
            assertTrue("组合 $comp 应生成至少一页", slides.isNotEmpty())
            val slide = slides.first()
            val f = expectedFrame(comp)
            val fLeft = f.x; val fTop = f.y; val fRight = f.x + f.w; val fBottom = f.y + f.h
            for (u in slide.units) {
                assertTrue(
                    "组合 $comp 文本框越左界：x=${u.x} < 框左 $fLeft (unit=${u.type})",
                    u.x >= fLeft - 2
                )
                assertTrue(
                    "组合 $comp 文本框越上界：y=${u.y} < 框上 $fTop (unit=${u.type})",
                    u.y >= fTop - 2
                )
                assertTrue(
                    "组合 $comp 文本框越右界：x+w=${u.x + u.w} > 框右 $fRight (unit=${u.type})",
                    u.x + u.w <= fRight + 2
                )
                assertTrue(
                    "组合 $comp 文本框越下界：y+h=${u.y + u.h} > 框下 $fBottom (unit=${u.type}, 页底 ${PptStyleSheet().canvasH})",
                    u.y + u.h <= fBottom + 2
                )
            }
        }
    }

    @Test
    fun bandedCombinationsRenderCorrectBandGeometry() {
        // 左色：满高左侧色条 144×405 @ (0,0)
        val left = slidesFor(MD, SlideComposition(Structure.VERTICAL, ColorBlock.LEFT, VAlign.TOP, HAlign.LEFT, 24)).first()
        assertTrue("左色应有装饰条", left.deco?.bars?.isNotEmpty() == true)
        val leftBand = left.deco!!.bars.first()
        assertEquals("左色条应 144×405 @ (0,0)", Rect(0, 0, 144, 405), leftBand)

        // 上色：顶部满宽色带 720×40 @ (0,0)
        val top = slidesFor(MD, SlideComposition(Structure.VERTICAL, ColorBlock.TOP, VAlign.TOP, HAlign.LEFT, 24)).first()
        assertTrue("上色应有装饰条", top.deco?.bars?.isNotEmpty() == true)
        assertEquals("上色带应 720×40 @ (0,0)", Rect(0, 0, 720, 40), top.deco!!.bars.first())

        // 下色：底部满宽色带 720×40 @ (0,365)
        val bottom = slidesFor(MD, SlideComposition(Structure.VERTICAL, ColorBlock.BOTTOM, VAlign.CENTER, HAlign.CENTER, 24)).first()
        assertTrue("下色应有装饰条", bottom.deco?.bars?.isNotEmpty() == true)
        assertEquals("下色带应 720×40 @ (0,365)", Rect(0, 365, 720, 40), bottom.deco!!.bars.first())
    }

    @Test
    fun coverColorBlockInvertsTextAndHidesH3Bar() {
        val comp = SlideComposition(Structure.VERTICAL, ColorBlock.COVER, VAlign.CENTER, HAlign.CENTER, 0)
        val slide = slidesFor(MD, comp).first()
        assertTrue("全色色块页应标记 cover（整页反色）", slide.cover)
        // 全色页隐藏 H3 竖线（整页同色不可见）
        assertTrue("全色页不应绘制 H3 竖线", slide.deco?.bars?.isEmpty() != false)
    }

    /**
     * 自定义封面版式：用户修改封面的 HAlign/VAlign 设置后，应能体现到
     * LaidOutUnit 的 align 与 y 坐标上，不再被 hardcode 的居中对齐绕过。
     */
    @Test
    fun coverCompositionRespectsCustomAlign() {
        val coverRole = PageRole.COVER
        // 默认封面（CENTER/CENTER）应保持居中对齐行为
        val defaultCover = SlideComposition(Structure.VERTICAL, ColorBlock.COVER, VAlign.CENTER, HAlign.CENTER, 0, coverRole)
        val defaultSlide = slidesFor(MD, defaultCover).first()
        val defaultMain = defaultSlide.units.first { it.type == BlockType.H1 }
        assertEquals("默认封面 H1 应为居中对齐", PptLayoutEngine.Align.CENTER, defaultMain.align)

        // 自定义封面（LEFT/TOP）：H1 应左对齐，且起始 y 应靠近页顶
        val customCover = SlideComposition(Structure.VERTICAL, ColorBlock.COVER, VAlign.TOP, HAlign.LEFT, 0, coverRole)
        val customSlide = slidesFor(MD, customCover).first()
        val customMain = customSlide.units.first { it.type == BlockType.H1 }
        assertEquals("自定义封面 H1 应为左对齐", PptLayoutEngine.Align.LEFT, customMain.align)
        assertTrue(
            "自定义封面 TOP 对齐时起始 y 应不大于默认封面的 y（更靠近页顶），" +
                "实际 custom.y=${customMain.y} default.y=${defaultMain.y}",
            customMain.y <= defaultMain.y + 1
        )
    }

    @Test
    fun noneColorBlockWithoutH3HasNoBand() {
        val mdNoH3 = "# 标题\n这是正文段落，没有三级标题，用于验证无色块页不绘制装饰条。"
        val slide = slidesFor(mdNoH3, SlideComposition(Structure.VERTICAL, ColorBlock.NONE, VAlign.TOP, HAlign.LEFT, 0)).first()
        assertTrue("无色块页（无 H3）不应有装饰条", slide.deco?.bars?.isEmpty() != false)
    }

    @Test
    fun headingFontSizeConsistentAcrossCombos() {
        // 同一 MD 标签的字号/字体在任意组合下应一致（仅位置/反色不同）
        val combos = listOf(
            SlideComposition(Structure.VERTICAL, ColorBlock.NONE, VAlign.TOP, HAlign.LEFT, 0),
            SlideComposition(Structure.TWO_COL, ColorBlock.LEFT, VAlign.CENTER, HAlign.LEFT, 24),
            SlideComposition(Structure.THREE_COL, ColorBlock.TOP, VAlign.TOP, HAlign.LEFT, 24),
            SlideComposition(Structure.VERTICAL, ColorBlock.COVER, VAlign.CENTER, HAlign.CENTER, 0),
            SlideComposition(Structure.VERTICAL, ColorBlock.BOTTOM, VAlign.CENTER, HAlign.CENTER, 24)
        )
        val sizes = combos.map { comp ->
            slidesFor(MD, comp).first().units.filter { it.type == BlockType.H2 }.map { it.fontSize }.toSet()
        }
        val distinct = sizes.toSet()
        assertEquals("H2 字号在 5 种组合下应完全一致，实际 $distinct", 1, distinct.size)
    }

    @Test
    fun legacyLayoutsIdenticalViaCompositionPath() {
        // 旧 9 版式经 compOf 组合路径 与 直连 layoutOf 路径 逐单元几何必须完全一致（零回归）
        style()
        val r = MdAstParser.parse(MD)
        val paginated = MdAutoPaginator.paginate(r.blocks, autoPaginate = true, r.coverTitle)
        for (layout in SlideLayout.values()) {
            val direct = PptLayoutEngine.layout(paginated, PptThemes.byId("default"), { _ -> layout }, enableWave = false)
            val viaComp = PptLayoutEngine.layout(paginated, PptThemes.byId("default"), { _ -> SlideLayout.STANDARD }, compOf = { _ -> CompositionResolver.compositionOf(layout) }, enableWave = false)
            assertEquals("版式 $layout 页数应一致", direct.size, viaComp.size)
            for (i in direct.indices) {
                val a = direct[i].units; val b = viaComp[i].units
                assertEquals("版式 $layout 第 $i 页单元数应一致", a.size, b.size)
                for (j in a.indices) {
                    val ua = a[j]; val ub = b[j]
                    assertEquals("版式 $layout 单元[$j] x 应一致", ua.x, ub.x)
                    assertEquals("版式 $layout 单元[$j] y 应一致", ua.y, ub.y)
                    assertEquals("版式 $layout 单元[$j] w 应一致", ua.w, ub.w)
                    assertEquals("版式 $layout 单元[$j] h 应一致", ua.h, ub.h)
                    assertEquals("版式 $layout 单元[$j] fontSize 应一致", ua.fontSize, ub.fontSize)
                }
            }
        }
    }

    @Test
    fun compositionKeyRoundTrips() {
        for (comp in allCombos()) {
            val decoded = SlideComposition.fromKey(comp.key)
            assertNotNull("组合 ${comp.key} 应能解码", decoded)
            assertEquals("组合 ${comp.key} 编码解码应一致", comp, decoded)
        }
    }

    @Test
    fun comboWithNonDefaultGapShrinksFrame() {
        val tight = slidesFor(MD, SlideComposition(Structure.VERTICAL, ColorBlock.LEFT, VAlign.TOP, HAlign.LEFT, 8)).first()
        val wide = slidesFor(MD, SlideComposition(Structure.VERTICAL, ColorBlock.LEFT, VAlign.TOP, HAlign.LEFT, 40)).first()
        val tightLeft = tight.units.minOf { it.x }
        val wideLeft = wide.units.minOf { it.x }
        assertTrue("间距 40 应比间距 8 的文本框更靠右（内缩更多）", wideLeft > tightLeft)
    }
}
