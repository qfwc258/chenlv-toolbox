package com.wb.mdgw

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验「添加页码」开关下生成的 .docx 是否为合法 OOXML：
 *  - 含 footer1.xml 部件
 *  - document.xml.rels 声明 footer 关系
 *  - sectPr 含 footerReference
 *  - footer1.xml 含 PAGE 域
 */
class DocxPageNumberTest {

    @Test
    fun docxWithPageNumber_containsValidFooter() {
        val w = DocxWriter(pageNumber = true)
        w.addParagraph(
            listOf(TextRun("正文首行测试", "仿宋_GB2312", 16.0)),
            ParaProps(align = Align.BOTH, firstLineIndentPt = 32.0, lineSpacingPt = 28.0)
        )
        val bytes = w.build("测试公文")
        assertTrue("docx 不应为空", bytes.isNotEmpty())

        val dir = File("/tmp/docx_pn_check").also { it.deleteRecursively(); it.mkdirs() }
        val docx = File(dir, "out.docx").also { it.writeBytes(bytes) }

        // 用系统 unzip 解包
        ProcessBuilder("unzip", "-o", docx.absolutePath, "-d", dir.absolutePath)
            .redirectErrorStream(true).start().waitFor()

        val footer = File(dir, "word/footer1.xml")
        assertTrue("缺少 footer1.xml", footer.exists())

        val rels = File(dir, "word/_rels/document.xml.rels").readText()
        assertTrue("document.xml.rels 未声明 footer 关系",
            rels.contains("relationships/footer") && rels.contains("footer1.xml"))

        val document = File(dir, "word/document.xml").readText()
        assertTrue("sectPr 缺少 footerReference", document.contains("footerReference"))

        val footerText = footer.readText()
        assertTrue("footer1.xml 缺少 PAGE 域", footerText.contains("PAGE"))
        assertTrue("footer1.xml 应为居中", footerText.contains("""w:jc w:val="center""""))

        println("[DocxPageNumberTest] 校验通过：footer / rels / sectPr / PAGE 域 均存在")
    }

    @Test
    fun docxWithoutPageNumber_hasNoFooter() {
        val w = DocxWriter(pageNumber = false)
        w.addParagraph(
            listOf(TextRun("正文", "仿宋_GB2312", 16.0)),
            ParaProps(align = Align.LEFT)
        )
        val bytes = w.build("无页码")
        val dir = File("/tmp/docx_nopn_check").also { it.deleteRecursively(); it.mkdirs() }
        val docx = File(dir, "out.docx").also { it.writeBytes(bytes) }
        ProcessBuilder("unzip", "-o", docx.absolutePath, "-d", dir.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        assertTrue("无页码时不应有 footer1.xml", !File(dir, "word/footer1.xml").exists())
        println("[DocxPageNumberTest] 校验通过：关闭页码时无障碍 docx")
    }
}
