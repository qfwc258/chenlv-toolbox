package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.Assert.*
import org.w3c.dom.Element

class DocxInPlaceTest {

    /** 用 DocxWriter 造一个含「标题段 + 正文段 + 2×2 表格」的样例 docx */
    private fun buildSample(): ByteArray {
        val w = DocxWriter(page = PageSetup(), defaultFont = "仿宋_GB2312", defaultSizePt = 16.0)
        w.addParagraph(
            listOf(TextRun("标题段落", "黑体", 22.0, true)),
            ParaProps(align = Align.CENTER)
        )
        w.addParagraph(
            listOf(TextRun("正文第一段", "仿宋_GB2312", 16.0)),
            ParaProps(align = Align.BOTH, firstLineIndentPt = 32.0)
        )
        w.addTable(
            listOf(
                listOf(listOf(TextRun("A1", "仿宋_GB2312", 16.0)), listOf(TextRun("B1", "仿宋_GB2312", 16.0))),
                listOf(listOf(TextRun("A2", "仿宋_GB2312", 16.0)), listOf(TextRun("B2", "仿宋_GB2312", 16.0)))
            )
        )
        return w.build("测试文档")
    }

    /** 造一个含「下划线 run」的样例：本合同由【张三(下划线)】与李四签订 */
    private fun buildUnderlineSample(): ByteArray {
        val w = DocxWriter(page = PageSetup(), defaultFont = "仿宋_GB2312", defaultSizePt = 16.0)
        w.addParagraph(
            listOf(
                TextRun("本合同由", "仿宋_GB2312", 16.0),
                TextRun("张三", "仿宋_GB2312", 16.0, underline = true),
                TextRun("与李四签订", "仿宋_GB2312", 16.0)
            ),
            ParaProps(align = Align.BOTH, firstLineIndentPt = 32.0)
        )
        return w.build("测试文档")
    }

    /** 造一个「下划线在段落中部」的样例：甲方【（盖章）】代表： （盖章 为下划线，前后均为无下划线） */
    private fun buildUnderlineMiddleSample(): ByteArray {
        val w = DocxWriter(page = PageSetup(), defaultFont = "仿宋_GB2312", defaultSizePt = 16.0)
        w.addParagraph(
            listOf(
                TextRun("甲方", "仿宋_GB2312", 16.0),
                TextRun("（盖章）", "仿宋_GB2312", 16.0, underline = true),
                TextRun("代表：", "仿宋_GB2312", 16.0)
            ),
            ParaProps(align = Align.BOTH, firstLineIndentPt = 32.0)
        )
        return w.build("测试文档")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                m[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        return m
    }

    private fun bodyOf(bytes: ByteArray): Element {
        val docXml = unzip(bytes)["word/document.xml"]!!
        val dom = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(docXml))
        return dom.documentElement.kids().first { it.loc() == "body" }
    }

    private fun Element.loc() = if (tagName.contains(":")) tagName.substringAfter(":") else tagName
    private fun Element.kids() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.child(tag: String) = kids().firstOrNull { it.loc() == tag }

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

