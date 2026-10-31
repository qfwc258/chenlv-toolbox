package com.wb.mdgw

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 MdToGongwen 对 `---` 的处理：
 * - `---` 按正常 Markdown 水平线解读（渲染为空行），不再触发落款区右对齐
 * - `rr ` 前缀用于落款块：各行左对齐并共用同一左缩进，第一行距右边 4 字，后续行与第一行左齐
 * - 独立行 `rrr` 之后全部为落款，版式与 `rr` 相同；落款前空一行
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
     * `rr ` 前缀应触发落款：左对齐、共用左缩进。
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
        assertEquals("rr 前缀应左对齐", Align.LEFT, applicant!!.second)

        val date = paras.find { it.first.contains("二〇二六") }
        assertNotNull("应存在日期段落", date)
        assertEquals("rr 前缀应左对齐", Align.LEFT, date!!.second)
    }

    /**
     * 连续 rr 行成一块：各行左对齐并共用同一左缩进，后续行与第一行左齐。
     */
    @Test
    fun `consecutive rr lines share first line left edge`() {
        val md = """
            rr 答辩人：长沙青旅金洲湖物业服务有限公司
            rr 2026年10月29日
        """.trimIndent()
        val doc = convert(md)
        val paras = doc.blocks.filterIsInstance<Block.Para>().filter { it.runs.isNotEmpty() }
        assertEquals(2, paras.size)
        val first = paras[0]
        val second = paras[1]
        assertEquals(Align.LEFT, first.props.align)
        assertEquals(Align.LEFT, second.props.align)
        assertTrue("落款应带左缩进", first.props.leftIndentChars > 0)
        assertEquals("各行共用同一左缩进", first.props.leftIndentChars, second.props.leftIndentChars)
        assertTrue(first.props.sign)
        assertTrue(second.props.sign)

        val xml = unzipEntry(doc.toDocx(), "word/document.xml")!!
        assertTrue("Word XML 应带字符单位的左缩进", xml.contains("w:leftChars=\"${first.props.leftIndentChars}\""))
        assertTrue("且仍为左对齐", xml.contains("<w:jc w:val=\"left\"/>"))
        assertFalse("落款不再写逐行不同的右缩进", xml.contains("w:rightChars"))
    }

    @Test
    fun `visual width counts ascii as half hanzi`() {
        assertEquals(50, MdToGongwen.visualWidthHundredths("A"))
        assertEquals(100, MdToGongwen.visualWidthHundredths("中"))
        assertEquals(150, MdToGongwen.visualWidthHundredths("中A"))
        assertEquals(200, MdToGongwen.visualWidthHundredths("2026".take(4)))
        assertEquals(700, MdToGongwen.visualWidthHundredths("2026年10月29日"))
    }

    /** 独立行 rrr 之后全部为落款，版式与 rr 块相同，落款前空一行。 */
    @Test
    fun `rrr marker treats remaining lines as sign block with blank before`() {
        val md = """
            正文一段。

            rrr
            答辩人：长沙青旅金洲湖物业服务有限公司
            2026年10月29日
        """.trimIndent()
        val doc = convert(md)
        val paras = doc.blocks.filterIsInstance<Block.Para>()
        val body = paras.first { it.runs.joinToString("") { r -> r.text }.contains("正文一段") }
        val blankIdx = paras.indexOf(body) + 1
        assertTrue("落款前应空一行", paras[blankIdx].runs.all { it.text.isBlank() })
        val sign = paras.filter { it.props.sign }
        assertEquals(2, sign.size)
        assertEquals("答辩人：长沙青旅金洲湖物业服务有限公司", sign[0].runs.joinToString("") { it.text })
        assertEquals(Align.LEFT, sign[0].props.align)
        assertEquals("各行左缩进相同", sign[0].props.leftIndentChars, sign[1].props.leftIndentChars)
        assertTrue(sign[0].props.leftIndentChars > 0)
        assertFalse("rrr 标记本身不应出现", paras.any { it.runs.joinToString("") { r -> r.text }.trim() == "rrr" })
    }

    @Test
    fun `rr block also inserts blank line before signature`() {
        val md = """
            正文一段。
            rr 答辩人：长沙青旅金洲湖物业服务有限公司
            rr 2026年10月29日
        """.trimIndent()
        val paras = convert(md).blocks.filterIsInstance<Block.Para>()
        val bodyIdx = paras.indexOfFirst { it.runs.joinToString("") { r -> r.text }.contains("正文一段") }
        assertTrue(paras[bodyIdx + 1].runs.all { it.text.isBlank() })
        assertEquals(Align.LEFT, paras[bodyIdx + 2].props.align)
        assertTrue(paras[bodyIdx + 2].props.sign)
    }

    /** 后续行比第一行更长时，仍与第一行共用同一左缩进。 */
    @Test
    fun `sign block longer second line does not indent less than first`() {
        val doc = convert("rr 日期\nrr 这是一行比第一行更长的落款单位名称")
        val paras = doc.blocks.filterIsInstance<Block.Para>().filter { it.runs.isNotEmpty() }
        assertEquals(paras[0].props.leftIndentChars, paras[1].props.leftIndentChars)
        assertTrue(paras[0].props.sign)
        assertTrue(paras[1].props.sign)
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
     * 落款行默认距右边界 4 个汉字：用共用左缩进实现，必须真进 Word 的 XML（字符单位，随字号联动）。
     */
    @Test
    fun `rr prefix indents four hanzi from right edge`() {
        val doc = convert("rr 申请人：张三")
        val p = doc.blocks.filterIsInstance<Block.Para>().first()
        val usableChars = kotlin.math.round((21.0 - 2.5 - 2.5) * 72.0 / 2.54 / 14.0 * 100).toInt()
        val firstW = MdToGongwen.visualWidthHundredths("申请人：张三")
        assertEquals("左缩进 = 版心 − 第一行宽 − 右边 4 字", usableChars - firstW - 400, p.props.leftIndentChars)
        assertTrue(p.props.sign)

        val xml = unzipEntry(doc.toDocx(), "word/document.xml")!!
        assertTrue("Word XML 应带字符单位的左缩进", xml.contains("w:leftChars=\"${p.props.leftIndentChars}\""))
        assertTrue("且仍为左对齐", xml.contains("<w:jc w:val=\"left\"/>"))
    }

    /** 设置项要能贯通：距右边字数越大，左缩进越小。 */
    @Test
    fun `sign right indent follows options`() {
        val flush = MdToGongwen.convert("rr 张三", MdToGongwen.Options(signRightChars = 0))
        val wide = MdToGongwen.convert("rr 张三", MdToGongwen.Options(signRightChars = 6))
        val flushLeft = (flush.blocks.filterIsInstance<Block.Para>().first { it.runs.isNotEmpty() }).props.leftIndentChars
        val wideLeft = (wide.blocks.filterIsInstance<Block.Para>().first { it.runs.isNotEmpty() }).props.leftIndentChars
        assertTrue("贴边时左缩进应更大", flushLeft > wideLeft)
        assertEquals("相差 6 个汉字", 600, flushLeft - wideLeft)

        val xmlFlush = unzipEntry(flush.toDocx(), "word/document.xml")!!
        val xmlWide = unzipEntry(wide.toDocx(), "word/document.xml")!!
        assertTrue(xmlFlush.contains("w:leftChars=\"$flushLeft\""))
        assertTrue(xmlWide.contains("w:leftChars=\"$wideLeft\""))
    }

    /** 不能只改 Word：预览 HTML 要有等价左缩进、反导 Markdown 要还原前缀、重开 docx 不能丢。 */
    @Test
    fun `preview html and roundtrip reflect rr indent`() {
        val doc = convert("rr 申请人：张三")
        val left = doc.blocks.filterIsInstance<Block.Para>().first { it.runs.isNotEmpty() }.props.leftIndentChars
        assertTrue("预览应按段落字号换算出左缩进",
            DocxHtml.govDocToHtml(doc).contains("padding-left:"))
        assertTrue("反导 Markdown 应还原 rr 前缀",
            doc.toMarkdown().lineSequence().any { it.startsWith("rr ") })

        val back = DocxReader.read(doc.toDocx(), GovDocSpec.DEFAULT)
        val rp = back.blocks.filterIsInstance<Block.Para>().first { it.runs.isNotEmpty() }
        assertEquals("重新打开导出的 docx 不应丢左缩进", left, rp.props.leftIndentChars)
        assertTrue(rp.props.sign)
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
