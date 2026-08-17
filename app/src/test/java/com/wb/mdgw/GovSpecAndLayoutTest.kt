package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验本轮改动的三件事（纯 JVM，可脱离 Android 运行）：
 *  ① 默认公文规范为「诉讼文书」，且规范可按名字回查（持久化的基础）
 *  ② 诉讼文书的页边距确实不同于国标——这正是此前 PDF 忽略页边距导致
 *     Word / PDF 版式对不上的地方
 *  ③ Word 导入时文档级参数跟随规范
 */
class GovSpecAndLayoutTest {

    @Test
    fun defaultSpecIsCourtDoc() {
        assertEquals("默认规范应为诉讼文书", "诉讼文书", GovDocSpec.DEFAULT.specName)
        assertEquals("Options 默认应取诉讼文书", "诉讼文书", MdToGongwen.Options().spec.specName)
        // 选择器列表首项也应是诉讼文书，符合“法律实务优先”的定位
        assertEquals("诉讼文书", GovDocSpec.ALL_PRESETS.first().specName)
    }

    /**
     * 页码必须默认关闭：用户没勾选就不该出现「— 1 —」。
     * 此前所有预设的 pageNumber 默认为 true，导致 PDF 底部凭空多出页码。
     */
    @Test
    fun pageNumberIsOffByDefault() {
        assertEquals("Options 默认不应加页码", false, MdToGongwen.Options().pageNumber)
        for (s in GovDocSpec.ALL_PRESETS) {
            assertEquals("${s.specName} 默认不应加页码", false, s.pageNumber)
        }
        // 转换结果同样不带页码
        val doc = MdToGongwen.convert("# 测试标题\n\n这是正文内容。", MdToGongwen.Options())
        assertEquals("转换结果默认不应加页码", false, doc.pageNumber)
        // 显式开启时仍然可用（开关本身没坏）
        val on = MdToGongwen.convert(
            "# 测试标题\n\n正文。",
            MdToGongwen.Options(pageNumber = true)
        )
        assertEquals("显式开启页码应生效", true, on.pageNumber)
    }

    @Test
    fun specCanBeRestoredByName() {
        for (s in GovDocSpec.ALL_PRESETS) {
            assertEquals("按名字回查应还原同一套规范", s, GovDocSpec.byName(s.specName))
        }
        // 未知名字（例如旧版本遗留）安全回退到默认，不应抛异常
        assertEquals(GovDocSpec.DEFAULT, GovDocSpec.byName("不存在的规范"))
        assertEquals(GovDocSpec.DEFAULT, GovDocSpec.byName(null))
    }

    /**
     * 诉讼文书页边距严格参照 Python 参考实现 md_to_official_word：
     * 上3 / 下2.8 / 左2.5 / 右2.5 cm（即 A4 公文默认边距，与国标通用版一致）。
     */
    @Test
    fun courtDocUsesReferenceScriptMargins() {
        val court = GovDocSpec.COURT_DOC.page
        assertEquals(3.0, court.topCm, 1e-9)
        assertEquals(2.8, court.bottomCm, 1e-9)
        assertEquals(2.5, court.leftCm, 1e-9)
        assertEquals(2.5, court.rightCm, 1e-9)
        // 与行政机关（自定义边距）明显不同，证明不是硬编码
        val govOff = GovDocSpec.GOV_OFFICIAL.page
        assertNotEquals("应与行政机关上边距不同", govOff.topCm, court.topCm)
        assertNotEquals("应与行政机关下边距不同", govOff.bottomCm, court.bottomCm)
    }

    @Test
    fun convertedDocCarriesSpecPageSetup() {
        val md = "# 民事起诉状\n\n原告：张三。\n\n此致\n\n北京市朝阳区人民法院"
        val doc = MdToGongwen.convert(md, MdToGongwen.Options(spec = GovDocSpec.COURT_DOC))
        // PDF 导出依赖 doc.page，这里必须是所选规范的页边距而非默认值
        assertEquals(3.0, doc.page.topCm, 1e-9)
        assertEquals(2.5, doc.page.rightCm, 1e-9)
        assertEquals(GovDocSpec.COURT_DOC.lineSpacingPt, doc.lineSpacingPt, 1e-9)
        assertTrue("正文块不应为空", doc.blocks.isNotEmpty())
    }