    /** 把部件表重新打包成合法 docx 字节（用于注入测试样本） */
    private fun repack(parts: Map<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 仅编辑一个单元格：原 Word 的其余部件、表格结构、其他单元格、未编辑段落必须原样 */
    @Test
    fun wordSourceKeepsTableIntactWhenOnlyCellEdited() {
        val original = buildSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val tblIdx = gov.blocks.indexOfFirst { it is Block.Table }
        assertEquals(2, tblIdx)

        val edits = setOf(EditTarget(tblIdx, 0, 1)) // 改 B1
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val t = lst[tblIdx] as Block.Table
            lst[tblIdx] = Block.Table(
                t.rows.mapIndexed { r, row ->
                    row.mapIndexed { c, cell ->
                        if (r == 0 && c == 1) listOf(cell.first().copy(text = "B1改后")) else cell
                    }
                }
            )
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        // 1) 除 document.xml 外所有 zip 部件字节完全不变（样式/页眉/文档属性零改动）
        val o = unzip(original)
        val res = unzip(result)
        assertEquals(o.keys, res.keys)
        for ((name, data) in o) {
            if (name != "word/document.xml") {
                assertTrue("部件 $name 应原样不变", data.contentEquals(res[name]))
            }
        }

        // 2) 表格结构保留：列数、边框、单元格列宽都还在
        val tbl = bodyOf(result).kids().first { it.loc() == "tbl" }
        val grid = tbl.child("tblGrid")!!
        assertEquals(2, grid.kids().count { it.loc() == "gridCol" })
        val tblPr = tbl.child("tblPr")!!
        assertNotNull("边框应保留", tblPr.child("tblBorders"))
        val firstCell = tbl.kids().first { it.loc() == "tr" }.kids().first { it.loc() == "tc" }
        assertNotNull("单元格列宽(tcW)应保留", firstCell.child("tcPr")?.child("tcW"))

        // 3) 单元格文字：仅 B1 更新，其余不变
        val cells = tbl.kids().filter { it.loc() == "tr" }
            .map { tr -> tr.kids().filter { it.loc() == "tc" }.map { cellText(it) } }
        assertEquals(listOf("A1", "B1改后"), cells[0])
        assertEquals(listOf("A2", "B2"), cells[1])

        // 4) 未编辑的段落原样
        val firstPara = bodyOf(result).kids().first { it.loc() == "p" }
        assertEquals("标题段落", firstPara.textContent)
    }

    /** 只改一个段落：表格与另一段落不受影响 */
    @Test
    fun wordSourceKeepsUneditedContentIntact() {
        val original = buildSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val edits = setOf(EditTarget(1, -1, -1)) // 只改正文第一段
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[1] as Block.Para
            lst[1] = Block.Para(listOf(p.runs.first().copy(text = "正文改后")), p.props)
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        val o = unzip(original)
        val res = unzip(result)
        for ((name, data) in o) {
            if (name != "word/document.xml") assertTrue("部件 $name 不变", data.contentEquals(res[name]))
        }

        val body = bodyOf(result)
        val paras = body.kids().filter { it.loc() == "p" }
        assertEquals("标题段落", paras[0].textContent) // 未编辑
        assertEquals("正文改后", paras[1].textContent)   // 已编辑

        val tbl = body.kids().first { it.loc() == "tbl" }
        val cells = tbl.kids().filter { it.loc() == "tr" }
            .map { tr -> tr.kids().filter { it.loc() == "tc" }.map { cellText(it) } }
        assertEquals(listOf("A1", "B1"), cells[0]) // 表格完全不变
        assertEquals(listOf("A2", "B2"), cells[1])
    }

    /** 没有任何编辑：导出结果应与原始 document.xml 完全一致 */
    @Test
    fun noEditsProducesIdenticalDocumentXml() {
        val original = buildSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val result = DocxInPlace.edit(original, gov.blocks, emptySet())
        val o = unzip(original)["word/document.xml"]!!
        val r = unzip(result)["word/document.xml"]!!
        assertTrue("无编辑时 document.xml 应字节一致", o.contentEquals(r))
    }

    /** 编辑含下划线的段落：下划线 run 数量不变、文字更新、其余部件不动 */
    @Test
    fun wordSourceKeepsUnderlineAfterEdit() {
        val original = buildUnderlineSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val pIdx = gov.blocks.indexOfFirst { it is Block.Para && it.runs.any { r -> r.underline } }
        assertTrue("应能解析到下划线 run", pIdx >= 0)

        val origUl = countUnderlineRuns(bodyOf(original))
        assertTrue("原始应存在下划线 run", origUl > 0)

        val newText = "本协议由王五与赵六签署"
        val edits = setOf(EditTarget(pIdx, -1, -1))
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[pIdx] as Block.Para
            val style = p.runs.first()
            // 与 UI 的 applyEdit 行为一致：沿用首 run 样式，整段重写成一个 run
            lst[pIdx] = Block.Para(listOf(style.copy(text = newText)), p.props)
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        // 1) 下划线 run 数量保持不变（原文带下划线的位置编辑后仍带下划线）
        assertEquals(origUl, countUnderlineRuns(bodyOf(result)))

        // 2) 段落整体文字已更新为用户输入
        val edited = bodyOf(result).kids().first { it.loc() == "p" && it.textContent == newText }
        assertEquals(newText, edited.textContent)

        // 3) 除 document.xml 外所有部件字节不变
        val o = unzip(original)
        val res = unzip(result)
        for ((name, data) in o) {
            if (name != "word/document.xml") assertTrue("部件 $name 不变", data.contentEquals(res[name]))
        }
    }

    /**
     * 回归：下划线在段落中部、且新文字比原文更长时，附近「无下划线」的文字绝不能被误加上下划线。
     * 旧实现按各 run 等比切分，会把相邻明文划进下划线 run 的份额（本例会误把「代」也加上下划线）；
     * 修复后「格式 run 锁长、无格式 run 吸收余量」，下划线只覆盖原字段本身。
     */
    @Test
    fun wordSourceDoesNotSpreadUnderlineToNearbyPlainText() {
        val original = buildUnderlineMiddleSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val pIdx = gov.blocks.indexOfFirst { it is Block.Para && it.runs.any { r -> r.underline } }
        assertTrue("应能解析到下划线 run", pIdx >= 0)

        val origUl = countUnderlineRuns(bodyOf(original))
        assertEquals(1, origUl)

        // 新文字比原文（9 字）更长（12 字），且下划线位于中段
        val newText = "乙方（盖章）代表：变更生效"
        val edits = setOf(EditTarget(pIdx, -1, -1))
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[pIdx] as Block.Para
            val style = p.runs.first()
            lst[pIdx] = Block.Para(listOf(style.copy(text = newText)), p.props)
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        // 1) 下划线 run 数量仍为 1，未被「摊薄」成多个、也未吞并明文
        assertEquals(1, countUnderlineRuns(bodyOf(result)))

        // 2) 下划线 run 的文字应仅为原字段「（盖章）」对应的那段，
        //    绝不能包含「代 / 表 / 变 / 更 / 生 / 效」等原无下划线的字
        val ulTexts = underlineRunTexts(bodyOf(result))
        assertEquals(listOf("（盖章）"), ulTexts)

        // 3) 整段文字正确更新
        val edited = bodyOf(result).kids().first { it.loc() == "p" && it.textContent == newText }
        assertEquals(newText, edited.textContent)
    }

    /**
     * 字段级（run 级）编辑：只改下划线 run，新内容应继承下划线且字段能跟着变长，
     * 周围普通文字零改动——这是「整段合并」方案永远做不到的（下划线字段无法随内容变长）。
     */
    @Test
    fun wordSourceKeepsUnderlineWhenEditingOnlyThatRun() {
        val original = buildUnderlineMiddleSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val pIdx = gov.blocks.indexOfFirst { it is Block.Para && it.runs.any { r -> r.underline } }
        assertTrue("应能解析到下划线 run", pIdx >= 0)

        val newField = "（已盖章确认）"
        val edits = setOf(EditTarget(pIdx, runIndex = 1)) // 仅编辑下划线 run（第 1 个）
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[pIdx] as Block.Para
            // 与 UI 字段级 applyEdit 一致：按 run 逐一保留样式、仅更新对应 run 文字
            lst[pIdx] = Block.Para(
                p.runs.mapIndexed { k, r -> if (k == 1) r.copy(text = newField) else r },
                p.props
            )
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        // 1) 下划线 run 数量仍为 1（未被拆散、未被摊薄）
        assertEquals(1, countUnderlineRuns(bodyOf(result)))
        // 2) 下划线 run 文字已更新为更长的字段——字段跟着变长、仍带下划线
        assertEquals(listOf(newField), underlineRunTexts(bodyOf(result)))
        // 3) 各 run 文字顺序正确：普通文字零改动，仅字段 run 更新
        val p = bodyOf(result).kids().first { it.loc() == "p" }
        assertEquals(listOf("甲方", newField, "代表："), runTextsOf(p))
        // 4) 整段文字拼接正确
        assertEquals("甲方${newField}代表：", p.textContent)
    }

    /** 字段级编辑：只改普通 run（非下划线），下划线 run 必须完全不受影响 */
    @Test
    fun wordSourcePlainRunEditDoesNotTouchUnderline() {
        val original = buildUnderlineMiddleSample()
        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val pIdx = gov.blocks.indexOfFirst { it is Block.Para && it.runs.any { r -> r.underline } }
        assertTrue("应能解析到下划线 run", pIdx >= 0)

        val edits = setOf(EditTarget(pIdx, runIndex = 0)) // 仅编辑首段普通 run「甲方」
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[pIdx] as Block.Para
            lst[pIdx] = Block.Para(
                p.runs.mapIndexed { k, r -> if (k == 0) r.copy(text = "乙方") else r },
                p.props
            )
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        assertEquals(1, countUnderlineRuns(bodyOf(result)))
        assertEquals(listOf("（盖章）"), underlineRunTexts(bodyOf(result)))
        val p = bodyOf(result).kids().first { it.loc() == "p" }
        assertEquals(listOf("乙方", "（盖章）", "代表："), runTextsOf(p))
    }

    /** 按顺序收集某段落内每个 run 的纯文本（用于验证字段级编辑后各片段文字是否精准） */
    private fun runTextsOf(p: Element): List<String> {        return p.kids().filter { it.loc() == "r" }.map { r ->
            val sb = StringBuilder()
            fun gather(n: Element) {
                for (c in n.kids()) if (c.loc() == "t") sb.append(c.textContent) else gather(c)
            }
            gather(r)
            sb.toString()
        }
    }

    /** 统计 body 中带下划线的 run 数量（存在 w:u 节点即视为有下划线） */
    private fun countUnderlineRuns(body: Element): Int {
        var n = 0
        fun walk(e: Element) {
            if (e.loc() == "r") {
                if (e.child("rPr")?.child("u") != null) n++
            }
            for (c in e.kids()) walk(c)
        }
        walk(body)
        return n
    }

    /** 收集 body 中每个带下划线 run 的纯文本（按出现顺序） */
    private fun underlineRunTexts(body: Element): List<String> {
        val out = mutableListOf<String>()
        fun walk(e: Element) {
            if (e.loc() == "r" && e.child("rPr")?.child("u") != null) {
                val sb = StringBuilder()
                fun gather(n: Element) {
                    for (c in n.kids()) if (c.loc() == "t") sb.append(c.textContent) else gather(c)
                }
                gather(e)
                out += sb.toString()
            }
            for (c in e.kids()) walk(c)
        }
        walk(body)
        return out
    }

    /**
     * 回归：段落内若含有「不含 w:t 的承载型 run」——图片 / 绘图 / 域等
     * （此处以 w:br run 作等价代理，规避图片二进制与命名空间声明问题）——整段文字编辑时
     * 该 run 必须原样保留，绝不可以被写入 w:t（否则会把字塞进图片对象、破坏图片）。
     */
    @Test
    fun wordSourceKeepsDrawingLikeRunWhenParagraphEdited() {
        val base = buildSample()
        val parts = unzip(base)
        val docXml = String(parts["word/document.xml"]!!, java.nio.charset.StandardCharsets.UTF_8)
        // 在第一段末尾注入一个「无 w:t 的承载型 run」（w:br 作为图片/绘图 run 的等价代理）
        val injected = docXml.replaceFirst("</w:p>", "<w:r><w:br/></w:r></w:p>")
        assertTrue("注入应生效", injected != docXml)
        val original = repack(
            parts + ("word/document.xml" to injected.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        )

        val gov = DocxReader.read(original, GovDocSpec.DEFAULT)
        val edits = setOf(EditTarget(0, -1, -1)) // 整段编辑标题段（含承载型 run）
        val newBlocks = gov.blocks.toMutableList().also { lst ->
            val p = lst[0] as Block.Para
            lst[0] = Block.Para(listOf(p.runs.first().copy(text = "新标题文字")), p.props)
        }
        val result = DocxInPlace.edit(original, newBlocks, edits)

        // 1) 承载型 run（w:br）必须原样保留
        val firstP = bodyOf(result).kids().first { it.loc() == "p" }
        val brRun = firstP.kids().firstOrNull { it.loc() == "r" && it.child("br") != null }
        assertNotNull("承载型 run(w:br)应被保留、未被删除", brRun)
        // 2) 承载型 run 内绝不能被写入 w:t（未被污染成文字 run）
        assertNull("承载型 run(w:br)内不应被插入 w:t（未把字塞进图片）", brRun!!.child("t"))
        // 3) 实际可编辑文字正确更新
        assertTrue("整段文字应已更新为新标题文字", firstP.textContent.contains("新标题文字"))
    }
}
