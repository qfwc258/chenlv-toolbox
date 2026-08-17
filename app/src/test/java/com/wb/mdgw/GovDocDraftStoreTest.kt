package com.wb.mdgw

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * 守护 [GovDoc] 的 kotlinx.serialization 装配：任何漏标 @Serializable 的字段 /
 * 不可序列化的类型都会在往返测试中暴露，避免「草稿保存后恢复为空」这类静默回归。
 */
class GovDocDraftStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun `公文草稿可序列化往返且字段保真`() {
        val gov = GovDoc(
            blocks = listOf(
                Block.Para(
                    runs = listOf(
                        TextRun("陈  律", "小标宋", 22.0, bold = true),
                        TextRun("（盖章）", "仿宋", 16.0, underline = true)
                    ),
                    props = ParaProps(align = Align.CENTER, firstLineIndentPt = 28.0)
                ),
                Block.Table(
                    rows = listOf(
                        listOf(listOf(TextRun("姓名", "黑体", 12.0))),
                        listOf(listOf(TextRun("张三", "仿宋", 12.0, underline = true)))
                    )
                )
            ),
            page = PageSetup(),
            title = "民事判决书",
            mainTitleFont = "小标宋",
            bodyFont = "仿宋",
            bodySizePt = 16.0,
            lineSpacingPt = 28.0,
            indentPt = 32.0,
            pageNumber = true,
            pageNumStyle = PageNumStyle(fontSizePt = 14.0),
            originalDocx = byteArrayOf(1, 2, 3), // @Transient：往返后应为 null
            edits = setOf(EditTarget(0, runIndex = 1), EditTarget(1, row = 1, col = 0, runIndex = 0))
        )

        val txt = json.encodeToString(GovDoc.serializer(), gov)
        val back = json.decodeFromString<GovDoc>(txt)

        // 基础属性
        assertEquals(gov.title, back.title)
        assertEquals(gov.bodyFont, back.bodyFont)
        assertEquals(gov.bodySizePt, back.bodySizePt, 0.0001)
        assertEquals(gov.pageNumber, back.pageNumber)
        assertEquals(gov.indentPt, back.indentPt, 0.0001)

        // 段落 / run 样式（含下划线、粗体）
        val para = back.blocks[0] as Block.Para
        assertEquals(2, para.runs.size)
        assertTrue(para.runs[0].bold)
        assertTrue(para.runs[1].underline)
        assertEquals(Align.CENTER, para.props.align)

        // 表格
        val table = back.blocks[1] as Block.Table
        assertEquals(2, table.rows.size)
        assertEquals("张三", table.rows[1][0][0].text)
        assertTrue(table.rows[1][0][0].underline)

        // 页码样式
        assertEquals(14.0, back.pageNumStyle.fontSizePt, 0.0001)

        // 编辑位置集合
        assertEquals(gov.edits, back.edits)

        // @Transient 的源字节不进入草稿，恢复时为 null（草稿走「新建导出」路径）
        assertNull(back.originalDocx)
    }

    @Test
    fun `空公文也可序列化`() {
        val gov = GovDoc(
            blocks = emptyList(),
            page = PageSetup(),
            title = "",
            mainTitleFont = "小标宋",
            bodyFont = "仿宋",
            bodySizePt = 16.0,
            lineSpacingPt = 28.0,
            indentPt = 32.0
        )
        val txt = json.encodeToString(GovDoc.serializer(), gov)
        val back = json.decodeFromString<GovDoc>(txt)
        assertTrue(back.blocks.isEmpty())
        assertEquals("", back.title)
    }
}
