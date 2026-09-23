package com.wb.mdgw.tableform

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 通用表格原位回填器：在**原 docx** 上写回，100% 保留边框 / 合并 / 列宽 / 行高 / 页眉页脚。
 *
 * 与 [com.wb.mdgw.DocxInPlace] 同一思路（解包只重写 word/document.xml，其余部件原样复制），
 * 但本回填器额外支持：
 *  1. 规整表（卡片式）以模板数据行克隆 / 删除，从而增删行，格式天然继承；
 *  2. 序号列自动重排；
 *  3. 原空白格写入时克隆同列（表头优先）有字 run 的 rPr，保证字体字号一致。
 *
 * 定位仍用 DOM 顺序 (tableIndex, domRow, domCell)，不依赖逻辑网格。
 */

/** 单格写入（清单 / 网格模式，按 DOM 定位） */
data class CellWrite(val domRow: Int, val domCell: Int, val value: String)

/** 卡片式一条记录：数据列 domCell -> 值（序号列由回填器自动填） */
data class CardRecord(val valuesByDomCell: Map<Int, String>)

/** 卡片式增删行规格 */
data class CardSpec(
    val headerRow: Int,
    val seqDomCell: Int?,
    val records: List<CardRecord>
)

/** 一张表的回填描述 */
data class TableFill(
    val tableIndex: Int,
    val card: CardSpec? = null,
    val writes: List<CellWrite> = emptyList(),
    val headerRow: Int = 0
)

object TableFormFiller {

    private const val DOC_PATH = "word/document.xml"

