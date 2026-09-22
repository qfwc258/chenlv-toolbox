package com.wb.mdgw.tableform

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 .docx 中的表格，产出 [TfDoc]。
 *
 * 与 [com.wb.mdgw.DocxReader] 一致采用 namespaceAware=false 的 DOM（标签带 w: 前缀），
 * 但本解析器额外识别 gridSpan / vMerge / 列宽 / 空白格 / 图片，并：
 *  1. 用网格填充算法为每个 tc 分配逻辑列（合并单元格也能对齐）；
 *  2. 为空白格关联同列有字格作为字体样本（回填克隆 rPr）；
 *  3. 推断手机表单模式（卡片 / 清单 / 网格）。
 */
object TableFormParser {

    fun parse(docxBytes: ByteArray): TfDoc {
        val xml = readDocumentXml(docxBytes)
            ?: return TfDoc(emptyList())
        val doc = buildDocument(xml)
        val body = doc.documentElement.getElementsByTagName("w:body").item(0) as Element

        val tables = mutableListOf<TfTable>()
        var tableIndex = 0
        for (child in children(body)) {
            if (child.nodeName == "w:tbl") {
                tables += parseTable(child as Element, tableIndex)
                tableIndex++
            }
        }
        return TfDoc(tables)
    }

    // ---------------- 表单表解析 ----------------

