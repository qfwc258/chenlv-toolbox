package com.wb.mdgw.shot

import com.wb.mdgw.Align
import com.wb.mdgw.DocxWriter
import com.wb.mdgw.ParaProps
import com.wb.mdgw.PageSetup
import com.wb.mdgw.TextRun
import com.wb.mdgw.inchToEmu
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DocxWriter 图片扩展的正确性与零回归测试。
 *
 * 零回归基准：无图片文档的 zip 部件集合、document.xml 内容必须与扩展前完全一致
 * （不出现 drawing 命名空间、不出现 media 部件、rels/Content_Types 无图片条目）。
 */
class ShotDocxImageTest {

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { m[e.name] = zis.readBytes(); e = zis.nextEntry }
        }
        return m
    }

    private fun String.utf8() = toByteArray(StandardCharsets.UTF_8)

    /** 生成一个 3 段图片 + 2 列表格的截图式文档（3 段 → 2 行，末格为空） */
    private fun buildImageDoc(): Pair<Map<String, ByteArray>, Array<ByteArray>> {
        val img1 = byteArrayOf(1, 2, 3, 4)
        val img2 = byteArrayOf(5, 6, 7, 8)
        val img3 = byteArrayOf(9, 10, 11, 12)
        val all = arrayOf(img1, img2, img3)
        val w = inchToEmu(2.99)
        val h = inchToEmu(2.99 * 3.6)
        val writer = DocxWriter(
            page = PageSetup(topCm = 1.0, bottomCm = 1.0, leftCm = 1.0, rightCm = 1.0),
            gridless = true
        ).apply {
            val s1 = addImage(img1, "jpeg")
            val s2 = addImage(img2, "png")
            val s3 = addImage(img3, "jpeg")
            addImageTable(2, listOf(
                listOf(
                    DocxWriter.ImageCell(s1, w, h),
                    DocxWriter.ImageCell(s2, w, h)
                ),
                listOf(
                    DocxWriter.ImageCell(s3, w, inchToEmu(2.99 * 0.5)), // 末段矮段，独立高度
                    DocxWriter.ImageCell(null, w, h)                    // 空格子
                )
            ))
        }
        return unzip(writer.build("长截图文档")) to all
    }

    @Test
    fun imagePartsPresentInZip() {
        val (parts, imgs) = buildImageDoc()
        assertNotNull(parts["word/media/image1.jpeg"])
        assertNotNull(parts["word/media/image2.png"])
        assertNotNull(parts["word/media/image3.jpeg"])
        assertArrayEquals(imgs[0], parts["word/media/image1.jpeg"])
        assertArrayEquals(imgs[1], parts["word/media/image2.png"])
        assertArrayEquals(imgs[2], parts["word/media/image3.jpeg"])
    }

    @Test
    fun contentTypesHasOnlyUsedExtensions() {
        val (parts, _) = buildImageDoc()
        val ct = String(parts["[Content_Types].xml"]!!, StandardCharsets.UTF_8)
        assertTrue(ct.contains("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"))
        assertTrue(ct.contains("<Default Extension=\"png\" ContentType=\"image/png\"/>"))
        // 原有声明仍在
        assertTrue(ct.contains("<Default Extension=\"rels\""))
        assertTrue(ct.contains("/word/document.xml"))
    }

    @Test
    fun docRelsHasImageRelationships() {
        val (parts, _) = buildImageDoc()
        val rels = String(parts["word/_rels/document.xml.rels"]!!, StandardCharsets.UTF_8)
        assertTrue(rels.contains("Id=\"rId1\""))
        assertTrue(rels.contains("Id=\"rIdImg1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image1.jpeg\""))
        assertTrue(rels.contains("Target=\"media/image2.png\""))
        assertTrue(rels.contains("Target=\"media/image3.jpeg\""))
    }

    @Test
    fun documentXmlHasDrawingAndNamespaces() {
        val (parts, _) = buildImageDoc()
        val doc = String(parts["word/document.xml"]!!, StandardCharsets.UTF_8)
        // 根元素按需追加的三个 drawing 命名空间
        assertTrue(doc.contains("xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""))
        assertTrue(doc.contains("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""))
        assertTrue(doc.contains("xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""))
        // 图片引用
        assertTrue(doc.contains("r:embed=\"rIdImg1\""))
        assertTrue(doc.contains("r:embed=\"rIdImg3\""))
        // EMU 精确值
        assertTrue(doc.contains("<wp:extent cx=\"${inchToEmu(2.99)}\" cy=\"${inchToEmu(2.99 * 3.6)}\"/>"))
        // 图片段落行距 auto（防 exact 行距裁剪）与零缩进
        assertTrue(doc.contains("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>"))
        assertTrue(doc.contains("<w:ind w:firstLine=\"0\"/>"))
        // 无边框表格 + 零单元格边距
        assertTrue(doc.contains("<w:top w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\"/>"))
        assertTrue(doc.contains("<w:tcMar>"))
        // gridless：sectPr 的 docGrid 不带 type="lines"
        assertTrue(doc.contains("<w:docGrid w:linePitch=\"312\"/>"))
        assertFalse(doc.contains("<w:docGrid w:type=\"lines\""))
        // 空格子有空段落、末段矮段用独立高度
        assertTrue(doc.contains("<wp:extent cx=\"${inchToEmu(2.99)}\" cy=\"${inchToEmu(2.99 * 0.5)}\"/>"))
    }

    @Test
    fun emuConversion() {
        assertEquals(914400L, inchToEmu(1.0))
        assertEquals(2732867L, inchToEmu(2.9887)) // (2.9887 × 914400) = 2732867.28 四舍五入
        assertEquals(0L, inchToEmu(0.0))
    }

    // ---------- 零回归 ----------

    @Test
    fun plainDocumentUnchanged() {
        val writer = DocxWriter(PageSetup(), "仿宋_GB2312", 16.0).apply {
            addParagraph(
                listOf(TextRun("普通正文", "仿宋_GB2312", 16.0)),
                ParaProps(align = Align.BOTH)
            )
            addTable(listOf(listOf(listOf(TextRun("表格", "仿宋_GB2312", 16.0)))))
        }
        val parts = unzip(writer.build("回归文档"))
        // 部件集合与扩展前完全一致（8 件套，无页码）
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels",
                "docProps/core.xml", "docProps/app.xml",
                "word/_rels/document.xml.rels", "word/styles.xml", "word/document.xml"
            ),
            parts.keys
        )
        val doc = String(parts["word/document.xml"]!!, StandardCharsets.UTF_8)
        assertFalse("无图片文档不得出现 drawing 命名空间", doc.contains("xmlns:wp="))
        assertFalse(doc.contains("w:drawing"))
        assertFalse(doc.contains("w:docGrid w:linePitch=\"312\"/>")) // 非 gridless 保持 type="lines"
        val rels = String(parts["word/_rels/document.xml.rels"]!!, StandardCharsets.UTF_8)
        assertFalse(rels.contains("image"))
        val ct = String(parts["[Content_Types].xml"]!!, StandardCharsets.UTF_8)
        assertFalse(ct.contains("image/"))
    }

    @Test
    fun plainDocumentWithPageNumberUnchanged() {
        val writer = DocxWriter(PageSetup(), "仿宋_GB2312", 16.0, pageNumber = true)
        val parts = unzip(writer.build("回归文档2"))
        assertTrue(parts.containsKey("word/footer1.xml")) // 原有 7 部件 + 页脚 = 8 件套
        assertEquals(8, parts.size)
        assertFalse(String(parts["word/_rels/document.xml.rels"]!!, StandardCharsets.UTF_8).contains("rIdImg"))
    }

    @Test
    fun imageTableSkipsInvalidRefs() {
        // 引用越界序号的格子按空格处理，不崩溃、不产出悬空 r:embed
        val writer = DocxWriter().apply {
            addImage(byteArrayOf(1), "jpeg")
            addImageTable(2, listOf(listOf(
                DocxWriter.ImageCell(99, 100L, 100L), // 越界
                DocxWriter.ImageCell(1, 100L, 100L)
            )))
        }
        val doc = String(unzip(writer.build("边界"))["word/document.xml"]!!, StandardCharsets.UTF_8)
        assertTrue(doc.contains("r:embed=\"rIdImg1\""))
        assertFalse(doc.contains("r:embed=\"rIdImg99\""))
    }

    @Test
    fun emptyImageTableIgnored() {
        val writer = DocxWriter().apply {
            addImageTable(2, emptyList())
            addImageTable(0, listOf(listOf<DocxWriter.ImageCell?>()))
        }
        val parts = unzip(writer.build("空表"))
        val doc = String(parts["word/document.xml"]!!, StandardCharsets.UTF_8)
        assertFalse(doc.contains("w:drawing"))
        assertFalse(parts.containsKey("word/media/image1.jpeg"))
    }
}
