package com.wb.mdgw.elementdoc

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 要素式文书新增功能单测：一键带入执业档案、快速结构化填入、虚构案例、草稿备份、A4 预览 HTML。
 * 纯 JVM，无需 Android Context。
 */
class ElementFeatureTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun corpus(): SpcCorpus {
        val candidates = listOf(
            File("src/main/assets/elementdoc/spc-2025-templates.json"),
            File("app/src/main/assets/elementdoc/spc-2025-templates.json"),
            File(System.getProperty("user.dir"), "src/main/assets/elementdoc/spc-2025-templates.json")
        )
        val f = candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("找不到要素式模板 JSON")
        return json.decodeFromString(f.readText(Charsets.UTF_8))
    }

    private val agentText = "有□\n姓名：\n单位：        职务：        联系电话：\n代理权限：一般授权□    特别授权□\n无□"

    /** 从 docx zip 字节中取 word/document.xml 文本 */
    private fun docxDocumentXml(bytes: ByteArray): String {
        val zis = java.util.zip.ZipInputStream(bytes.inputStream())
        while (true) {
            val e = zis.nextEntry ?: break
            if (e.name == "word/document.xml") return zis.readBytes().toString(Charsets.UTF_8)
            zis.closeEntry()
        }
        throw IllegalStateException("docx 缺少 word/document.xml")
    }

    // ---------- 一键带入执业档案 ----------

    @Test
    fun `lawyer profile fills agent row with name firm license and special authority`() {
        val out = PartyFillEngine.fromLawyerProfile(agentText, "张三", "湖南某某律师事务所", "14301202010123456", "13800000000")
        assertTrue(out.contains("有☑"))
        assertTrue(out.contains("无□"))
        assertTrue(out.contains("姓名：张三"))
        assertTrue(out.contains("湖南某某律师事务所"))
        assertTrue(out.contains("职务：专职律师"))
        assertFalse(out.contains("执业证号"))
        assertTrue(out.contains("联系电话：13800000000"))
        assertTrue(out.contains("一般授权□"))
        assertTrue(out.contains("特别授权☑"))
    }

    @Test
    fun `lawyer profile without license still fills agent`() {
        val out = PartyFillEngine.fromLawyerProfile(agentText, "李四", "某某律所", "", "")
        assertTrue(out.contains("姓名：李四"))
        assertTrue(out.contains("职务：专职律师"))
        assertTrue(out.contains("特别授权☑"))
    }

    @Test
    fun `agent without delegate toggles to no`() {
        val out = PartyFillEngine.applyToCellText(agentText, "agent", PartyFillEngine.Data(agentHas = false))
        assertTrue(out.contains("有□"))
        assertTrue(out.contains("无☑"))
    }

    // ---------- 快速结构化填入 ----------

    @Test
    fun `person quick fill sets gender birth work unit and id`() {
        val tpl = "姓名：\n性别：男□    女□\n出生日期：        年        月        日        民族：\n工作单位：        职务：        联系电话：\n住所地（户籍所在地）：\n经常居住地：\n证件类型：\n证件号码："
        val out = PartyFillEngine.applyToCellText(
            tpl, "plaintiff_person",
            PartyFillEngine.Data(
                name = "王五", gender = "女", birthDate = "1990-3-5", ethnicity = "汉族",
                workUnit = "某公司", jobTitle = "经理", phone = "13900000000",
                domicile = "长沙市", idType = "居民身份证", idNumber = "430100199003051234"
            )
        )
        assertTrue(out.contains("姓名：王五"))
        assertTrue(out.contains("男□"))
        assertTrue(out.contains("女☑"))
        assertTrue(out.contains("1990 年 3 月 5 日"))
        assertTrue(out.contains("工作单位：某公司"))
        assertTrue(out.contains("住所地（户籍所在地）：长沙市"))
        assertTrue(out.contains("证件号码：430100199003051234"))
    }

    @Test
    fun `org quick fill checks org type and民营 ownership`() {
        val tpl = corpus().templates.first { it.rows.any { r -> r.roleTag == "plaintiff_org" } }
            .rows.first { it.roleTag == "plaintiff_org" }.templateText
        val out = PartyFillEngine.applyToCellText(
            tpl, "plaintiff_org",
            PartyFillEngine.Data(
                name = "示例公司", orgType = "股份有限公司", ownershipNature = "民营",
                creditCode = "91330106MA2DEMO01X", legalRep = "林示例"
            )
        )
        assertTrue(out.contains("名称：示例公司"))
        assertTrue(out.contains("股份有限公司☑"))
        assertTrue(out.contains("有限责任公司□"))
        assertTrue(out.contains("民营☑"))
        assertTrue(out.contains("统一社会信用代码：91330106MA2DEMO01X"))
    }

    @Test
    fun `isPartyFillable covers all party roles`() {
        assertTrue(PartyFillEngine.isPartyFillable("agent"))
        assertTrue(PartyFillEngine.isPartyFillable("plaintiff_person"))
        assertTrue(PartyFillEngine.isPartyFillable("defendant_org"))
        assertFalse(PartyFillEngine.isPartyFillable("mediation_benefit"))
        assertFalse(PartyFillEngine.isPartyFillable("case_meta"))
    }

    // ---------- 虚构案例 ----------

    @Test
    fun `demo sample fills plaintiff and defendant and mediation rows`() {
        val tpl = corpus().templates.first { it.rows.any { r -> r.roleTag == "plaintiff_org" } }
        val draft = DemoSampleEngine.build(tpl)
        assertEquals(tpl.id, draft.templateId)
        assertEquals(DemoSampleEngine.DEMO_CASE_NAME, draft.caseName)
        assertTrue(draft.rowValues.isNotEmpty())
        assertTrue(draft.rowValues.values.any { it.contains("杭州云栖示例商贸有限公司") })
        assertTrue(draft.rowValues.values.any { it.contains("宁波海潮示例实业有限公司") })
        assertTrue(draft.rowValues.values.any { it.contains("虚构演示样例") })
        assertTrue(draft.rowValues.values.any { it.contains("了解☑") })
    }

    @Test
    fun `demo sample file tag marks不可提交`() {
        assertTrue(DemoSampleEngine.DEMO_FILE_TAG.contains("不可提交"))
    }

    // ---------- 草稿备份 ----------

    @Test
    fun `draft backup serialize and parse round trip`() {
        val drafts = listOf(
            ElementDraft(templateId = "t1", caseName = "案A", rowValues = mapOf("r1" to "值1")),
            ElementDraft(templateId = "t2", caseName = "案B")
        )
        val json = ElementDraftBackup.serialize(drafts)
        val parsed = ElementDraftBackup.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("案A", parsed[0].caseName)
        assertEquals("值1", parsed[0].rowValues["r1"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `draft backup rejects foreign json`() {
        ElementDraftBackup.parse("{\"app\":\"other-app\",\"drafts\":[]}")
    }

    // ---------- A4 预览 HTML ----------

    @Test
    fun `a4 html contains page rule title table and attachments`() {
        val tpl = corpus().templates.first { it.attachments.isNotEmpty() }
        val draft = ElementDraft(templateId = tpl.id, caseName = "A4测试")
        val doc = ElementEngine.buildResolved(tpl, draft)
        val html = ElementDocHtmlBuilder.build(doc)
        assertTrue(html.contains("@page{size:A4"))
        assertTrue(html.contains("20mm 20mm 20mm 24mm"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains(doc.docTitle))
        assertTrue(html.contains("page-break-before:always"))
    }

    @Test
    fun `a4 html colgroup matches docx tblGrid exactly`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val html = ElementDocHtmlBuilder.build(doc)
        val grid = SpcGridLayout.forDocument(doc)
        val xml = docxDocumentXml(SpcDocxBuilder.build(doc))
        val mainTblGrid = Regex("<w:tblGrid>(.*?)</w:tblGrid>").find(xml)!!.groupValues[1]
        val docxCols = Regex("<w:gridCol w:w=\"(\\d+)\"\\/>").findAll(mainTblGrid)
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals(grid.cols, docxCols)
        assertTrue(html.contains("<table><colgroup>"))
        grid.cols.forEach {
            assertTrue("预览缺少列宽 ${SpcGridLayout.percent(it)}", html.contains("width:${SpcGridLayout.percent(it)}"))
        }
    }

    @Test
    fun `a4 html uses official spans for four column rows`() {
        val tpl = corpus().templates.first { it.rows.any { r -> r.kind == "grid" && r.cells.size == 4 } }
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val html = ElementDocHtmlBuilder.build(doc)
        val grid = SpcGridLayout.forDocument(doc)
        assertTrue(html.contains("<td colspan='${grid.four[0]}' class='grid-h'>"))
        assertTrue(html.contains("<td colspan='${grid.four[1]}'>"))
    }

    @Test
    fun `a4 html attachment widths match docx grid`() {
        val tpl = corpus().templates.first { it.attachments.isNotEmpty() }
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val html = ElementDocHtmlBuilder.build(doc)
        val att = doc.attachments.first()
        SpcGridLayout.attachmentWidths(att).forEach {
            assertTrue("附件缺少列宽 ${SpcGridLayout.percent(it)}", html.contains("width:${SpcGridLayout.percent(it)}"))
        }
    }

    @Test
    fun `a4 html escapes xml special chars in user text`() {
        val tpl = corpus().templates.first()
        // 把特殊字符注入第一个字段行的已填值（会进入 ResolvedSpcDocument.value）
        val firstField = tpl.rows.first { it.kind == "field" || it.kind == "full_input" }
        val draft = ElementDraft(
            templateId = tpl.id,
            caseName = "转义测试",
            rowValues = mapOf(firstField.id to "<script>x</script>&中文")
        )
        val doc = ElementEngine.buildResolved(tpl, draft)
        val html = ElementDocHtmlBuilder.build(doc)
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp;中文"))
        assertFalse(html.contains("<script>"))
    }

    // ---------- 本轮 UI/版式修正 ----------

    @Test
    fun `sign date formats with four spaces between year month day`() {
        assertEquals("2026 年    3 月    1 日", ElementEngine.formatSignDate("2026 年 3 月 1 日"))
        assertEquals("2026 年    3 月    1 日", ElementEngine.formatSignDate("2026-3-1"))
        assertEquals("2026 年    3 月    1 日", ElementEngine.formatSignDate("2026年03月01日"))
        assertEquals("    年    月    日", ElementEngine.formatSignDate("  "))
    }

    @Test
    fun `resolved doc sign date uses four spaces`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id, signDate = "2026年3月1日"))
        assertTrue(doc.signDate.contains("2026 年    3 月    1 日"))
    }

    @Test
    fun `docx signer and date are right aligned with four char indent`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id, signerName = "张三", signDate = "2026 年    3 月    1 日"))
        val xml = docxDocumentXml(SpcDocxBuilder.build(doc))
        assertTrue(xml.contains("<w:jc w:val=\"right\"/><w:ind w:right=\"960\"/>"))
        assertTrue(xml.contains("2026 年    3 月    1 日"))
    }

    @Test
    fun `a4 signer and date indent four em and star tip not bold`() {
        val tpl = corpus().templates.first()
        val doc = ElementEngine.buildResolved(tpl, ElementDraft(templateId = tpl.id))
        val html = ElementDocHtmlBuilder.build(doc)
        assertTrue(html.contains(".signer{text-align:right;padding-right:4em"))
        assertTrue(html.contains(".date{text-align:right;padding-right:4em"))
        assertTrue(html.contains(".notice .star{font-family:'SimSun','宋体',serif;font-weight:400;}"))
        assertFalse(html.contains(".notice .star{font-family:'SimSun','宋体',serif;font-weight:700;}"))
    }
}
