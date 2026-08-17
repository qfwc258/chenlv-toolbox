package com.wb.mdgw.pptx

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/**
 * Markdown AST 全语法解析引擎。
 *
 * 不使用正则匹配正文内容，全部走 commonmark-java 标准 AST，零歧义。
 * 支持：#/##/### 标题、段落、有序/无序列表、引用块、代码块、行内代码、
 * 粗体/斜体/删除线/超链接、--- 强制分页、YAML FrontMatter（封面/主题）。
 */
object MdAstParser {

    private val parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create(), TablesExtension.create()))
        .build()

    /** 解析结果。coverTitle 来自 FrontMatter（封面页标题），可为空。 */
    data class ParseResult(
        val blocks: List<MdBlock>,
        val coverTitle: String?,
        val coverTheme: String?
    )

    fun parse(markdown: String): ParseResult {
        // 1) 剥离开头 YAML FrontMatter
        val (body, fm) = stripFrontMatter(markdown)

        // 2) commonmark AST
        val doc = parser.parse(body)
        val blocks = mutableListOf<MdBlock>()

        var node: Node? = doc.firstChild
        while (node != null) {
            val block = when (node) {
                is Heading -> parseHeading(node)
                is Paragraph -> MdBlock.TextBlock(BlockType.PARAGRAPH, collectInline(node))
                is BulletList -> parseList(BlockType.BULLET_LIST, node)
                is OrderedList -> parseList(BlockType.ORDERED_LIST, node)
                is BlockQuote -> MdBlock.TextBlock(BlockType.QUOTE, collectQuoteInline(node))
                is FencedCodeBlock -> MdBlock.TextBlock(BlockType.CODE, raw = node.literal ?: "")
                is IndentedCodeBlock -> MdBlock.TextBlock(BlockType.CODE, raw = node.literal ?: "")
                is TableBlock -> parseTable(node)
                is ThematicBreak -> MdBlock.ForcedBreak()
                else -> null
            }
            if (block != null) blocks.add(block)
            node = node.next
        }

        return ParseResult(blocks, fm["title"], fm["theme"])
    }

    // ── FrontMatter ──
    /** 返回：body 文本、FrontMatter 键值。 */
    private fun stripFrontMatter(md: String): Pair<String, Map<String, String>> {
        val m = Regex("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?", RegexOption.DOT_MATCHES_ALL).find(md)
        if (m == null) return Pair(md, emptyMap())
        val inner = m.groupValues[1]
        val map = inner.lines().mapNotNull { line ->
            val kv = line.split(":", limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim().trim('"', '\'') else null
        }.toMap()
        return Pair(md.removeRange(m.range), map)
    }

    // ── 标题 ──
    private fun parseHeading(h: Heading): MdBlock {
        val type = when (h.level) {
            1 -> BlockType.H1
            2 -> BlockType.H2
            3 -> BlockType.H3
            4 -> BlockType.H4
            5 -> BlockType.H5
            else -> BlockType.H6
        }
        return MdBlock.TextBlock(type, collectInline(h))
    }

    // ── 列表（有序/无序）──
    private fun parseList(type: BlockType, list: ListBlock): MdBlock {
        val items = mutableListOf<MdBlock.ListItemData>()
        // 有序列表：从 commonmark 节点获取起始编号，用于保留 MD 原文编号
        val orderedStart = if (list is org.commonmark.node.OrderedList) list.startNumber else null
        var itemIndex = 0  // 用于计算当前项的原始编号
        var item = list.firstChild
        while (item is ListItem) {
            var frags = mutableListOf<InlineFragment>()
            var child: Node? = item.firstChild
            while (child != null) {
                if (child is Paragraph) {
                    if (frags.isNotEmpty()) frags.add(InlineFragment(" "))
                    frags.addAll(collectInline(child))
                } else if (child is BlockQuote) {
                    frags.addAll(collectQuoteInline(child))
                } else if (child is org.commonmark.node.BulletList) {
                    // 嵌套无序列表：递归解析，子项 indent+1 扁平化到当前列表
                    items.add(MdBlock.ListItemData(frags.toList(), indent = 0,
                        number = orderedStart?.let { it + itemIndex }))
                    itemIndex++
                    frags = mutableListOf() // 重置，子列表项单独成项
                    items.addAll(parseNestedListItems(child, baseIndent = 1))
                } else if (child is org.commonmark.node.OrderedList) {
                    // 嵌套有序列表：同上
                    items.add(MdBlock.ListItemData(frags.toList(), indent = 0,
                        number = orderedStart?.let { it + itemIndex }))
                    itemIndex++
                    frags = mutableListOf()
                    items.addAll(parseNestedListItems(child, baseIndent = 1))
                }
                child = child.next
            }
            if (frags.isNotEmpty()) {
                items.add(MdBlock.ListItemData(frags, indent = 0,
                    number = orderedStart?.let { it + itemIndex }))
                itemIndex++
            }
            item = item.next
        }
        return MdBlock.ListBlock(type, items)
    }

    /**
     * 递归解析嵌套列表的每一项，返回扁平化的 ListItemData 列表（indent 逐层递增）。
     * 支持任意深度的嵌套（commonmark 允许列表嵌套列表）。
     */
    private fun parseNestedListItems(list: org.commonmark.node.ListBlock, baseIndent: Int): List<MdBlock.ListItemData> {
        val result = mutableListOf<MdBlock.ListItemData>()
        var item = list.firstChild
        while (item is ListItem) {
            var frags = mutableListOf<InlineFragment>()
            var child: Node? = item.firstChild
            while (child != null) {
                if (child is Paragraph) {
                    if (frags.isNotEmpty()) frags.add(InlineFragment(" "))
                    frags.addAll(collectInline(child))
                } else if (child is BlockQuote) {
                    frags.addAll(collectQuoteInline(child))
                } else if (child is org.commonmark.node.BulletList || child is org.commonmark.node.OrderedList) {
                    // 更深层嵌套：先保存当前片段作为独立项，再递归子列表
                    if (frags.isNotEmpty()) {
                        result.add(MdBlock.ListItemData(frags.toList(), indent = baseIndent))
                        frags = mutableListOf()
                    }
                    result.addAll(parseNestedListItems(child as org.commonmark.node.ListBlock, baseIndent + 1))
                }
                child = child.next
            }
            if (frags.isNotEmpty()) {
                result.add(MdBlock.ListItemData(frags, indent = baseIndent))
            }
            item = item.next
        }
        return result
    }

    // ── 行内片段收集 ──
    /**
     * 引用块（BlockQuote）专用片段收集。
     * commonmark 把多行 `>` 解析为 BlockQuote > 单个 Paragraph，
     * 行间用 SoftLineBreak 隔离。此处将 SoftLineBreak 映射为 \n 以实现分行。
     */
    private fun collectQuoteInline(blockQuote: BlockQuote): List<InlineFragment> {
        return collectInline(blockQuote, softBreakAsNewline = true)
    }

    /**
     * 收集节点内所有行内片段。
     * @param softBreakAsNewline true 时 SoftLineBreak 映射为 \n（引用块分行）；false 时映射为空格（默认）。
     */
    private fun collectInline(node: Node, softBreakAsNewline: Boolean = false): List<InlineFragment> {
        val frags = mutableListOf<InlineFragment>()
        var n: Node? = node.firstChild
        while (n != null) {
            when (n) {
                is Text -> frags.add(InlineFragment(n.literal ?: ""))
                is Code -> frags.add(InlineFragment(n.literal ?: "", code = true))
                is StrongEmphasis -> frags.addAll(collectInline(n, softBreakAsNewline).map { it.copy(bold = true) })
                is Emphasis -> frags.addAll(collectInline(n, softBreakAsNewline).map { it.copy(italic = true) })
                is Strikethrough -> frags.addAll(collectInline(n, softBreakAsNewline).map { it.copy(strike = true) })
                is Link -> {
                    val sub = collectInline(n, softBreakAsNewline)
                    val dest = n.destination
                    frags.addAll(sub.map { it.copy(link = dest) })
                }
                is SoftLineBreak -> frags.add(InlineFragment(if (softBreakAsNewline) "\n" else " "))
                is HardLineBreak -> frags.add(InlineFragment("\n"))
                is HtmlInline -> { /* 忽略 HTML 标签 */ }
                // 引用块/表格单元格等内容块会被 commonmark 再包一层 Paragraph，需递归进入取行内片段。
                // 多个 Paragraph 之间用 \n 分隔（否则 BlockQuote 内多行会粘连成一行）
                is Paragraph -> { if (frags.isNotEmpty()) frags.add(InlineFragment("\n")); frags.addAll(collectInline(n, softBreakAsNewline)) }
                is BlockQuote -> { if (frags.isNotEmpty()) frags.add(InlineFragment("\n")); frags.addAll(collectInline(n, softBreakAsNewline)) }
                else -> {
                    val lit = (n as? Text)?.literal
                    if (lit != null) frags.add(InlineFragment(lit))
                }
            }
            n = n.next
        }
        return frags.filter { it.text.isNotEmpty() || it.code }
    }

    // ── 表格（GFM 管道表格）──
    private fun parseTable(table: TableBlock): MdBlock {
        val headerWithCells = mutableListOf<List<Pair<List<InlineFragment>, TableCell>>>()
        val rowsWithCells = mutableListOf<List<Pair<List<InlineFragment>, TableCell>>>()

        var node: Node? = table.firstChild
        while (node != null) {
            when (node) {
                is TableHead -> {
                    val row = node.firstChild as? TableRow
                    if (row != null) headerWithCells.add(rowCells(row))
                }
                is TableBody -> {
                    var r: Node? = node.firstChild
                    while (r is TableRow) {
                        rowsWithCells.add(rowCells(r))
                        r = r.next
                    }
                }
            }
            node = node.next
        }

        val header = headerWithCells.firstOrNull()?.map { it.first } ?: emptyList()
        val rows = rowsWithCells.map { it.map { pair -> pair.first } }
        // 列对齐：优先取表头单元格对齐；无表头则取首行
        val alignSource = if (headerWithCells.isNotEmpty()) headerWithCells.first()
        else rowsWithCells.firstOrNull()
        val colAlign = alignSource?.map { alignOf(it.second) } ?: emptyList()

        return MdBlock.TableBlock(header = header, rows = rows, colAlign = colAlign)
    }

    /** 取一行中所有单元格（片段 + 单元格节点）。 */
    private fun rowCells(row: TableRow): List<Pair<List<InlineFragment>, TableCell>> {
        val out = mutableListOf<Pair<List<InlineFragment>, TableCell>>()
        var c: Node? = row.firstChild
        while (c is TableCell) {
            out.add(collectInline(c) to c)
            c = c.next
        }
        return out
    }

    private fun alignOf(cell: TableCell): TableAlign = when (cell.alignment) {
        TableCell.Alignment.CENTER -> TableAlign.CENTER
        TableCell.Alignment.RIGHT -> TableAlign.RIGHT
        else -> TableAlign.LEFT
    }
}
