package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.PptExportEngine
import com.wb.mdgw.pptx.PptLayoutEngine
import com.wb.mdgw.pptx.PptThemes
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 验证导出 OOXML 不含段后距（spcAft）——已恢复到初始状态，所有文本框均无 <a:spcAft>。
 */
class ParagraphSpacingTest {

    private fun exportSlideXml(units: List<PptLayoutEngine.LaidOutUnit>): String {
        val slide = PptLayoutEngine.LaidOutSlide(units = units, cover = false, layout = SlideLayout.STANDARD)
        val baos = ByteArrayOutputStream()
        PptExportEngine.exportPptx(listOf(slide), PptThemes.fromTone(PptThemes.DEFAULT_TONE), baos)
        val zip = ZipInputStream(baos.toByteArray().inputStream())
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == "ppt/slides/slide1.xml") {
                return zip.bufferedReader().readText()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        error("slide1.xml not found")
    }

    @Test
    fun noSpcAftInAnySlide() {
        // 标题 + 正文混合页，确认整体不含 spcAft
        val title = PptLayoutEngine.LaidOutUnit(
            type = BlockType.H2, x = 40, y = 30, w = 640, h = 29, fontSize = 24,
            align = PptLayoutEngine.Align.LEFT, fragments = listOf(InlineFragment("二级标题"))
        )
        val body = PptLayoutEngine.LaidOutUnit(
            type = BlockType.PARAGRAPH, x = 40, y = 80, w = 640, h = 20, fontSize = 16,
            align = PptLayoutEngine.Align.LEFT, fragments = listOf(InlineFragment("正文内容"))
        )
        val xml = exportSlideXml(listOf(title, body))
        assertFalse("导出 XML 不应包含任何 <a:spcAft>", xml.contains("<a:spcAft>"))
    }

    @Test
    fun paragraphOnlyHasAlgn() {
        val body = PptLayoutEngine.LaidOutUnit(
            type = BlockType.PARAGRAPH, x = 40, y = 80, w = 640, h = 20, fontSize = 16,
            align = PptLayoutEngine.Align.LEFT, fragments = listOf(InlineFragment("正文内容一段"))
        )
        val xml = exportSlideXml(listOf(body))
        assertTrue("pPr 应仅含 algn 属性", xml.contains("""<a:pPr algn="l"/>""") || xml.contains("""<a:pPr algn="l"></a:pPr>"""))
        assertFalse("不应含 spcAft", xml.contains("<a:spcAft>"))
    }
}
