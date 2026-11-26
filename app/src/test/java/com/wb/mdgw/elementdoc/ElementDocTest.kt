package com.wb.mdgw.elementdoc

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.io.File

/**
 * 要素式文书模块核心逻辑单测：数据完整性、勾选交互、草稿→成文解析、docx 版式结构。
 * 纯 JVM，无需 Android Context；数据直接读仓库 assets JSON。
 */
class ElementDocTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun corpusText(): String {
        val candidates = listOf(
            File("src/main/assets/elementdoc/spc-2025-templates.json"),
            File("app/src/main/assets/elementdoc/spc-2025-templates.json"),
            File(System.getProperty("user.dir"), "src/main/assets/elementdoc/spc-2025-templates.json")
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("找不到要素式模板 JSON，工作目录=${System.getProperty("user.dir")}")
        return f.readText(Charsets.UTF_8)
    }

    private fun corpus(): SpcCorpus = json.decodeFromString(corpusText())

    // ---------- 数据完整性 ----------

    @Test
    fun `corpus contains all 113 official templates across 9 domains`() {
        val c = corpus()
        assertEquals(113, c.count)
        assertEquals(113, c.templates.size)
        assertEquals("法〔2025〕82号", c.standard)
        val domains = c.templates.map { it.domainId }.toSet()
        assertEquals(9, domains.size)
        assertTrue(SpcDomain.ORDER.all { it in domains })
    }

    @Test
    fun `every template has rows title and signer`() {
        corpus().templates.forEach { t ->
            assertTrue("${t.id} 无 rows", t.rows.isNotEmpty())
            assertTrue("${t.id} 无标题", t.docTitle.isNotBlank())
            assertTrue("${t.id} 无落款", t.signerLine.isNotBlank())
            assertTrue("${t.id} row id 重复", t.rows.map { it.id }.toSet().size == t.rows.size)
        }
    }

    @Test
    fun `all four doc roles present`() {
        val roles = corpus().templates.map { it.docRole }.toSet()
        assertTrue(roles.containsAll(listOf(SpcRole.CLAIM, SpcRole.DEFENSE, SpcRole.APPLICATION, SpcRole.STATEMENT)))
    }

    // ---------- 勾选 token ----------

    @Test
    fun `parse checkbox tokens counts boxes and labels`() {
        val text = "性别：男□    女□\n是否了解：了解□ 不了解□"
        val tokens = ElementEngine.parseCheckboxTokens(text)
        assertEquals(4, tokens.size)
        assertEquals(0, tokens[0].index)
        assertFalse(tokens[0].checked)
        assertTrue(tokens[0].label.isNotBlank())
    }

    @Test
    fun `toggle checkbox switches only target box`() {
        val text = "男□ 女□"
        assertEquals("男☑ 女□", ElementEngine.toggleCheckboxAtIndex(text, 0))
        assertEquals("男□ 女☑", ElementEngine.toggleCheckboxAtIndex(text, 1))
    }

    @Test
    fun `mediation bulk select sets correct boxes`() {
        val text = "了解□ 不了解□\n了解□ 不了解□"
        val yes = ElementEngine.setAllMediationBenefitChoices(text, true)
        assertEquals(2, yes.count { it == '☑' })
        assertFalse(yes.contains("了解□ 不了解☑"))
        val no = ElementEngine.setAllMediationBenefitChoices(text, false)
        assertEquals(2, no.count { it == '☑' })
    }

    // ---------- 草稿→成文解析 ----------

    @Test
    fun `build resolved injects user values and keeps notice and section`() {
        val tpl = corpus().templates.first()
        val field = tpl.rows.first { it.kind == "field" }
        val notice = tpl.rows.first { it.kind == "notice" }
        val draft = ElementDraft(
            templateId = tpl.id,
            rowValues = mapOf(field.id to "用户填写内容"),
            signerName = "张三", signDate = "2026年1月1日"
        )
        val doc = ElementEngine.buildResolved(tpl, draft)
        assertEquals(tpl.id, doc.templateId)
        assertEquals("张三", doc.signerName)
        assertEquals(notice.templateText, doc.rows.first { it.id == notice.id }.value)
        assertEquals("用户填写内容", doc.rows.first { it.id == field.id }.value)
    }

    @Test
    fun `build resolved fallback to template text when no user value`() {
        val tpl = corpus().templates.first()
        val field = tpl.rows.first { it.kind == "field" }
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        assertEquals(field.templateText, doc.rows.first { it.id == field.id }.value)
    }

