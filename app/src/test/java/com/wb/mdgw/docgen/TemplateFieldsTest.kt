package com.wb.mdgw.docgen

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文书类型 ↔ 主要字段联动的纯逻辑校验：
 *  1. 严格数字前缀匹配（码 1 不能误吃 10/11，前缀必须在文件名开头）；
 *  2. 由扫描到的占位符 key + 内置结构生成字段表单（模板为准、内置兜底）。
 */
class TemplateFieldsTest {

    // ---------- 严格前缀 ----------

    @Test
    fun `strict prefix matches only leading code with non digit boundary`() {
        assertTrue(TemplateFields.matchesStrictPrefix("1-民事委托书.docx", "1"))
        assertTrue(TemplateFields.matchesStrictPrefix("1委托书.docx", "1"))
        assertTrue(TemplateFields.matchesStrictPrefix("9-法援.dto.docx", "9"))
        assertTrue(TemplateFields.matchesStrictPrefix("1.docx", "1")) // 码后到末尾
        // 码后紧跟数字：不算命中，避免 1 吃 10/11
        assertFalse(TemplateFields.matchesStrictPrefix("10-委托书.docx", "1"))
        assertFalse(TemplateFields.matchesStrictPrefix("11-委托书.docx", "1"))
        // 前缀不在开头：不算命中
        assertFalse(TemplateFields.matchesStrictPrefix("民事-1委托书.docx", "1"))
        assertFalse(TemplateFields.matchesStrictPrefix("x1-委托书.docx", "1"))
        // 空码永远不命中
        assertFalse(TemplateFields.matchesStrictPrefix("1-委托书.docx", ""))
    }

    @Test
    fun `prefix code is compared against stem not extension`() {
        // 扩展名里的数字不应参与匹配：文件名主体是 report，码 1 不命中
        assertFalse(TemplateFields.matchesStrictPrefix("report.1.docx", "1"))
        // 主体是 1 时命中（substringBeforeLast('.') 取到 "1-委托书"）
        assertTrue(TemplateFields.matchesStrictPrefix("1-委托书.v2.docx", "1"))
    }

    // ---------- 结构生成 ----------

    private val builtin = listOf(
        RuleLine.group("委托授权"),
        RuleLine.field("leib", "民事"),
        RuleLine.field("weitr", "张三"),
        RuleLine.field("jied", "一审"),
        RuleLine.group("调证"),
        RuleLine.comment("#案件情况"),
        RuleLine.field("cbdw", ""),
        RuleLine.group("结案信息"),
        RuleLine.field("anhao", "")
    )

    @Test
    fun `build structure keeps only scanned keys with their builtin group and default`() {
        // 模板只用到 weitr / cbdw / jied
        val target = TemplateFields.buildStructure(listOf("weitr", "cbdw", "jied"), builtin) { key ->
            mapOf("weitr" to "李四", "cbdw" to "某银行", "jied" to "二审")[key] ?: ""
        }
        val groups = target.filter { it.isGroup }.map { it.text }
        assertEquals(listOf("委托授权", "调证"), groups)
        // 委托授权组里只留了 weitr、jied（leib 未被扫描）
        val delegated = target.dropWhile { !(it.isGroup && it.text == "委托授权") }
            .drop(1) // 跳过分组标题本身
            .takeWhile { !it.isGroup }
            .filter { it.isField }.map { it.key }
        assertEquals(listOf("weitr", "jied"), delegated)
        // 值来自 valueOf（有效默认值），不是内置结构里的旧值
        assertEquals("李四", target.first { it.key == "weitr" }.value)
        assertEquals("二审", target.first { it.key == "jied" }.value)
        // 调证组的注释跟随保留，cbdw 值被注入
        assertTrue(target.any { it.isComment && it.text == "#案件情况" })
        assertEquals("某银行", target.first { it.key == "cbdw" }.value)
        // 结案信息组整块没有命中字段 → 分组标题与 anhao 都不出现
        assertFalse(target.any { it.key == "anhao" })
        assertFalse(groups.contains("结案信息"))
    }

    @Test
    fun `scanned keys absent from builtin go to template group`() {
        val target = TemplateFields.buildStructure(listOf("weitr", "dlrzj"), builtin) { "值" }
        assertTrue(target.any { it.isGroup && it.text == TemplateFields.TEMPLATE_GROUP })
        val custom = target.dropWhile { !(it.isGroup && it.text == TemplateFields.TEMPLATE_GROUP) }
            .filter { it.isField }.map { it.key }
        assertEquals(listOf("dlrzj"), custom)
        assertEquals("值", target.first { it.key == "dlrzj" }.value)
    }

    @Test
    fun `empty scan returns empty so caller falls back to builtin`() {
        assertTrue(TemplateFields.buildStructure(emptyList(), builtin) { "" }.isEmpty())
    }

    @Test
    fun `duplicate scanned keys do not duplicate fields`() {
        val target = TemplateFields.buildStructure(listOf("weitr", "weitr", "jied"), builtin) { "" }
        assertEquals(2, target.count { it.isField })
    }

    // ---------- 端到端：按前缀筛模板并扫描占位符 ----------

