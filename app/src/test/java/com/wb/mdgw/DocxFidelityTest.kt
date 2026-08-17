package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.*

class DocxFidelityTest {

    private fun unzip(bytes: ByteArray): MutableMap<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { m[e.name] = zis.readBytes(); e = zis.nextEntry }
        }
        return m
    }

    private fun repack(parts: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun regularDocHasNoSpecialContent() {
        val original = DocxWriter(PageSetup(), "仿宋_GB2312", 16.0).apply {
            addParagraph(
                listOf(TextRun("普通正文", "仿宋_GB2312", 16.0)),
                ParaProps(align = Align.BOTH)
            )
        }.build("测试文档")
        assertTrue("常规文档不应提示特殊内容", DocxFidelity.scan(original).isEmpty())
    }

    @Test
    fun detectsRevisionMarks() {
        val base = DocxWriter(PageSetup(), "仿宋_GB2312", 16.0).apply {
            addParagraph(
                listOf(TextRun("普通正文", "仿宋_GB2312", 16.0)),
                ParaProps(align = Align.BOTH)
            )
        }.build("测试文档")
        val parts = unzip(base)
        val docXml = String(parts["word/document.xml"]!!, StandardCharsets.UTF_8)
        val injected = docXml.replaceFirst(
            "</w:p>",
            "<w:ins><w:r><w:t>修订插入</w:t></w:r></w:ins></w:p>"
        )
        val original = repack(
            parts + ("word/document.xml" to injected.toByteArray(StandardCharsets.UTF_8))
        )
        val notes = DocxFidelity.scan(original)
        assertTrue("应检测到修订痕迹", notes.contains("修订痕迹"))
    }
}
