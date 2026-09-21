package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DocxTemplateFillerTest {

    private val font = "仿宋_GB2312"

    private fun writer() = DocxWriter(PageSetup(), font, 16.0)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { m[e.name] = zis.readBytes(); e = zis.nextEntry }
        }
        return m
    }

    private fun docXml(bytes: ByteArray): String =
        String(unzip(bytes)["word/document.xml"]!!, Charsets.UTF_8)

    private fun bodyOf(bytes: ByteArray): Element {
        val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(unzip(bytes)["word/document.xml"]!!))
        return dom.documentElement.kids().first { it.loc() == "body" }
    }

    private fun Element.loc() = if (tagName.contains(":")) tagName.substringAfter(":") else tagName
    private fun Element.kids() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun allText(el: Element): String {
        val sb = StringBuilder()
        fun walk(n: Element) {
            for (c in n.kids()) {
                when (c.loc()) {
                    "t" -> sb.append(c.textContent)
                    "br" -> sb.append('\n')
                    "tab" -> sb.append('\t')
                    else -> walk(c)
                }
            }
        }
        walk(el)
        return sb.toString()
    }

    private fun cellText(tc: Element): String {
        val sb = StringBuilder()
        fun walk(n: Element) {
            for (c in n.kids()) {
                if (c.loc() == "t") sb.append(c.textContent) else walk(c)
            }
        }
        walk(tc)
        return sb.toString()
    }

    /** 单 run 段落里的占位符被替换 */
    @Test
    fun replacesPlaceholderInSingleRun() {
        val w = writer()
        w.addParagraph(listOf(TextRun("委托人：weitr", font, 16.0)), ParaProps())
        val out = DocxTemplateFiller.fill(w.build("t"), mapOf("weitr" to "周启民"))
        assertEquals("委托人：周启民", allText(bodyOf(out)))
    }

    /** 占位符被 Word 拆到两个 run（含下划线 run），替换后全文正确、下划线 run 仍在 */
    @Test
    fun replacesPlaceholderAcrossRunsAndKeepsFormat() {
        val w = writer()
        w.addParagraph(
            listOf(
                TextRun("委托人wei", font, 16.0),
                TextRun("tr签字", font, 16.0, underline = true)
            ),
            ParaProps()
        )
        val src = w.build("t")
        val underlinesBefore = countUnderlineRuns(bodyOf(src))
        val out = DocxTemplateFiller.fill(src, mapOf("weitr" to "周启民"))

        assertEquals("委托人周启民签字", allText(bodyOf(out)))
        // 下划线 run 数量不丢失
        assertEquals(underlinesBefore, countUnderlineRuns(bodyOf(out)))
    }

    /** 表格单元格内的占位符也被替换 */
    @Test
    fun replacesPlaceholderInsideTable() {
        val w = writer()
        w.addTable(
            listOf(
                listOf(
                    listOf(TextRun("案由：anyou", font, 16.0)),
                    listOf(TextRun("阶段：jied", font, 16.0))
                )
            )
        )
        val out = DocxTemplateFiller.fill(
            w.build("t"),
            mapOf("anyou" to "劳务合同纠纷", "jied" to "一审")
        )
        val tbl = bodyOf(out).kids().first { it.loc() == "tbl" }
        val cells = tbl.kids().first { it.loc() == "tr" }.kids().filter { it.loc() == "tc" }
        assertEquals("案由：劳务合同纠纷", cellText(cells[0]))
        assertEquals("阶段：一审", cellText(cells[1]))
    }

    /** 空值按原占位符 key 长度留白：长度零变化、占位符位置被等量空格填满 */
    @Test
    fun blankValueFillsSpacesEqualToKeyLength() {
        val w = writer()
        w.addParagraph(listOf(TextRun("代理人：dailr完毕", font, 16.0)), ParaProps())
        val out = DocxTemplateFiller.fill(w.build("t"), mapOf("dailr" to ""))
        // dailr 长度 5 → 5 个空格
        assertEquals("代理人：     完毕", allText(bodyOf(out)))
    }

    /** 长键优先：gcsj1 应整体匹配，而不是先匹配 gcsj 留下数字 1 */
    @Test
    fun longerKeyWinsOverShorterPrefix() {
        val w = writer()
        w.addParagraph(listOf(TextRun("时间：gcsj1", font, 16.0)), ParaProps())
        val out = DocxTemplateFiller.fill(
            w.build("t"),
            mapOf("gcsj" to "过程时间", "gcsj1" to "2025年12月5日")
        )
        val text = allText(bodyOf(out))
        assertTrue(text.contains("2025年12月5日"))
        assertFalse("不应退化成 过程时间1", text.contains("过程时间1"))
    }

    /** 多行值应生成 w:br 软换行 */
    @Test
    fun multilineValueBecomesBreak() {
        val w = writer()
        w.addParagraph(listOf(TextRun("内容：gcnr1", font, 16.0)), ParaProps())
        val out = DocxTemplateFiller.fill(w.build("t"), mapOf("gcnr1" to "第一行\n第二行"))
        assertEquals("内容：第一行\n第二行", allText(bodyOf(out)))
        assertTrue(docXml(out).contains("<w:br"))
    }

    /** 表格行：处理后应带 trHeight@hRule=atLeast（允许跨页、最小行高） */
    @Test
    fun tableRowsGetAtLeastHeightRule() {
        val w = writer()
        w.addTable(
            listOf(
                listOf(
                    listOf(TextRun("A", font, 16.0)),
                    listOf(TextRun("B", font, 16.0))
                )
            )
        )
        val out = DocxTemplateFiller.fill(w.build("t"), mapOf("A" to "X"))
        val tr = bodyOf(out).kids().first { it.loc() == "tbl" }.kids().first { it.loc() == "tr" }
        val trPr = tr.kids().firstOrNull { it.loc() == "trPr" }
        assertNotNull(trPr)
        val h = trPr!!.kids().firstOrNull { it.loc() == "trHeight" }
        assertNotNull(h)
        assertEquals("atLeast", h!!.getAttribute("w:hRule"))
        // 不应保留 cantSplit
        assertFalse(trPr.kids().any { it.loc() == "cantSplit" })
    }

    /** 不是有效 docx（缺 word/document.xml）应抛异常 */
    @Test
    fun invalidDocxThrows() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("<x/>".toByteArray())
            z.closeEntry()
        }
        val ex = runCatching {
            DocxTemplateFiller.fill(bos.toByteArray(), mapOf("a" to "b"))
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
    }

    /** 无规则时原样返回；有替换时其余部件（styles 等）字节不变 */
    @Test
    fun untouchedPartsRemainByteIdentical() {
        val w = writer()
        w.addParagraph(listOf(TextRun("委托人：weitr", font, 16.0)), ParaProps())
        val src = w.build("t")
        val out = DocxTemplateFiller.fill(src, mapOf("weitr" to "周启民"))
        val o = unzip(src)
        val r = unzip(out)
        assertEquals(o.keys, r.keys)
        for ((name, data) in o) {
            if (name != "word/document.xml") {
                assertTrue("部件 $name 应原样", data.contentEquals(r[name]))
            }
        }
    }

    private fun countUnderlineRuns(body: Element): Int {
        var n = 0
        fun walk(e: Element) {
            if (e.loc() == "r" && e.kids().firstOrNull { it.loc() == "rPr" }
                    ?.kids()?.any { it.loc() == "u" } == true) n++
            for (c in e.kids()) walk(c)
        }
        walk(body)
        return n
    }
}