    @Test
    fun `blank mode reproduces verbatim official template`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id), blankMode = true)
        val fieldRow = tpl.rows.first { it.kind == "field" }
        assertEquals(fieldRow.templateText, doc.rows.first { it.id == fieldRow.id }.value)
        assertTrue(doc.rows.any { it.kind == "notice" })
    }

    @Test
    fun `excluded rows hidden and extra rows inserted`() {
        val tpl = corpus().templates.first()
        val field = tpl.rows.first { it.kind == "field" }
        val draft = ElementDraft(
            templateId = tpl.id,
            excludedRowIds = setOf(field.id),
            extraRows = listOf(ElementDraft.ExtraRow("x1", field.id, "自定义", "值"))
        )
        val doc = ElementEngine.buildResolved(tpl, draft)
        assertFalse("被隐藏行不应出现", doc.rows.any { it.id == field.id && it.value == field.templateText })
        assertTrue("自定义行应插入", doc.rows.any { it.id == "x1" && it.value == "值" })
    }

    // ---------- docx 版式结构 ----------

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { m[e.name] = zis.readBytes(); e = zis.nextEntry }
        }
        return m
    }

    @Test
    fun `docx is valid zip with all OOXML parts`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val bytes = SpcDocxBuilder.build(doc)
        val parts = unzip(bytes)
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("word/document.xml"))
        assertTrue(parts.containsKey("word/styles.xml"))
        assertTrue(parts.containsKey("word/fontTable.xml"))
        assertTrue(parts.containsKey("word/_rels/document.xml.rels"))
    }

    @Test
    fun `docx keeps official title signer checkboxes and A4 layout`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val xml = String(unzip(SpcDocxBuilder.build(doc))["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue("缺标题", xml.contains(tpl.docTitle))
        assertTrue("缺 A4 页面", xml.contains("w:w=\"11906\" w:h=\"16838\""))
        assertTrue("缺官方表格边框", xml.contains("<w:tblBorders>"))
        assertTrue("缺落款", xml.contains(tpl.signerLine))
        assertTrue("勾选框符号未保留", xml.contains("□") || xml.contains("☑"))
        assertTrue("缺仿宋/宋体字体", xml.contains("SimSun") && xml.contains("FangSong"))
    }

    @Test
    fun `all 113 templates generate well formed docx`() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        corpus().templates.forEach { tpl ->
            val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id), blankMode = true)
            val bytes = SpcDocxBuilder.build(doc)
            val parts = unzip(bytes)
            assertTrue("${tpl.displayName} 缺 document.xml", parts.containsKey("word/document.xml"))
            // XML 必须良构（可被 DOM 解析），否则 Word 打不开
            val stream = ByteArrayInputStream(parts["word/document.xml"]!!)
            try {
                factory.newDocumentBuilder().parse(stream)
            } catch (e: Exception) {
                fail("${tpl.displayName} 生成 XML 非良构：${e.message}")
            }
            val xml = String(parts["word/document.xml"]!!, Charsets.UTF_8)
            assertTrue("${tpl.displayName} 缺标题", xml.contains(tpl.docTitle))
        }
    }

    @Test
    fun `field label alignment follows official layout`() {
        val tpl = corpus().templates.first { it.id == "spc2025_001" }
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val xml = String(unzip(SpcDocxBuilder.build(doc))["word/document.xml"]!!, Charsets.UTF_8)

        // 取某 label 所在段落的对齐值：段落形如 <w:p><w:pPr><w:jc w:val="left"/>...label...</w:p>
        fun alignOf(label: String): String {
            val needle = "<w:t xml:space=\"preserve\">$label</w:t>"
            val at = xml.indexOf(needle)
            assertTrue("未找到 label: $label", at >= 0)
            val pStart = xml.lastIndexOf("<w:p>", at)
            val pEnd = xml.indexOf("</w:p>", at)
            val para = xml.substring(pStart, pEnd)
            val jc = Regex("<w:jc w:val=\"([^\"]+)\"").find(para)
            return jc?.groupValues?.get(1) ?: error("段落无对齐: $label")
        }

        // 带数字序号的小标题 → 左对齐
        assertEquals("数字序号小标题应左对齐", "left", alignOf("1. 给付价款（元）"))
        assertEquals("序号小标题应左对齐", "left", alignOf("20. 是否签订保证合同"))
        // 调解部分第一列 → 左对齐
        assertEquals("调解行应左对齐", "left", alignOf("是否考虑先行调解"))
        assertEquals("调解行应左对齐", "left", alignOf("是否了解先行调解解决纠纷的好处"))
        // 当事人信息 label → 保持居中
        assertEquals("当事人信息应居中", "center", alignOf("委托诉讼代理人"))
        // ★特别提示★ → 左对齐
        assertEquals("特别提示应左对齐", "left", alignOf("★特别提示★"))
    }

    @Test
    fun `docx escapes xml special chars`() {
        val tpl = corpus().templates.first()
        val field = tpl.rows.first { it.kind == "field" }
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(
            templateId = tpl.id, rowValues = mapOf(field.id to "A & B <测试> \"引号\"")
        ))
        val xml = String(unzip(SpcDocxBuilder.build(doc))["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("A &amp; B &lt;测试&gt;"))
        assertFalse(xml.contains("A & B <测试>"))
    }
}
