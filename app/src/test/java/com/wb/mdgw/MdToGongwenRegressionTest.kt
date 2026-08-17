package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公文转换回归测试（golden 结构）。
 *
 * 输入与仓库 `示例/示例公文.md` 内容一致，覆盖典型公文要素：
 * 标题、顶格称呼语、多级标题、正文缩进、粗体行内样式、表格、列表、落款。
 * 任何排版规则改动若破坏这些稳定结构，都会在此暴露。
 */
class MdToGongwenRegressionTest {

    // 与仓库「示例/示例公文.md」保持一致的样例（内联以便 CI 无需依赖文件路径）
    private val SAMPLE = """
        # 关于加强办公自动化建设的通知

        ll 各处室、各直属单位：

        为进一步提升机关办公效率，规范公文处理流程，现就加强办公自动化建设有关事项通知如下。

        ## 一、总体要求

        坚持"统筹规划、分步实施"的原则，围绕**提质增效**这一核心目标，推动办公流程数字化转型。

        ### （一）明确责任分工

        各单位主要负责同志要亲自抓，明确专人负责，确保各项任务落到实处。

        #### 1. 建立工作台账

        对照任务清单，逐项建立工作台账，实行销号管理。

        ### （二）强化技术保障

        加强信息系统运维保障，确保系统安全稳定运行。

        ## 二、重点任务

        各单位应按照下表要求，明确时间节点，倒排工期。

        | 序号 | 任务内容 | 责任单位 | 完成时限 |
        | --- | --- | --- | --- |
        | 1 | 公文格式标准化改造 | 办公室 | 3月底前 |
        | 2 | 系统权限梳理 | 信息中心 | 4月底前 |
        | 3 | 人员业务培训 | 人事处 | 5月底前 |

        ## 三、工作要求

        - 提高思想认识，切实增强工作的主动性
        - 加强协调配合，形成齐抓共管的工作合力
        - 严格督查考核，确保各项任务按期完成

        各单位在执行中遇到的问题，请及时反馈。

        ---

        某某单位办公室

        2026年8月8日
    """.trimIndent()

    private val options = MdToGongwen.Options(spec = GovDocSpec.GB_STANDARD)

    @Test
    fun `标题置中并采用主标题字体字号`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        assertEquals("关于加强办公自动化建设的通知", doc.title)

        val title = doc.blocks[0] as Block.Para
        assertEquals(doc.title, title.runs.joinToString("") { it.text })
        assertEquals(Align.CENTER, title.props.align)
        assertEquals(0.0, title.props.firstLineIndentPt, 0.001)
        assertEquals(GovDocSpec.GB_STANDARD.mainTitleFont, title.runs[0].font)
        assertEquals(GovDocSpec.GB_STANDARD.mainTitleSizePt, title.runs[0].sizePt, 0.001)
    }

    @Test
    fun `顶格称呼语无首行缩进`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val salutation = doc.blocks
            .filterIsInstance<Block.Para>()
            .first { p -> p.runs.joinToString("") { it.text } == "各处室、各直属单位：" }
        assertEquals(0.0, salutation.props.firstLineIndentPt, 0.001)
        assertEquals(Align.LEFT, salutation.props.align)
    }

    @Test
    fun `正文两端对齐且二字符缩进`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val body = doc.blocks
            .filterIsInstance<Block.Para>()
            .first { p -> p.runs.joinToString("") { it.text }.startsWith("为进一步提升机关办公效率") }
        assertEquals(Align.BOTH, body.props.align)
        assertEquals(options.indentPt, body.props.firstLineIndentPt, 0.001)
    }

    @Test
    fun `粗体行内样式被拆分为独立 run`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val bold = doc.blocks
            .flatMap { b -> if (b is Block.Para) b.runs else emptyList() }
            .first { it.bold }
        assertEquals("提质增效", bold.text)
    }

    @Test
    fun `表格结构完整且内容保真`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val table = doc.blocks.filterIsInstance<Block.Table>().single()
        assertEquals(4, table.rows.size)
        table.rows.forEach { assertEquals(4, it.size) }

        val header = table.rows[0].map { it.joinToString("") { r -> r.text } }
        assertEquals(listOf("序号", "任务内容", "责任单位", "完成时限"), header)
        assertEquals("公文格式标准化改造", table.rows[1][1].joinToString("") { it.text })
    }

    @Test
    fun `反向 Markdown 保留标题与表格`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val md = doc.toMarkdown()
        assertTrue(md.contains("关于加强办公自动化建设的通知"))
        assertTrue(md.contains("提质增效"))
        assertTrue(md.contains("序号"))
        assertTrue(md.contains("公文格式标准化改造"))
    }

    @Test
    fun `生成的 docx 非空`() {
        val doc = MdToGongwen.convert(SAMPLE, options)
        val bytes = doc.toDocx()
        assertTrue(bytes.isNotEmpty())
        // OOXML 是 zip 包，魔数为 PK
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
    }
}