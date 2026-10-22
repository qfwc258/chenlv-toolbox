package com.wb.mdgw.docgen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 案件类型默认结构与「切类型并入」逻辑的校验。
 *
 * DefaultFields 的资源加载需要 Context（JVM 单测拿不到），因此这里直接读仓库里的
 * assets 文本，用与运行时完全相同的 [SharedTextParser.parse] 解析后断言，
 * 覆盖的是真正有风险的两件事：
 *  1. 每套结构都保留模板依赖的既有 key（key 是与 /sdcard/pylaw/mb 下模板的填充契约，删了就填不上）；
 *  2. 切类型时已填内容不丢、空槽不攒。
 */
class CaseTypeStructureTest {

    private fun assetDir(): File {
        val candidates = listOf(
            File("src/main/assets/docgen/defaults"),          // Gradle 以模块目录为工作目录
            File("app/src/main/assets/docgen/defaults"),      // 从仓库根运行
            File(System.getProperty("user.dir"), "src/main/assets/docgen/defaults")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("找不到默认结构资源目录，工作目录=${System.getProperty("user.dir")}")
    }

    private fun structure(id: String): List<RuleLine> {
        val f = File(assetDir(), "$id.txt")
        assertTrue("缺少案件类型默认结构：${f.path}", f.isFile)
        return SharedTextParser.parse(f.readText(Charsets.UTF_8))
    }

    private fun keys(id: String): List<String> = structure(id).filter { it.isField }.map { it.key }

    /** 模板填充契约 key：任何一套结构都必须包含，否则对应占位符会填不上 */
    private val CONTRACT_KEYS = listOf(
        "leib", "anyou", "weitr", "bianh", "badw", "jied", "zprq",
        "wtrsfz", "wtrdh", "wtrxb", "wtrcs", "wtrzz",
        "dailr", "wdgx", "dlrdh", "dlrsfz",
        "wtxm", "quanx", "sq1", "sq2", "lsf",
        "cbdw", "bdqxx",
        "thsj", "thdd", "ajqk", "ssqq", "basl",
        "yjrq", "yjnr",
        "bgksl", "zztg", "jztg", "dltg",
        "dzsj", "ktsj", "ktdd",
        "anhao", "jarq", "gdrq", "dfdsr", "cbxj", "ljsm", "bljg"
    ) + (1..9).flatMap { n -> listOf("gcsj$n", "gcfs$n", "gcnr$n") }

    @Test
    fun `every case type keeps the template contract keys`() {
        for (id in listOf("civil_legal_aid", "criminal_legal_aid", "civil_entrust", "criminal_entrust")) {
            val ks = keys(id)
            val missing = CONTRACT_KEYS.filter { it !in ks }
            assertTrue("$id 缺少模板契约 key：$missing", missing.isEmpty())
            assertEquals("$id 存在重复 key", ks.size, ks.distinct().size)
        }
    }

    @Test
    fun `case type ids match the store builtins`() {
        // CaseTypeStore 的 id 就是资源文件名，改名不同步会静默回退到通用结构
        val ids = CaseTypeStore.DEFAULT.map { it.id }
        assertEquals(4, ids.size)
        for (id in ids) assertNotNull(structure(id).firstOrNull { it.isGroup })
    }

    @Test
    fun `new keys only add and all have labels`() {
        val civilAid = keys("civil_legal_aid").toSet()
        for (id in listOf("criminal_legal_aid", "civil_entrust", "criminal_entrust")) {
            val added = keys(id).filter { it !in civilAid }
            assertTrue("$id 不应删减民事法援的 key", (civilAid - keys(id).toSet()).isEmpty())
            added.forEach { assertNotNull("$id 的新增 key $it 没有中文标签", FieldLabels.labelOf(it)) }
        }
        assertTrue(keys("criminal_legal_aid").containsAll(listOf("zcjg", "zming", "qzcs", "jysuo", "trrf", "liangj")))
        assertTrue(keys("criminal_entrust").containsAll(listOf("zcjg", "htbh", "wtrq", "fxgz")))
        assertTrue(keys("civil_entrust").containsAll(listOf("htbh", "wtrq", "fxgz")))
        assertFalse("民事法援不该出现刑事专用 key", keys("civil_legal_aid").contains("zcjg"))
    }

    @Test
    fun `switching type keeps filled values and hands untouched defaults to the new type`() {
        val from = structure("civil_entrust")
        // 资源文本里的值本身就是内置默认值，等价于运行时套过 applyUserDefaults 的目标结构
        val to = structure("criminal_entrust")
        val sourceDefaults = from.filter { it.isField }.associate { it.key to it.value }

        val current = from.map { l ->
            when (l.key) {
                "weitr" -> l.copy(value = "李四")
                "htbh" -> l.copy(value = "(2026)湘委民字第9号", alias = "合同号")
                else -> l
            }
        }
        val merged = DefaultFields.mergeStructure(current, to, sourceDefaults)
        val map = merged.filter { it.isField }.associate { it.key to it.value }

        // 改过的内容：保留（即使新类型另有默认值）
        assertEquals("李四", map["weitr"])
        assertEquals("(2026)湘委民字第9号", map["htbh"])
        assertEquals("合同号", merged.first { it.key == "htbh" }.alias)
        // 没改过的默认值：由新类型接管，不能把「民事 / 一审 / xx公司」带进刑事表单
        assertEquals("刑事", map["leib"])
        assertEquals("侦查", map["jied"])
        assertEquals("", map["dfdsr"])
        // 新类型独有的键：取其自身默认值
        assertEquals("宁乡市公安局", map["zcjg"])
        // 分组以目标结构为准
        val groupTitles = merged.filter { it.isGroup }.map { it.text }
        assertTrue("应带入刑事分组", groupTitles.contains("刑事案件情况"))
        assertTrue("分组顺序应以目标结构开头", groupTitles.first() == "委托授权")
        assertEquals("不应丢字段也不应重复", to.count { it.isField }, merged.count { it.isField })
    }

    @Test
    fun `filled fields missing from target go to custom group`() {
        val target = listOf(
            RuleLine.group("委托授权"),
            RuleLine.field("weitr", "张三"),
            RuleLine.group("结案信息"),
            RuleLine.field("anhao", "")
        )
        val current = listOf(
            RuleLine.group("委托授权"),
            RuleLine.field("weitr", ""),
            RuleLine.group("我的补充"),
            RuleLine.field("extra1", "有值"),
            RuleLine.field("extra2", "")
        )
        val merged = DefaultFields.mergeStructure(current, target, emptyMap())
        val fields = merged.filter { it.isField }

        assertEquals("张三", fields.first { it.key == "weitr" }.value)
        assertEquals("有值", fields.first { it.key == "extra1" }.value)
        assertTrue("空值的外来字段应丢弃", fields.none { it.key == "extra2" })
        val customIdx = merged.indexOfFirst { it.isGroup && it.text == DefaultFields.CUSTOM_GROUP }
        val extraIdx = merged.indexOfFirst { it.isField && it.key == "extra1" }
        assertTrue("外来字段应归入自定义字段分组", customIdx in 0 until extraIdx)
    }
}