    private fun parseTable(tbl: Element, tableIndex: Int): TfTable {
        // 逻辑列数：优先 tblGrid/gridCol 数量
        val gridColsFromGrid = firstChild(tbl, "w:tblGrid")
            ?.let { children(it).count { c -> c.nodeName == "w:gridCol" } } ?: 0

        // 原始行（DOM 顺序）
        val trs = children(tbl).filter { it.nodeName == "w:tr" }.map { it as Element }

        // 先解析每格的原始信息（不含 logicalCol）
        data class Raw(val domRow: Int, val domCell: Int, val text: String, val blank: Boolean,
                       val colSpan: Int, val vStart: Boolean, val vCont: Boolean,
                       val hasImage: Boolean, val align: TfAlign, val logicalCol: Int = 0)

        val rawRows = ArrayList<List<Raw>>()
        var maxLogical = 0
        for ((r, tr) in trs.withIndex()) {
            val tcs = children(tr).filter { it.nodeName == "w:tc" }.map { it as Element }
            val raws = ArrayList<Raw>()
            for ((c, tc) in tcs.withIndex()) {
                val tcPr = firstChild(tc, "w:tcPr")
                val colSpan = tcPr?.let { firstChild(it, "w:gridSpan") }
                    ?.getAttribute("w:val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val vMerge = tcPr?.let { firstChild(it, "w:vMerge") }
                val vStart = vMerge != null && vMerge.getAttribute("w:val")
                    .let { it == "restart" || it == "start" }
                val vCont = vMerge != null && !vStart
                val paras = children(tc).filter { it.nodeName == "w:p" }.map { it as Element }
                val text = paras.joinToString("\n") { paraText(it) }
                val hasImage = tc.getElementsByTagName("w:drawing").length > 0 ||
                        tc.getElementsByTagName("w:pict").length > 0
                val align = paras.firstOrNull()?.let { paraAlign(it) } ?: TfAlign.LEFT
                val blank = text.trim().isEmpty() && !hasImage
                raws.add(Raw(r, c, text, blank, colSpan, vStart, vCont, hasImage, align))
            }
            rawRows.add(raws)
        }

        // 网格列数：取 tblGrid 与各行展开后宽度的最大值
        var gridCols = gridColsFromGrid
        for (raws in rawRows) {
            val w = raws.sumOf { it.colSpan }
            if (w > gridCols) gridCols = w
        }
        if (gridCols <= 0) gridCols = 1

        // 网格填充：为每个 tc 分配 logicalCol（横向 span 占位；vMerge 续格逐行出现自然对齐）
        val logical = Array(rawRows.size) { IntArray(rawRows[it].size) }
        for (r in rawRows.indices) {
            val occupied = BooleanArray(gridCols)
            var cursor = 0
            for (di in rawRows[r].indices) {
                while (cursor < gridCols && occupied[cursor]) cursor++
                val col = cursor.coerceAtMost(gridCols - 1)
                logical[r][di] = col
                val span = rawRows[r][di].colSpan
                for (k in 0 until span) if (col + k < gridCols) occupied[col + k] = true
                cursor = col + span
            }
            if (cursor - 1 > maxLogical) maxLogical = cursor - 1
        }

        // 构建 TfCell（暂不挂 sample）
        data class Slot(val r: Int, val di: Int, val cell: TfCell)
        val slots = ArrayList<Slot>()
        val rows = ArrayList<TfRow>()
        for (r in rawRows.indices) {
            val cells = ArrayList<TfCell>()
            for (di in rawRows[r].indices) {
                val x = rawRows[r][di]
                val cell = TfCell(
                    domRow = r, domCell = x.domCell, text = x.text, blank = x.blank,
                    logicalCol = logical[r][di], colSpan = x.colSpan,
                    vMergeStart = x.vStart, vMergeCont = x.vCont,
                    hasImage = x.hasImage, align = x.align
                )
                cells.add(cell)
                slots.add(Slot(r, di, cell))
            }
            rows.add(TfRow(r, cells))
        }

        val hasV = rows.any { row -> row.cells.any { it.isVerticalMerge } }

        // 同列字体样本：每个空白格取同逻辑列第一个有字格（优先表头候选行 0）
        fun findSample(logicalCol: Int): TfCellRef? {
            val byRow = slots.filter { !it.cell.blank && !it.cell.vMergeCont &&
                    it.cell.logicalCol == logicalCol }
            val fromHead = byRow.minByOrNull { it.r }
            return fromHead?.let { TfCellRef(it.cell.domRow, it.cell.domCell) }
        }
        val rowsWithSample = rows.map { row ->
            TfRow(row.domRow, row.cells.map { cell ->
                if (cell.blank) cell.copy(sample = findSample(cell.logicalCol)) else cell
            })
        }

        // 推断模式 / 表头 / 序号列
        val infer = inferMode(rowsWithSample, gridCols)

        return TfTable(
            tableIndex = tableIndex,
            gridCols = gridCols,
            rows = rowsWithSample,
            hasVerticalMerge = hasV,
            mode = infer.mode,
            headerRow = infer.headerRow,
            seqLogicalCol = infer.seqCol
        )
    }

    private data class Infer(val mode: TfMode, val headerRow: Int, val seqCol: Int?)

    private fun inferMode(rows: List<TfRow>, gridCols: Int): Infer {
        if (rows.isEmpty()) return Infer(TfMode.GRID, -1, null)

        // 寻找表头行：跳过「整行合并的标题行」（如合并整行的"申请表"），
        // 取第一个有 ≥2 个有字格、非整行合并的行作为表头。
        var headerRow = -1
        for (i in rows.indices) {
            val cells = rows[i].cells.filter { !it.vMergeCont }
            val fullMergeTitle = cells.size == 1 && cells[0].colSpan >= gridCols
            if (fullMergeTitle) continue
            val filled = cells.count { !it.blank }
            if (cells.size >= 2 && filled * 2 >= cells.size) {
                headerRow = i
                break
            }
            break // 首个非标题行就不满足表头特征 → 视为无表头
        }
        if (headerRow == -1) return Infer(TfMode.GRID, -1, null)

        val headCells = rows[headerRow].cells.filter { !it.vMergeCont }
        val dataRows = rows.drop(headerRow + 1)

        val avgBlank = if (dataRows.isEmpty()) 0.0
        else dataRows.flatMap { it.cells }.count { it.blank }.toDouble() / dataRows.size
        // 数据行是否同构（domCell 数一致）
        val domCellCounts = dataRows.map { row -> row.cells.count { !it.vMergeCont } }
        val isomorphic = domCellCounts.distinct().size <= 1

        // 数据行中「恰好一个空白值格」的行占比（清单式特征）
        val oneBlankRows = dataRows.count { r -> r.cells.count { it.blank } == 1 }
        val oneBlankRatio = if (dataRows.isEmpty()) 0.0
        else oneBlankRows.toDouble() / dataRows.size

        // 序号列：表头文字含序号/编号，或数据行逻辑列 0 至少出现一个纯数字且其余为空/数字
        val headLabel0 = headCells.firstOrNull { it.logicalCol == 0 }?.text?.trim().orEmpty()
        val col0Values = dataRows.mapNotNull { r ->
            r.cells.firstOrNull { it.logicalCol == 0 && !it.vMergeCont }?.text?.trim()
        }.filter { it.isNotEmpty() }
        val col0IsNumeric = col0Values.isNotEmpty() && col0Values.all { v ->
            v.all { ch -> ch.isDigit() || ch in "．.、) " }
        }
        val seqCol = when {
            headLabel0.contains("序号") || headLabel0.contains("编号") ||
                headLabel0.contains("序") -> 0
            col0IsNumeric -> 0
            else -> null
        }

        // 1) 卡片式：表头 + 同构数据行，且每行平均有 ≥2 个待填空白
        if (isomorphic && dataRows.isNotEmpty() && avgBlank >= 2.0 && gridCols >= 3) {
            return Infer(TfMode.CARD, headerRow, seqCol)
        }
        // 2) 清单式：2~4 列，多数行恰好一个空白值格
        if (gridCols in 2..4 && oneBlankRatio >= 0.6 && dataRows.isNotEmpty()) {
            return Infer(TfMode.LIST, headerRow, seqCol)
        }
        // 3) 网格兜底
        return Infer(TfMode.GRID, headerRow, seqCol)
    }

    // ---------------- 文本与格式提取 ----------------

    private fun paraText(p: Element): String {
        val sb = StringBuilder()
        val runs = p.getElementsByTagName("w:r")
        for (i in 0 until runs.length) {
            val r = runs.item(i) as Element
            for (ch in children(r)) {
                when (ch.nodeName) {
                    "w:t" -> sb.append(ch.textContent)
                    "w:br", "w:cr" -> sb.append('\n')
                    "w:tab" -> sb.append('\t')
                }
            }
        }
        return sb.toString()
    }

    private fun paraAlign(p: Element): TfAlign {
        val pPr = firstChild(p, "w:pPr") ?: return TfAlign.LEFT
        val jc = firstChild(pPr, "w:jc") ?: return TfAlign.LEFT
        return when (jc.getAttribute("w:val")) {
            "center" -> TfAlign.CENTER
            "right", "end" -> TfAlign.RIGHT
            else -> TfAlign.LEFT
        }
    }

    // ---------------- ZIP / DOM 基础 ----------------

    private fun readDocumentXml(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == "word/document.xml") return zis.readBytes()
                zis.closeEntry()
            }
        }
        return null
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
        builder.setEntityResolver(EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) })
        return builder.parse(ByteArrayInputStream(xml))
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
}
