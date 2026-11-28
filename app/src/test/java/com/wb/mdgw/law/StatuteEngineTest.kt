package com.wb.mdgw.law

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 离线法规引擎单测（纯 JVM，直接读仓库 assets JSON）。
 */
class StatuteEngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun findAsset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/law/$name"),
            File("app/src/main/assets/law/$name")
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException("找不到 asset: $name，工作目录=${System.getProperty("user.dir")}")
    }

    private fun corpus(): StatuteCorpus = json.decodeFromString(findAsset("laws-offline.json").readText(Charsets.UTF_8))

    // ---------- 语料完整性 ----------
    @Test
    fun `corpus has 1260 civil articles and 40 companion docs`() {
        val c = corpus()
        assertEquals(1260, c.civilArticles.size)
        assertEquals(40, c.companionDocs.size)
        assertTrue(c.totalArticles >= 4000)
    }

    @Test
    fun `buildEntries yields all statute entries with unique keys`() {
        val entries = StatuteEngine.buildEntries(corpus())
        assertTrue(entries.size >= 4000)
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        assertTrue(entries.any { it.isCivil && it.articleNo == 585 })
        assertTrue(entries.any { it.lawTitle.contains("公司法", true) })
    }

    @Test
    fun `checklists asset has 6 templates with sections`() {
        val list = json.decodeFromString<List<PracticeChecklist>>(findAsset("practice-checklists.json").readText(Charsets.UTF_8))
        assertEquals(6, list.size)
        assertTrue(list.all { it.sections.isNotEmpty() && it.sections.any { s -> s.items.isNotEmpty() } })
    }

    // ---------- 中文数字解析 ----------
    @Test
    fun `parseChineseArticleNumber handles arabic and chinese forms`() {
        assertEquals(585, StatuteEngine.parseChineseArticleNumber("585"))
        assertEquals(585, StatuteEngine.parseChineseArticleNumber("第五百八十五条"))
        assertEquals(11, StatuteEngine.parseChineseArticleNumber("十一"))
        assertEquals(20, StatuteEngine.parseChineseArticleNumber("二十"))
        assertEquals(585, StatuteEngine.parseChineseArticleNumber("五八五"))
        assertNull(StatuteEngine.parseChineseArticleNumber("零"))
    }

    @Test
    fun `parseStatuteQuery parses natural lawyer queries`() {
        assertEquals(585, StatuteEngine.parseStatuteQuery("585").exactNo)
        assertEquals(585, StatuteEngine.parseStatuteQuery("第五百八十五条").exactNo)
        assertEquals(585, StatuteEngine.parseStatuteQuery("民法典第585条").exactNo!!)
        assertEquals("民法典", StatuteEngine.parseStatuteQuery("民法典第585条").lawHint)
        val compound = StatuteEngine.parseStatuteQuery("民法典 第1165条 第2款")
        assertEquals(1165, compound.exactNo)
        assertEquals("第2款", compound.clauseHint)
    }

    // ---------- 搜索 ----------
    @Test
    fun `search by exact article number returns civil 585 first`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val result = StatuteEngine.search(entries, "585")
        assertTrue(result.hits.isNotEmpty())
        assertEquals("民法典", result.hits.first().lawTitle)
        assertEquals(585, result.hits.first().articleNo)
    }

    @Test
    fun `search by law prefix and number narrows to that law`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val result = StatuteEngine.search(entries, "民法典第585条")
        assertTrue(result.hits.isNotEmpty())
        assertTrue(result.hits.all { it.lawTitle == "民法典" })
        assertEquals(585, result.hits.first().articleNo)
    }

    @Test
    fun `search clause hint surfaces notice`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val result = StatuteEngine.search(entries, "民法典 第1165条 第2款")
        assertTrue(result.hits.isNotEmpty())
        assertTrue(result.clauseNotice.contains("第2款"))
    }

    @Test
    fun `search uncollected law refuses silent substitution`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val result = StatuteEngine.search(entries, "刑法第264条")
        assertTrue(result.hits.isEmpty())
        assertTrue(result.uncollectedNotice.contains("刑法"))
    }

    @Test
    fun `search keyword hits text and summary`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val result = StatuteEngine.search(entries, "违约金过高")
        assertTrue(result.hits.isNotEmpty())
    }

    @Test
    fun `scope filters work`() {
        val entries = StatuteEngine.buildEntries(corpus())
        assertTrue(StatuteEngine.search(entries, "585", StatuteEngine.Scope.CIVIL).hits.all { it.isCivil })
        assertTrue(StatuteEngine.search(entries, "585", StatuteEngine.Scope.STARRED, setOf("civil:585")).hits.any { it.key == "civil:585" })
    }

    @Test
    fun `citation includes full law title and verification meta`() {
        val entries = StatuteEngine.buildEntries(corpus())
        val e = entries.first { it.key == "civil:585" }
        val plain = StatuteEngine.citation(e, false, "2026-11-24")
        val verified = StatuteEngine.citation(e, true, "2026-11-24")
        assertTrue(plain.startsWith("《中华人民共和国民法典》"))
        assertTrue(verified.contains("flk.npc.gov.cn"))
        assertTrue(verified.contains("2026-11-24"))
    }
}