    /** 构造一个含 word/document.xml 的最小 docx，正文里放入裸拼音占位符 */
    private fun writeDocx(file: File, vararg tokens: String) {
        val body = tokens.joinToString(" ") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        file.writeBytes(bos.toByteArray())
    }

    @Test
    fun `scanKeys reads only strict-prefix templates and finds placeholders`() {
        val dir = kotlin.io.path.createTempDirectory("docgen-scan").toFile()
        val t1 = File(dir, "1-委托书.docx").also { writeDocx(it, "委托人", "weitr", "leib") }
        val t10 = File(dir, "10-委托书.docx").also { writeDocx(it, "cbdw") } // 码 1 不应吃 10
        val t9 = File(dir, "9-法援.docx").also { writeDocx(it, "anhao") }
        val pdf = File(dir, "1-附件.pdf").also { it.writeText("not a docx") } // pdf 不参与扫描
        val files = DocTemplateDir.scan(dir.path)
        assertEquals(4, files.size)

        val standard = listOf("leib", "weitr", "cbdw", "anhao")
        val keys1 = TemplateFields.scanKeys(files, "1", standard)
        assertTrue("1 号模板的占位符应被扫到", "weitr" in keys1 && "leib" in keys1)
        assertFalse("不能混入 10 号模板的 cbdw", "cbdw" in keys1)
        assertFalse("不能混入 9 号模板的 anhao", "anhao" in keys1)

        val keys10 = TemplateFields.scanKeys(files, "10", standard)
        assertTrue("10 号模板独立扫描", "cbdw" in keys10)

        // pickTemplates 只取 docx；pdf 排除
        assertTrue(TemplateFields.pickTemplates(files, "1").all { it.isDocx })
        assertTrue(t1.exists() && t10.exists() && t9.exists() && pdf.exists())
    }

    // ---------- 快照恢复：切走再切回时保留自定义结构 ----------

    @Test
    fun `restore snapshot keeps custom order groups aliases and values`() {
        // 用户改了分组名、调了顺序、加了中文别名、填了值；模板扫出的 key 全是老的
        val snapshot = listOf(
            RuleLine.group("我的授权"),
            RuleLine.field("jied", "二审", "阶段"),
            RuleLine.field("weitr", "张三", "委托人"),
            RuleLine.comment("#备注"),
            RuleLine.field("cbdw", "某银行")
        )
        val restored = TemplateFields.restoreSnapshot(
            snapshot, listOf("jied", "weitr", "cbdw")
        ) { "默认值" }
        // 无新增字段时原样返回，一字节都不重排
        assertEquals(snapshot, restored)
        assertEquals("我的授权", restored.first { it.isGroup }.text)
        assertEquals("张三", restored.first { it.key == "weitr" }.value)
        assertEquals("委托人", restored.first { it.key == "weitr" }.alias)
        assertEquals(listOf("jied", "weitr", "cbdw"), restored.filter { it.isField }.map { it.key })
        assertTrue(restored.any { it.isComment && it.text == "#备注" })
    }

    @Test
    fun `restore snapshot appends new scanned keys into template group before next group`() {
        val snapshot = listOf(
            RuleLine.group("委托授权"),
            RuleLine.field("weitr", "张三"),
            RuleLine.group(TemplateFields.TEMPLATE_GROUP),
            RuleLine.field("oldnew", "旧值"),
            RuleLine.group("结案"),
            RuleLine.field("anhao", "案号")
        )
        val restored = TemplateFields.restoreSnapshot(
            snapshot, listOf("weitr", "oldnew", "extra1", "extra2")
        ) { "模板默认:$it" }
        assertEquals(
            listOf("weitr", "oldnew", "extra1", "extra2", "anhao"),
            restored.filter { it.isField }.map { it.key }
        )
        // 新字段插在「模板字段」组内、结案分组之前
        assertTrue(restored.indexOfFirst { it.key == "extra1" } <
            restored.indexOfFirst { it.isGroup && it.text == "结案" })
        assertEquals("模板默认:extra2", restored.first { it.key == "extra2" }.value)
        // 快照里已有的字段值不被默认值覆盖
        assertEquals("张三", restored.first { it.key == "weitr" }.value)
        assertEquals("旧值", restored.first { it.key == "oldnew" }.value)
    }

    @Test
    fun `restore snapshot creates template group when missing`() {
        val snapshot = listOf(
            RuleLine.group("委托授权"),
            RuleLine.field("weitr", "张三")
        )
        val restored = TemplateFields.restoreSnapshot(snapshot, listOf("weitr", "newkey")) { "v" }
        assertEquals(
            listOf("委托授权", TemplateFields.TEMPLATE_GROUP),
            restored.filter { it.isGroup }.map { it.text }
        )
        assertEquals("v", restored.first { it.key == "newkey" }.value)
        assertEquals("张三", restored.first { it.key == "weitr" }.value)
    }

    @Test
    fun `restore snapshot does not duplicate new keys`() {
        val snapshot = listOf(RuleLine.field("a", "1"))
        val restored = TemplateFields.restoreSnapshot(snapshot, listOf("a", "b", "b")) { "x" }
        assertEquals(2, restored.count { it.isField })
    }
}
