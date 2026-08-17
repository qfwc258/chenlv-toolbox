package com.wb.mdgw

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * 打开 Word 文档后的「原位修改」导出器。
 *
 * 与 [DocxWriter]（从模型整篇重建 Word）不同，这里**直接以原始 .docx 为模板**：
 *  - 解包后保留 zip 内每一个部件（样式 / 页眉页脚 / 文档属性 / 关系等），一个字节都不动；
 *  - 仅对 [edits] 集合中标记的段落 / 表格单元格，在原始 `document.xml` 的 DOM 上
 *    把文字替换为新内容（沿用该位置原有的 run 样式）；
 *  - 未编辑的节点原样保留，因此**表格的列宽、合并单元格、边框、单元格对齐，乃至
 *    整个文档的页面 / 样式 / 页眉页脚全部 100% 不变形**。
 *
 * 这是从架构上根治「Word 往返后表格变形」的唯一彻底办法：
 * 重建必然丢失细节，原位修改则根本不去碰它。
 */
object DocxInPlace {

    /**
     * @param original 打开的源 Word 字节（[GovDoc.originalDocx]）
     * @param blocks   当前（可能已被用户编辑过文字的）块模型
     * @param edits    用户编辑过的位置；仅这些位置会被替换，其余原样
     */
    fun edit(original: ByteArray, blocks: List<Block>, edits: Set<EditTarget>): ByteArray {
        // 没有任何编辑：原样返回源文件，字节级完全一致（最彻底保真）
        if (edits.isEmpty()) return original

        // 1. 解包，保留全部部件
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(original)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                parts[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        val docBytes = parts["word/document.xml"]
            ?: error("不是有效的 Word 文档（缺少 document.xml）")

        // 2. DOM 解析（与 DocxReader 同样的非命名空间模式，前缀 w: 原样保留）
        val dom = parseDom(docBytes)
        val docEl = dom.documentElement
        val body = docEl.childElements().firstOrNull { it.local() == "body" }
            ?: error("文档结构异常：缺少 body")

        // 3. 按 body 中 p / tbl 的顺序与 GovDoc.blocks 对齐（混合序列，单一 bIdx）
        //    GovDoc.blocks 中段落与表格共用一个从 0 开始的索引，因此这里也用统一计数。
        //    每个位置可能有「整段 / 整格」编辑（runIndex<0，向后兼容旧记录）或
        //    「run 级」编辑（runIndex>=0）——后者直接定位原始 run 写入，保留其各自 rPr，
        //    从而让带下划线 / 粗斜体的片段成为独立可编辑单元（字段跟着变长、互不干扰）。
        var bIdx = 0
        for (child in body.childElements()) {
            when (child.local()) {
                "p" -> {
                    val paraTargets = edits.filter { it.blockIndex == bIdx && it.row < 0 }
                    val para = blocks.getOrNull(bIdx)
                    if (paraTargets.isNotEmpty() && para is Block.Para) {
                        if (paraTargets.any { it.runIndex < 0 }) {
                            // 整段编辑：保留既有 distributeRespectingFormat 逻辑（向后兼容）
                            replaceParaText(child, para)
                        } else {
                            // run 级编辑：逐 run 定位原始 run 直接写入，保留各自 rPr
                            val runs = child.childElements().filter { it.local() == "r" }
                            for (t in paraTargets) {
                                val run = runs.getOrNull(t.runIndex)
                                val newText = para.runs.getOrNull(t.runIndex)?.text
                                if (run != null && newText != null) setRunText(run, newText)
                            }
                        }
                    }
                    bIdx++
                }
                "tbl" -> {
                    val table = blocks.getOrNull(bIdx) as? Block.Table
                    var r = 0
                    for (tr in child.childElements()) {
                        if (tr.local() != "tr") continue
                        var c = 0
                        for (tc in tr.childElements()) {
                            if (tc.local() != "tc") continue
                            val cellTargets = edits.filter {
                                it.blockIndex == bIdx && it.row == r && it.col == c
                            }
                            if (table != null && cellTargets.isNotEmpty()) {
                                val cell = table.rows.getOrNull(r)?.getOrNull(c)
                                if (cell != null) {
                                    if (cellTargets.any { it.runIndex < 0 }) {
                                        replaceCellText(tc, cell)
                                    } else {
                                        val firstP = tc.childElements().firstOrNull { it.local() == "p" }
                                        val runs = firstP?.childElements()?.filter { it.local() == "r" }
                                            ?: emptyList()
                                        for (t in cellTargets) {
                                            val run = runs.getOrNull(t.runIndex)
                                            val newText = cell.getOrNull(t.runIndex)?.text
                                            if (run != null && newText != null) setRunText(run, newText)
                                        }
                                    }
                                }
                            }
                            c++
                        }
                        r++
                    }
                    bIdx++
                }
                // sectPr 等其它节点原样不动
            }
        }

        // 4. 用标准 Transformer 序列化（正确处理命名空间 / w: 前缀绑定），其余部件原样写回
        parts["word/document.xml"] = (XML_DECL + serialize(docEl)).toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(1 shl 18)
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 段落：保留原始 run 结构与各自样式（含下划线/粗斜体），仅把整段新文字按原始各 run 长度比例回填 */
    private fun replaceParaText(p: Element, para: Block.Para) {
        val newText = para.runs.joinToString("") { it.text }
        val runs = p.childElements().filter { it.local() == "r" }
        // 只把文字分配到「含 w:t 的文字 run」；图片 / 绘图 run（无 w:t）原样保留，绝不写入文字
        val textRuns = runs.filter { runHasText(it) }
        if (textRuns.isEmpty()) {
            if (newText.isNotEmpty()) appendRunWithText(p, newText, anchorAfter = p.child("w:pPr"))
            return
        }
        replaceRunsText(textRuns, newText)
    }

    /** 单元格：保留首段落内原始 run 结构与各自样式，仅回填新文字；其余段落原样不动 */
    private fun replaceCellText(tc: Element, cell: List<TextRun>) {
        val newText = cell.joinToString("") { it.text }
        val ps = tc.childElements().filter { it.local() == "p" }.toMutableList()
        if (ps.isEmpty()) {
            val p = tc.ownerDocument.createElementNS(NS_W, "w:p")
            appendRunWithText(p, newText, anchorAfter = null)
            tc.appendChild(p)
            return
        }
        val firstP = ps.first()
        val runs = firstP.childElements().filter { it.local() == "r" }
        // 只分配文字给含 w:t 的 run；图片 / 绘图 run 原样保留
        val textRuns = runs.filter { runHasText(it) }
        if (textRuns.isEmpty()) {
            if (newText.isNotEmpty()) appendRunWithText(firstP, newText, anchorAfter = firstP.child("w:pPr"))
        } else {
            replaceRunsText(textRuns, newText)
        }
    }

    /**
     * 把 newText 回填到原始的各个 run，但**带格式（下划线 / 粗体 / 斜体）的 run 保持
     * 其原始字符长度不变**，新文字的增减全部由「无格式 run」吸收。
     *
     * 这样可根除一个瑕疵：若按等比切分，当带下划线的 run 不在首尾且新文字长度变化时，
     * 相邻「本无下划线」的文字会被划进下划线 run 的份额，导致附近文字也被误加上下划线。
     * 改为「格式 run 锁长、无格式 run 吸收余量」后，无下划线的文字绝不会落到下划线 run 中。
     * 每个 run 的 rPr（下划线 / 粗斜体 / 字体 / ​字号）始终原样保留，仅文字内容更新。
     */
    private fun replaceRunsText(runs: List<Element>, newText: String) {
        if (newText.isEmpty()) {
            for (run in runs) clearRunText(run)
            return
        }
        val lens = runs.map { runTextLen(it) }
        val counts = distributeRespectingFormat(lens, runs.map { runHasFormatting(it) }, newText.length)
        var cursor = 0
        for (i in runs.indices) {
            val end = (cursor + counts[i]).coerceAtMost(newText.length)
            setRunText(runs[i], newText.substring(cursor, end))
            cursor = end
        }
        // 极端情形：仍有剩余文字（格式 run 全锁且总长不足），追加到最后一个 run
        if (cursor < newText.length) {
            val tail = newText.substring(cursor)
            val last = runs.last()
            val cur = last.childElements().filter { it.local() == "t" }.firstOrNull()?.textContent ?: ""
            setRunText(last, cur + tail)
        }
    }

    /** 统计一个 run 内所有 w:t 的纯文本长度 */
    private fun runTextLen(run: Element): Int =
        run.childElements().filter { it.local() == "t" }.sumOf { it.textContent.length }

    /** run 是否承载纯文本（含 w:t 子元素）；图片 / 绘图 / 域等承载型 run 返回 false */
    private fun runHasText(run: Element): Boolean =
        run.childElements().any { it.local() == "t" }

    /** 把 text 写入 run：保留其 rPr 与结构，仅替换 w:t 文字（多 t 合并到第一个） */
    private fun setRunText(run: Element, text: String) {
        val texts = run.childElements().filter { it.local() == "t" }
        // 图片 / 绘图 / 域等承载型 run 不含 w:t（只有 w:drawing 等），
        // 绝不为其写入 w:t，否则会把文字塞进图片、破坏对象。
        if (texts.isEmpty()) return
        texts.first().textContent = text
        for (i in 1 until texts.size) run.removeChild(texts[i])
    }

    /** 清空 run 的文字（保留 rPr），用于整段被清空的情形 */
    private fun clearRunText(run: Element) {
        for (t in run.childElements().filter { it.local() == "t" }) run.removeChild(t)
    }

    /**
     * 受格式约束的字符数分配，用于把新文字切分回填到各原始 run：
     *
     * 做法：先按「各 run 在原文中的起始位置」比例，把每个 run 的**起始边界**映射到新文本
     * 中的对应位置（取下界），再对带格式（下划线 / 粗体 / 斜体）的 run **锁定为其原始长度**，
     * 把由此产生的差值推给右侧相邻 run 吸收。
     *
     * 这样能同时保证两件关键的事：
     *  1) 无格式的明文**绝不会**被划进带下划线的 run —— 根除「附近文字被误加下划线」；
     *  2) 带下划线的字段（如「（盖章）」）能完整保住其下划线，边界字符不被无格式 run 吞掉。
     */
    private fun distributeRespectingFormat(lens: List<Int>, locked: List<Boolean>, total: Int): List<Int> {
        val n = lens.size
        if (n == 0) return emptyList()
        val o = lens.sum()
        if (o <= 0) return List(n) { if (it == 0) total else 0 }

        // 各 run 在原文中的累计起始位置（含结尾 O）
        val origStart = mutableListOf(0)
        for (l in lens) origStart += origStart.last() + l

        // 各 run 边界映射到新文本中的起始位置（取下界），pos.size == n + 1
        val pos = MutableList(n + 1) { 0 }
        for (i in 1..n) pos[i] = (origStart[i].toLong() * total / o).toInt() // 向下取整，避免向左吞并
        pos[n] = total

        // 格式 run 锁定原始长度：平移其右边界 pos[i+1]，差值由右侧 run 吸收（多为无格式 run）
        for (i in locked.indices) {
            if (!locked[i]) continue
            val cur = pos[i + 1] - pos[i]
            val diff = lens[i] - cur
            pos[i + 1] = (pos[i + 1] + diff).coerceIn(pos[i], total)
        }

        // 由最终边界算每个 run 的字符数
        return (0 until n).map { pos[it + 1] - pos[it] }
    }

    /** run 是否带「需要锁长」的格式：下划线 / 粗体 / 斜体 */
    private fun runHasFormatting(run: Element): Boolean {
        val rPr = run.child("w:rPr") ?: return false
        val u = rPr.child("w:u")
        if (u != null && underlineOn(u)) return true
        val b = rPr.child("w:b")
        if (b != null && onFlag(b)) return true
        val i = rPr.child("w:i")
        if (i != null && onFlag(i)) return true
        return false
    }

    /** w:u 是否开启下划线（无 val 视为开启；val=none/0/false/off 视为关闭） */
    private fun underlineOn(u: Element): Boolean {
        val v = u.attr("w:val")
        if (v == null) return true
        return v != "none" && v != "0" && v != "false" && v != "off"
    }

    /** w:b / w:i 是否开启（无 val 视为开启） */
    private fun onFlag(n: Element): Boolean {
        val v = n.attr("w:val")
        if (v == null) return true
        return v == "true" || v == "1" || v == "on"
    }

    /** 在段落内追加一个使用默认样式的 run（仅当段落完全没有 run 时兜底） */
    private fun appendRunWithText(p: Element, text: String, anchorAfter: Element?) {
        val doc = p.ownerDocument
        val r = doc.createElementNS(NS_W, "w:r")
        val t = doc.createElementNS(NS_W, "w:t").also {
            it.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            it.textContent = text
        }
        r.appendChild(t)
        if (anchorAfter != null) {
            val next = anchorAfter.nextSibling
            if (next != null) p.insertBefore(r, next) else p.appendChild(r)
        } else {
            p.appendChild(r)
        }
    }

    // ---------- DOM 辅助 ----------
    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    private fun parseDom(bytes: ByteArray) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    /** 用标准 Transformer 序列化，正确处理命名空间与 w: 前缀绑定 */
    private fun serialize(el: Element): String {
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        tf.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8")
        val sw = StringWriter()
        tf.transform(DOMSource(el), StreamResult(sw))
        return sw.toString()
    }

    private fun Element.local(): String {
        val tag = tagName
        val idx = tag.indexOf(':')
        return if (idx >= 0) tag.substring(idx + 1) else tag
    }
    private fun Element.attr(name: String): String? {
        getAttributeNode(name)?.value?.let { return it }
        val local = name.local()
        getAttributeNode(local)?.value?.let { return it }
        return null
    }
    private fun Element.child(tag: String): Element? =
        childElements().firstOrNull { it.local() == tag.local() }
    private fun Element.childElements(): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element) list += n
        }
        return list
    }
    private fun String.local(): String {
        val idx = indexOf(':')
        return if (idx >= 0) substring(idx + 1) else this
    }
}