    fun fill(
        original: ByteArray,
        parsed: TfDoc,
        fills: List<TableFill>,
        /** B 级段落支持：段落 domBodyIdx -> 新文字；null 表示不改 */
        paraWrites: Map<Int, String> = emptyMap()
    ): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        val dirs = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(original)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) dirs.add(e.name) else entries[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        val originalXml = entries[DOC_PATH]
            ?: throw IllegalStateException("缺少 word/document.xml，不是合法 docx")

        val doc = buildDocument(originalXml)
        val body = doc.documentElement.getElementsByTagName("w:body").item(0) as Element

        // B 级段落回填：按 domBodyIdx 定位 body.children 里的 w:p
        if (paraWrites.isNotEmpty()) {
            val bodyChildren = children(body)
            for ((idx, newText) in paraWrites) {
                val child = bodyChildren.getOrNull(idx) ?: continue
                if (child.nodeName != "w:p") continue
                setParaText(doc, child as Element, newText)
            }
        }

        val topTables = children(body).filter { it.nodeName == "w:tbl" }.map { it as Element }

        for (fill in fills) {
            val tbl = topTables.getOrNull(fill.tableIndex)
                ?: throw IllegalStateException("表格 #${fill.tableIndex} 不存在")
            val card = fill.card
            if (card != null) {
                applyCard(tbl, parsed, fill.tableIndex, card)
            } else {
                val trs = trsOf(tbl)
                for (w in fill.writes) {
                    val tc = tcOf(trs, w.domRow, w.domCell) ?: continue
                    val sample = findSampleRPr(tbl, trs, fill.headerRow, parsed, fill.tableIndex,
                        w.domRow, w.domCell)
                    setCellText(doc, tc, w.value, sample)
                }
            }
        }

        fixNamespacePrefix(doc)
        val newXml = serialize(doc)
        entries[DOC_PATH] = newXml

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for (d in dirs) { zos.putNextEntry(ZipEntry(d)); zos.closeEntry() }
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---------------- 卡片式：增删行 + 写值 + 序号重排 ----------------

    private fun applyCard(tbl: Element, parsed: TfDoc, tableIndex: Int, card: CardSpec) {
        val doc = tbl.ownerDocument
        val headerRow = card.headerRow
        var trs = trsOf(tbl)
        val dataCount = (trs.size - (headerRow + 1)).coerceAtLeast(0)
        val need = card.records.size

        // 模板数据行：表头后第一个数据行
        val templateIndex = headerRow + 1
        val templateTr = trs.getOrNull(templateIndex)
            ?: throw IllegalStateException("缺少可克隆的数据行")

        // 行数不足：克隆模板行追加到表尾
        var count = dataCount
        while (count < need) {
            tbl.appendChild(templateTr.cloneNode(true))
            count++
        }
        trs = trsOf(tbl)
        count = trs.size - (headerRow + 1)
        // 行数过多：从表尾删除多余数据行
        while (count > need) {
            val last = trs.getOrNull(headerRow + count)
            if (last != null) tbl.removeChild(last)
            count--
        }
        trs = trsOf(tbl)

        // 数据行 domCell 列集合（取自模板行，规整表各行同构）
        val templateTcs = children(templateTr).filter { it.nodeName == "w:tc" }.map { it as Element }
        val domCells = templateTcs.indices.toList()

        for (i in 0 until need) {
            val tr = trs[headerRow + 1 + i]
            val tcs = children(tr).filter { it.nodeName == "w:tc" }.map { it as Element }
            for (c in domCells) {
                val tc = tcs.getOrNull(c) ?: continue
                val sample = findSampleRPr(tbl, trs, headerRow, parsed, tableIndex,
                    headerRow + 1, c)
                val value = when {
                    card.seqDomCell != null && c == card.seqDomCell -> (i + 1).toString()
                    else -> card.records[i].valuesByDomCell[c].orEmpty()
                }
                setCellText(doc, tc, value, sample)
            }
        }
    }

    // ---------------- 单元格写入（保真） ----------------

    /**
     * 清空该格所有段落内容，只保留第一个段落及其 pPr，再用 [sampleRPr] 新建单个 run 写入。
     * 多行值以 w:br 表示。原格自身有 run 时优先用其自身 rPr。
     */
    private fun setCellText(doc: Document, tc: Element, value: String, sampleRPr: Element?) {
        val paras = children(tc).filter { it.nodeName == "w:p" }.map { it as Element }
        val firstP = paras.firstOrNull() ?: return
        // 删除多余段落
        for (p in paras.drop(1)) tc.removeChild(p)
        // 删除首段内除 pPr 外的所有内容（含 w:ins 包裹的 run）
        children(firstP).filter { it.nodeName != "w:pPr" }
            .forEach { firstP.removeChild(it) }

        if (value.isEmpty()) return

        val ownRPr = firstRunRPr(tc)
        val rPr = ownRPr ?: sampleRPr
        val r = doc.createElement("w:r")
        if (rPr != null) r.appendChild(rPr.cloneNode(true))
        val lines = value.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (i > 0) r.appendChild(doc.createElement("w:br"))
            val t = doc.createElement("w:t")
            t.setAttribute("xml:space", "preserve")
            t.textContent = line
            r.appendChild(t)
        }
        firstP.appendChild(r)
    }

    /** 取该格第一个含文字 run 的 rPr（深克隆） */
    private fun firstRunRPr(tc: Element): Element? {
        val runs = tc.getElementsByTagName("w:r")
        for (i in 0 until runs.length) {
            val r = runs.item(i) as Element
            if (r.getElementsByTagName("w:t").length > 0) {
                val rPr = firstChild(r, "w:rPr")
                if (rPr != null) return rPr.cloneNode(true) as Element
            }
        }
        return null
    }

    /**
     * B 级段落回填：清空段落内容，只保留 pPr（格式），再写入新文字。
     * 若段原本无文字，则不写入（避免制造空白段落）。
     */
    private fun setParaText(doc: Document, para: Element, value: String) {
        // 保留段落内原首个 run 的 rPr，作为字体样本
        val sampleRPr = firstRunRPr(para)
        // 清空所有非 pPr 子元素（run、br、hyperlink、ins 等）
        children(para).filter { it.nodeName != "w:pPr" }
            .forEach { para.removeChild(it) }
        if (value.isEmpty()) return

        val r = doc.createElement("w:r")
        if (sampleRPr != null) r.appendChild(sampleRPr.cloneNode(true))
        // 段落若原本没有 run 格式，退化为无 rPr；Word/WPS 仍可正常显示，字体回落到文档默认
        val lines = value.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (i > 0) r.appendChild(doc.createElement("w:br"))
            val t = doc.createElement("w:t")
            t.setAttribute("xml:space", "preserve")
            t.textContent = line
            r.appendChild(t)
        }
        para.appendChild(r)
    }

    /** 取段落首个含文字 run 的 rPr */
    private fun firstRunRPrInPara(para: Element): Element? = firstRunRPr(para)

    /**
     * 查找空白格的字体样本：优先表头行同 domCell，其次该列任意有字格。
     * 规整表 domCell 即逻辑列，因此按 domCell 对齐即可。
     */
    private fun findSampleRPr(
        tbl: Element, trs: List<Element>, headerRow: Int,
        parsed: TfDoc, tableIndex: Int, domRow: Int, domCell: Int
    ): Element? {
        // 1) 解析模型里记录的同列样本（适用 LIST/GRID）
        val table = parsed.table(tableIndex)
        val cell = table?.allCells?.firstOrNull { it.domRow == domRow && it.domCell == domCell }
        val sampleRef = cell?.sample
        if (sampleRef != null) {
            val sTc = tcOf(trs, sampleRef.domRow, sampleRef.domCell)
            val rPr = sTc?.let { firstRunRPr(it) }
            if (rPr != null) return rPr
        }
        // 2) 表头行同列，再退化为任意行同列
        val order = LinkedHashSet<Int>().apply {
            if (headerRow in trs.indices) add(headerRow)
            addAll(trs.indices)
        }
        for (r in order) {
            val tcs = children(trs[r]).filter { it.nodeName == "w:tc" }.map { it as Element }
            val rPr = tcs.getOrNull(domCell)?.let { firstRunRPr(it) }
            if (rPr != null) return rPr
        }
        return null
    }

    // ---------------- DOM / ZIP 基础 ----------------

    private fun trsOf(tbl: Element): List<Element> =
        children(tbl).filter { it.nodeName == "w:tr" }.map { it as Element }

    private fun tcOf(trs: List<Element>, domRow: Int, domCell: Int): Element? {
        val tr = trs.getOrNull(domRow) ?: return null
        return children(tr).filter { it.nodeName == "w:tc" }.map { it as Element }
            .getOrNull(domCell)
    }

    private fun buildDocument(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        return builder.parse(ByteArrayInputStream(xml))
    }

    private fun serialize(doc: Document): ByteArray {
        val baos = ByteArrayOutputStream()
        val tf = TransformerFactory.newInstance().newTransformer()
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        tf.transform(DOMSource(doc), StreamResult(baos))
        return baos.toByteArray()
    }

    /**
     * JDK 转换器在 namespaceAware=false 时可能输出 ns0: 前缀，统一替换回 Word 要求的 w: 前缀。
     * （与 DocxInPlace 同源，保证 Word/WPS 可打开。）
     */
    private fun fixNamespacePrefix(doc: Document) {
        val root = doc.documentElement
        if (root.lookupNamespaceURI("w") == NS_W) return
        val wrong = root.lookupPrefix(NS_W)
        if (wrong != null && wrong.isNotEmpty()) {
            replacePrefix(doc, wrong, "w")
            root.removeAttribute("xmlns:$wrong")
            if (!root.hasAttribute("xmlns:w")) root.setAttribute("xmlns:w", NS_W)
        }
    }

    private fun replacePrefix(node: Node, old: String, new: String) {
        if (node.nodeType == Node.ELEMENT_NODE) {
            val el = node as Element
            if (el.nodeName.startsWith("$old:")) {
                el.renameNodeIfPossible("${new}:${el.nodeName.substringAfter(':')}")
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) replacePrefix(children.item(i), old, new)
    }

    private fun Element.renameNodeIfPossible(qualifiedName: String) {
        runCatching { ownerDocument.renameNode(this, namespaceURI, qualifiedName) }
    }

    private fun children(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val list: NodeList = el.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
        }
        return out
    }

    private fun firstChild(el: Element, name: String): Element? =
        children(el).firstOrNull { it.nodeName == name }

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
}
