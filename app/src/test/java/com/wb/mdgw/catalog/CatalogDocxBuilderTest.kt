package com.wb.mdgw.catalog

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDocxBuilderTest {

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { m[e.name] = zis.readBytes(); e = zis.nextEntry }
        }
        return m
    }

    private fun docXml(bytes: ByteArray): String {
        val parts = unzip(bytes)
        assertTrue("必须包含 word/document.xml", parts.containsKey("word/document.xml"))
        assertTrue("必须包含 word/styles.xml", parts.containsKey("word/styles.xml"))
        return String(parts["word/document.xml"]!!, StandardCharsets.UTF_8)
    }

    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun gridColWidths(xml: String): List<Int> {
        val regex = Regex("""<w:gridCol w:w="(\d+)"/>""")
        return regex.findAll(xml).map { it.groupValues[1].toInt() }.toList()
    }

    @Test
    fun evidence_hasCorrectStructureRowsAndFooter() {
        val items = listOf(
            EvidenceItem(
                name = "劳动合同",
                pages = "1",
                purpose = "证明双方存在劳动关系",
                source = "原告提供"
            ),
            EvidenceItem(
                name = "工资银行流水",
                pages = "2-5",
                purpose = "证明工资标准与发放情况",
                source = "法院调取"
            )
        )
        val form = EvidenceForm(
            title = "证 据 目 录",
            submitter = "车再彬",
            date = "2026 年 9 月 15 日",
            minRows = 10,
            items = items
        )
        val xml = docXml(CatalogDocxBuilder.evidence(form))

        // 5 列
        val widths = gridColWidths(xml)
        assertEquals("证据目录应有 5 列", 5, widths.size)

        // 列宽总和 = A4 可用宽度 16.6cm
        val usable = Math.round(16.6 * 566.929).toInt()
        assertEquals("列宽之和应等于可用宽度", usable, widths.sum())

        // 行数 = 表头 1 + 数据 10（补空行到 10）
        assertEquals("应含表头+10 行", 11, count(xml, "<w:tr>"))

        // 表头重复标记
        assertTrue("表头应标记 tblHeader", xml.contains("<w:tblHeader/>"))

        // 编号 1..10 连续（空行也编号）
        for (n in 1..10) {
            assertTrue("应包含编号 $n", xml.contains("<w:t xml:space=\"preserve\">$n</w:t>"))
        }

        // 内容
        assertTrue(xml.contains("劳动合同"))
        assertTrue(xml.contains("工资银行流水"))
        assertTrue(xml.contains("证明双方存在劳动关系"))
        assertTrue(xml.contains("法院调取"))
        assertTrue(xml.contains("2-5"))

        // 落款
        assertTrue(xml.contains("提交人：车再彬"))
        assertTrue(xml.contains("2026 年 9 月 15 日"))

        // fixed 布局 + 六边框
        assertTrue(xml.contains("w:type=\"fixed\""))
        assertTrue(xml.contains("<w:insideV"))
    }

    @Test
    fun evidence_moreItemsThanMinRows_noPadding() {
        val items = List(12) { i ->
            EvidenceItem(name = "证据$i", pages = "1", purpose = "目的$i", source = "来源")
        }
        val xml = docXml(CatalogDocxBuilder.evidence(
            EvidenceForm(submitter = "张三", date = "2026 年 1 月 1 日", minRows = 10, items = items)
        ))
        // 表头 + 12 行
        assertEquals("证据多于最小行数时按实际条数", 13, count(xml, "<w:tr>"))
        for (n in 1..12) {
            assertTrue(xml.contains("<w:t xml:space=\"preserve\">$n</w:t>"))
        }
    }

    @Test
    fun archive_has21FixedItemsAndPages() {
        val items = CatalogTemplates.newArchiveItems().toMutableList()
        items[0] = items[0].copy(page = "1")
        items[4] = items[4].copy(page = "12-15")

        val xml = docXml(CatalogDocxBuilder.archive(
            ArchiveForm(title = "宁乡市法律援助案卷归档目录", items = items)
        ))

        // 3 列
        val widths = gridColWidths(xml)
        assertEquals("归档目录应有 3 列", 3, widths.size)

        // 行数 = 表头 1 + 21 项
        assertEquals("应含表头+21 项", 22, count(xml, "<w:tr>"))

        // 序号 1..21
        for (n in 1..21) {
            assertTrue("应含序号 $n", xml.contains("<w:t xml:space=\"preserve\">$n</w:t>"))
        }

        // 项目名与页码
        assertTrue(xml.contains(CatalogTemplates.ARCHIVE_ITEMS[0]))
        assertTrue(xml.contains(CatalogTemplates.ARCHIVE_ITEMS[20]))
        assertTrue(xml.contains("12-15"))

        // 无落款段落关键字（归档目录无提交人）
        assertTrue(!xml.contains("提交人："))
    }

    @Test
    fun archive_templateCountIs21() {
        assertEquals("归档目录固定 21 项", 21, CatalogTemplates.ARCHIVE_ITEMS.size)
        assertEquals(21, CatalogTemplates.newArchiveItems().size)
    }
}
