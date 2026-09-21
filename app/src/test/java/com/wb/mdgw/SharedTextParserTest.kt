package com.wb.mdgw

import com.wb.mdgw.docgen.FieldDoc
import com.wb.mdgw.docgen.RuleLine
import com.wb.mdgw.docgen.SharedTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextParserTest {

    @Test
    fun parsesGroupsCommentsAndFields() {
        val txt = """
            --委托授权--
            leib::民事
            anyou::劳务合同纠纷
            #案件情况
            ajqk::某段事实
        """.trimIndent()

        val lines = SharedTextParser.parse(txt)
        assertEquals(RuleLine.TYPE_GROUP, lines[0].type)
        assertEquals("委托授权", lines[0].text)

        val leib = lines.first { it.key == "leib" }
        assertEquals("民事", leib.value)
        val anyou = lines.first { it.key == "anyou" }
        assertEquals("劳务合同纠纷", anyou.value)

        val comment = lines.first { it.isComment }
        assertEquals("案件情况", comment.text)
    }

    /** 中文分组标题（含中文括号 / 斜杠 / 中文冒号）都应正确识别 */
    @Test
    fun parsesChineseGroupTitles() {
        val txt = """
            --接待谈话(首次）--
            thsj::2025年12月9日
            --庭前准备/提纲--
            --缺传票：开庭通知说明--
            ktsj::2025年12月4日
        """.trimIndent()
        val groups = SharedTextParser.parse(txt).filter { it.isGroup }.map { it.text }
        assertTrue(groups.contains("接待谈话(首次）"))
        assertTrue(groups.contains("庭前准备/提纲"))
        assertTrue(groups.contains("缺传票：开庭通知说明"))
    }

    /** 带序号的 key（gcsj1）必须保留数字 */
    @Test
    fun keepsNumericSuffixKey() {
        val lines = SharedTextParser.parse("gcsj1::2025年12月5日\ngcfs1::直接")
        val k = lines.first { it.key == "gcsj1" }
        assertEquals("2025年12月5日", k.value)
    }

    /** 空值字段（dailr:: ）解析为空字符串而非 null */
    @Test
    fun parsesEmptyValueAsEmpty() {
        val lines = SharedTextParser.parse("dailr:: \nwdgx::")
        val d = lines.first { it.key == "dailr" }
        assertEquals("", d.value)
        val w = lines.first { it.key == "wdgx" }
        assertEquals("", w.value)
    }

    /**
     * PC 脚本会丢弃没有 :: 的续行（ajqk 第二段丢失）。
     * App 版必须把续行并入上一字段，用换行连接。
     */
    @Test
    fun mergesContinuationLinesIntoPreviousField() {
        val txt = """
            ajqk::第一段事实
            第二段事实，没有双冒号
            ssqq:: 请求判决
        """.trimIndent()
        val lines = SharedTextParser.parse(txt)
        val ajqk = lines.first { it.key == "ajqk" }
        assertEquals("第一段事实\n第二段事实，没有双冒号", ajqk.value)
        // 下一个字段仍正常
        assertEquals("请求判决", lines.first { it.key == "ssqq" }.value)
    }

    /** 转义：\n \t \\ 正确还原 */
    @Test
    fun unescapesSequences() {
        val v = SharedTextParser.parse("""k::a\nb\tc\\d""").first { it.key == "k" }.value
        assertEquals("a\nb\tc\\d", v)
    }

    /** 导出 → 再解析应保持一致（字段内换行转义为 \n，保证一行一字段） */
    @Test
    fun exportThenParseRoundTrip() {
        val original = """
            --接待谈话(首次）--
            thsj::2025年12月9日
            thdd:: 湖南金厚律师事务所
            #案件情况
            ajqk::第一段事实
            第二段事实
            dailr::
        """.trimIndent()

        val doc = FieldDoc(SharedTextParser.parse(original))
        val exported = SharedTextParser.export(doc)

        // 导出后每个字段必须只占一行（多行值被转义）
        val fieldLines = exported.lineSequence().filter { it.contains("::") }.toList()
        assertTrue("多行值应转义为一行", fieldLines.all { !it.substringAfter("::").contains('\n') })
        // 续行内容应保存在 ajqk 字段中
        val ajqkLine = fieldLines.first { it.startsWith("ajqk::") }
        assertTrue(ajqkLine.contains("第二段事实"))

        // 再解析，值与首次一致
        val reparsed = SharedTextParser.parse(exported)
        val ajqk = reparsed.first { it.key == "ajqk" }
        assertEquals("第一段事实\n第二段事实", ajqk.value)
        assertEquals("", reparsed.first { it.key == "dailr" }.value)
        assertEquals("2025年12月9日", reparsed.first { it.key == "thsj" }.value)
    }

    /** toMap 只收字段、保持顺序、空值字段也在 */
    @Test
    fun toMapContainsFieldsIncludingEmpty() {
        val doc = FieldDoc(SharedTextParser.parse("--g--\nleib::民事\ndailr::\n#c\nanyou::纠纷"))
        val map = doc.toMap()
        assertEquals(listOf("leib", "dailr", "anyou"), map.keys.toList())
        assertEquals("民事", map["leib"])
        assertTrue(map.containsKey("dailr"))
        assertEquals("", map["dailr"])
        assertFalse(map.containsKey("g"))
    }
}
