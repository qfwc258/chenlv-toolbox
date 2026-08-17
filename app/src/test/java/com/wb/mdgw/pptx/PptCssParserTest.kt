package com.wb.mdgw.pptx

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 PptCssParser：CSS 文本 → PptStyleSheet 字段映射正确。
 * 这是「公众号式 CSS 可编辑样式」的可行性原型测试，不触碰现有渲染引擎。
 */
class PptCssParserTest {

    @Test
    fun emptyKeepsDefault() {
        val s = PptCssParser.parse("")
        assertEquals("默认行距应保留 1.2", 1.2, s.lineMult, 0.0001)
        assertEquals("默认 H1 字号应保留 28", 28, s.fsH1)
        assertEquals("默认主色应保留 C0392B", "C0392B", s.accent)
    }

    @Test
    fun universalLineHeightAndFont() {
        val s = PptCssParser.parse("* { line-height: 1.5; font-family: \"微软雅黑\"; }")
        assertEquals(1.5, s.lineMult, 0.0001)
        assertEquals("微软雅黑", s.bodyFont)
        assertEquals("微软雅黑", s.titleFont)
    }

    @Test
    fun headingFontSizeAndColor() {
        val s = PptCssParser.parse("h1 { font-size: 30pt; color: #9E2A2B; margin-bottom: 14pt; }")
        assertEquals(30, s.fsH1)
        assertEquals("9E2A2B", s.titleColor)
        assertEquals(14, s.headGap)
    }

    @Test
    fun bodyParagraphSpacing() {
        val s = PptCssParser.parse("p { font-size: 17pt; color: #444444; margin-bottom: 10pt; }")
        assertEquals(17, s.fsBody)
        assertEquals("444444", s.bodyColor)
        assertEquals(10, s.paraGap)
    }

    @Test
    fun pxConvertedToPt() {
        // 公众号习惯用 px：16px ≈ 12pt
        val s = PptCssParser.parse("p { font-size: 16px; }")
        assertEquals(12, s.fsBody)
    }

    @Test
    fun accentAndCover() {
        val s = PptCssParser.parse(".accent { color: #2E5FA3; } .cover { background: #1A3C6E; }")
        assertEquals("2E5FA3", s.accent)
        assertEquals("2E5FA3", s.quoteBg) // accent 同时驱动引用背景色
        assertEquals("1A3C6E", s.coverBg)
    }

    @Test
    fun codeFontBg() {
        val s = PptCssParser.parse(".code { font-family: \"Consolas\"; font-size: 13pt; background: #EEEEEE; }")
        assertEquals("Consolas", s.codeFont)
        assertEquals(13, s.fsCode)
        assertEquals("EEEEEE", s.codeBg)
    }

    @Test
    fun slideCanvasAndMargin() {
        val s = PptCssParser.parse(".slide { width: 800pt; height: 450pt; margin: 40pt 30pt; }")
        assertEquals(800, s.canvasW)
        assertEquals(450, s.canvasH)
        assertEquals(40, s.marginTop)
        assertEquals(40, s.marginBottom)
        assertEquals(30, s.marginX)
    }

    @Test
    fun threeDigitHexExpanded() {
        val s = PptCssParser.parse(".accent { color: #f00; }")
        assertEquals("FF0000", s.accent)
    }

    @Test
    fun quoteGapBeforeViaCss() {
        // .quote 的 margin-top = 引用块与上方文本的段前距（对应 quoteGapBefore）
        val s = PptCssParser.parse(".quote { font-size: 15pt; margin-top: 14pt; }")
        assertEquals(14, s.quoteGapBefore)
        assertEquals(15, s.fsQuote)
        assertTrue("quoteGapBefore 应记入 overrides", "quoteGapBefore" in s.overrides)
    }

    @Test
    fun blockquoteAliasAppliesToQuote() {
        // blockquote 是 .quote 的别名：margin-top / color 均应对引用块生效
        val s = PptCssParser.parse("blockquote { margin-top: 18pt; color: #EEF2F8; }")
        assertEquals(18, s.quoteGapBefore)
        assertEquals("EEF2F8", s.quoteBg)
    }

    @Test
    fun multipleRulesOverrideBase() {
        val base = PptStyleSheet(fsBody = 16, lineMult = 1.2)
        val s = PptCssParser.parse("* { line-height: 1.75; } h2 { font-size: 26pt; }", base)
        assertEquals(1.75, s.lineMult, 0.0001)
        assertEquals(26, s.fsH2)
        assertEquals(16, s.fsBody) // 未被覆盖 → 保留 base
    }
}