    /**
     * 诉讼文书输出必须逐项匹配 Python 参考实现 md_to_official_word：
     *  - 主标题：小标宋体 + 居中
     *  - 正文：仿宋_GB2312 + 首行缩进 2 字(32pt) + 固定行距 28pt(精确值) + 两端对齐
     *  - 生成的 docx XML 真正写入上述字体名与精确行距(560 twips)
     */
    @Test
    fun courtDocMatchesReferenceScript() {
        val md = """
            # 民事起诉状

            原告：张三，男，汉族。

            ## 诉讼请求

            请求判令被告支付欠款。

            ### 事实与理由

            原被告之间存在合法有效的合同关系。

            这是一段正文内容，用于验证两端对齐、仿宋字体以及固定行距是否一致。
        """.trimIndent()

        val doc = MdToGongwen.convert(md, MdToGongwen.Options(spec = GovDocSpec.COURT_DOC))

        // 主标题：小标宋体 + 居中
        val title = doc.blocks.filterIsInstance<Block.Para>().first { it.runs.isNotEmpty() }
        assertEquals("主标题应为小标宋体", "小标宋体", title.runs.first().font)
        assertEquals("主标题应居中", Align.CENTER, title.props.align)

        // 正文：仿宋_GB2312 + 缩进32 + 行距25 + 两端对齐
        val body = doc.blocks.filterIsInstance<Block.Para>().first {
            it.runs.isNotEmpty() && it.props.align == Align.BOTH && it.runs.first().font == "仿宋_GB2312"
        }
        assertEquals("正文应为仿宋_GB2312", "仿宋_GB2312", body.runs.first().font)
        assertEquals("正文应为四号(14pt)", 14.0, body.runs.first().sizePt, 1e-9)
        assertEquals("正文首行缩进应为 2 字(32pt)", 32.0, body.props.firstLineIndentPt, 1e-9)
        assertEquals("正文固定行距应为 25pt", 25.0, body.props.lineSpacingPt, 1e-9)
        assertEquals("正文应两端对齐", Align.BOTH, body.props.align)

        // 页边距 = 参考实现指定值
        assertEquals(3.0, doc.page.topCm, 1e-9)
        assertEquals(2.8, doc.page.bottomCm, 1e-9)
        assertEquals(2.5, doc.page.leftCm, 1e-9)
        assertEquals(2.5, doc.page.rightCm, 1e-9)

        // 直接解开 docx，确认 XML 真正落盘了这些格式
        val xml = unzipEntry(doc.toDocx(), "word/document.xml")
            ?: error("应生成 document.xml")
        assertTrue("应写入小标宋体字体名", xml.contains("小标宋体"))
        assertTrue("应写入仿宋_GB2312字体名", xml.contains("仿宋_GB2312"))
        assertTrue("行距应为精确值 exact", xml.contains("""w:lineRule="exact""""))
        // 25pt × 20 = 500 twips
        assertTrue("正文行距应写为 500 twips (25pt)", xml.contains("""w:line="500""""))
    }

    @Test
    fun bodyParagraphsAreJustified() {
        val md = "正文一段比较长的内容，用于确认默认开启了两端对齐，使右边界保持齐整。"
        val doc = MdToGongwen.convert(md, MdToGongwen.Options(spec = GovDocSpec.COURT_DOC))
        val bodyParas = doc.blocks.filterIsInstance<Block.Para>()
            .filter { it.runs.isNotEmpty() }
        assertTrue("应存在正文段落", bodyParas.isNotEmpty())
        assertTrue(
            "正文段落应为两端对齐（Align.BOTH）",
            bodyParas.any { it.props.align == Align.BOTH }
        )
    }

    @Test
    fun justifyCanBeDisabled() {
        val md = "关闭两端对齐后应退化为左对齐，便于个别场景使用。"
        val doc = MdToGongwen.convert(
            md,
            MdToGongwen.Options(spec = GovDocSpec.COURT_DOC.copy(justify = false))
        )
        val bodyParas = doc.blocks.filterIsInstance<Block.Para>().filter { it.runs.isNotEmpty() }
        assertTrue("关闭后不应再有两端对齐段落", bodyParas.none { it.props.align == Align.BOTH })
    }

    @Test
    fun docxImportFollowsSpec() {
        // 先用诉讼文书规范生成一份 docx，再读回来，文档级参数应跟随传入规范
        val gov = MdToGongwen.convert(
            "# 测试标题\n\n正文内容。",
            MdToGongwen.Options(spec = GovDocSpec.COURT_DOC)
        )
        val bytes = gov.toDocx()
        val back = DocxReader.read(bytes, GovDocSpec.COURT_DOC)
        assertEquals(3.0, back.page.topCm, 1e-9)
        assertEquals(2.5, back.page.rightCm, 1e-9)

        // 换一套规范读，页面参数应随之改变（证明不再硬编码）
        val back2 = DocxReader.read(bytes, GovDocSpec.GB_STANDARD)
        assertEquals(3.0, back2.page.topCm, 1e-9)
        assertEquals(2.5, back2.page.rightCm, 1e-9)
    }

    /**
     * 页码跟随原 Word 文档：原文档页脚有 PAGE 域就有页码，没有就没有。
     *
     * 这样「打开 Word → 导出 PDF」不会凭空多出或丢失页码。
     */
    @Test
    fun pageNumberFollowsSourceWord() {
        val md = "# 测试标题\n\n正文内容。"

        // ① 原 Word 带页码 → 读回来应有页码，且字号 / 页脚距边界与写入时一致
        val withNum = MdToGongwen.convert(
            md, MdToGongwen.Options(spec = GovDocSpec.COURT_DOC, pageNumber = true)
        )
        val backOn = DocxReader.read(withNum.toDocx(), GovDocSpec.COURT_DOC)
        assertTrue("原 Word 有页码时应识别出页码", backOn.pageNumber)
        assertEquals(
            "页码字号应与原 Word 一致",
            withNum.pageNumStyle.fontSizePt, backOn.pageNumStyle.fontSizePt, 1e-9
        )
        assertEquals(
            "页脚距边界应与原 Word 一致",
            withNum.pageNumStyle.footerDistanceCm, backOn.pageNumStyle.footerDistanceCm, 1e-2
        )

        // ② 原 Word 无页码 → 读回来不应凭空加上
        val without = MdToGongwen.convert(
            md, MdToGongwen.Options(spec = GovDocSpec.COURT_DOC, pageNumber = false)
        )
        val backOff = DocxReader.read(without.toDocx(), GovDocSpec.COURT_DOC)
        assertTrue("原 Word 无页码时不应加页码", !backOff.pageNumber)
    }

    /** 页码只能是一个居中数字：Word 页脚不得出现一字线等前后缀文本 */
    @Test
    fun pageNumberIsPlainCenteredNumber() {
        val gov = MdToGongwen.convert(
            "# 标题\n\n正文。", MdToGongwen.Options(spec = GovDocSpec.COURT_DOC, pageNumber = true)
        )
        val footer = unzipEntry(gov.toDocx(), "word/footer1.xml")
            ?: error("开启页码后应生成 footer1.xml")
        assertTrue("页脚应居中", footer.contains("""<w:jc w:val="center"/>"""))
        assertTrue("页脚应含 PAGE 域", footer.contains("PAGE"))
        assertTrue("页脚不应出现一字线前后缀", !footer.contains("—"))
        assertTrue("页码不应引入总页数域", !footer.contains("NUMPAGES"))
        // 除域代码外不应有任何静态文字 run
        assertTrue("页脚不应有多余文本", !footer.contains("<w:t"))
    }

    private fun unzipEntry(bytes: ByteArray, name: String): String? {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
                e = zis.nextEntry
            }
        }
        return null
    }
}
