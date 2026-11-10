package com.wb.mdgw.docgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导入 shared_text.txt 回填字段值」的纯逻辑校验。
 * 不依赖 Context：默认结构直接构造，导入文本走与运行时相同的 [SharedTextParser.parse]。
 */
class SharedTextImportTest {

    private val defaults = listOf(
        RuleLine.group("委托授权"),
        RuleLine.field("leib", "民事"),
        RuleLine.field("weitr", "张三"),
        RuleLine.group("接待谈话"),
        RuleLine.field("ajqk", ""),
        RuleLine.field("ssqq", "")
    )

    private fun values(lines: List<RuleLine>): Map<String, String> =
        lines.filter { it.isField }.associate { it.key to it.value }

    @Test
    fun `import overwrites existing values and keeps alias groups and comments`() {
        val current = defaults.map {
            when (it.key) {
                "weitr" -> it.copy(value = "旧委托人", alias = "委托人")
                "leib" -> it.copy(value = "刑事")
                else -> it
            }
        } + RuleLine.comment("案件情况")
        val imported = SharedTextParser.parse("leib::民事\nweitr::\najqk::第一段\\n第二段")

        val result = DefaultFields.mergeImportedValues(current, imported, defaults)

        assertEquals(3, result.updated)
        assertEquals(0, result.added)
        val map = values(result.lines)
        assertEquals("民事", map["leib"])
        // 文件里的空值也要覆盖，保证与保存时的状态一致
        assertEquals("", map["weitr"])
        // 导出时转义的多行值，回填后仍是同一段内容
        assertEquals("第一段\n第二段", map["ajqk"])
        assertEquals("委托人", result.lines.first { it.key == "weitr" }.alias)
        assertEquals(
            listOf("委托授权", "接待谈话"),
            result.lines.filter { it.isGroup }.map { it.text }
        )
        assertTrue(result.lines.any { it.isComment && it.text == "案件情况" })
    }

    @Test
    fun `missing fields are restored into their proper groups`() {
        val current = listOf(
            RuleLine.group("委托授权"),
            RuleLine.field("weitr", "张三")
        )
        val imported = SharedTextParser.parse(
            "weitr::李四\najqk::案情说明\nssqq::请求判决\nzidingyi::自定义值"
        )

        val result = DefaultFields.mergeImportedValues(current, imported, defaults)
        val lines = result.lines

        assertEquals(1, result.updated)
        assertEquals(3, result.added)
        assertEquals("李四", values(lines)["weitr"])
        assertEquals("案情说明", values(lines)["ajqk"])
        assertEquals(
            "接待谈话",
            DefaultFields.groupOfField(lines, lines.indexOfFirst { it.key == "ajqk" })
        )
        assertEquals("请求判决", values(lines)["ssqq"])
        // 非标准字段也要在界面上有位置，且值不丢
        assertEquals("自定义值", values(lines)["zidingyi"])
        assertEquals(
            DefaultFields.CUSTOM_GROUP,
            DefaultFields.groupOfField(lines, lines.indexOfFirst { it.key == "zidingyi" })
        )
        assertEquals(4, lines.count { it.isField })
    }

    @Test
    fun `exported shared text can be imported back into a trimmed form`() {
        val saved = defaults.map {
            when (it.key) {
                "weitr" -> it.copy(value = "王五")
                "ajqk" -> it.copy(value = "事实一\n事实二")
                else -> it
            }
        }
        val exported = SharedTextParser.export(FieldDoc(saved))
        val parsed = SharedTextParser.parse(exported)
        // 模拟用户当前只剩一个字段：导入后保存过的字段与值都应回来
        val trimmed = listOf(RuleLine.group("委托授权"), RuleLine.field("weitr", ""))

        val result = DefaultFields.mergeImportedValues(trimmed, parsed, defaults)
        val map = values(result.lines)

        assertEquals("王五", map["weitr"])
        assertEquals("事实一\n事实二", map["ajqk"])
        assertEquals("民事", map["leib"])
    }
}
