package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.PptLayoutEngine.blockHeight
import com.wb.mdgw.pptx.PptLayoutEngine.contentHeight

/**
 * 智能自动分页引擎。
 *
 * 分页优先级（锁定）：
 * 1. 手动  ---  强制分页（最高优先）
 * 2. H1 / H2 / H3 标题优先新开一页（专业 PPT 逻辑）
 * 3. keep-with-next：标题与其后首个内容块（表格/列表/段落等）尽量保持同页，避免标题孤立成空白页
 * 4. 孤儿标题保护：当连续多级标题（如 H1+H2）后紧跟放不下的内容块时，
 *    将尾部标题串随内容一起推到新页，避免"两级标题孤零零占一页"
 * 5. 当前页剩余高度放不下下一整块 → 自动新开一页
 * 6. 绝不拆分单个语义块（段落 / 列表 / 引用 / 代码块）
 * 7. 超长单块（自身高度超过整页）→ 保留单页并标记溢出警告
 *
 * autoPaginate = false 时退化为传统模式：仅  ---  分页，其余顺序堆叠。
 */
object MdAutoPaginator {

    fun paginate(
        blocks: List<MdBlock>,
        autoPaginate: Boolean,
        coverTitle: String?
    ): PaginationResult {
        val pages = mutableListOf<SlidePage>()
        val overflow = mutableSetOf<Int>()

        // 封面页（来自 FrontMatter title），独立首页，不参与高度限制
        if (!coverTitle.isNullOrBlank()) {
            pages.add(
                SlidePage(
                    blocks = listOf(MdBlock.TextBlock(BlockType.H1, fragmentsOf(coverTitle))),
                    isCover = true
                )
            )
        }

        val cur = mutableListOf<MdBlock>()
        var usedY = 0

        fun flush() {
            if (cur.isEmpty()) return
            val pageIndex = pages.size            // 即将加入的 0-based 页索引
            if (cur.any { contentHeight(it) > PptLayoutEngine.PAGE_CONTENT_H }) {
                overflow.add(pageIndex + 1)       // 1-based 页码
            }
            pages.add(SlidePage(cur.toList(), titleOf(cur)))
            cur.clear()
            usedY = 0
        }

        // 使用带索引的循环以支持向前预读（keep-with-next）
        for ((idx, block) in blocks.withIndex()) {
            when {
                block is MdBlock.ForcedBreak -> {
                    flush()
                }

                block is MdBlock.TextBlock &&
                        block.type.ordinal <= BlockType.H6.ordinal && autoPaginate -> {
                    val headingH = blockHeight(block)

                    // ── keep-with-next：向前预读下一非强制分页块 ──
                    // 目标：标题与其后首个内容块（表格/列表/段落等）尽量保持同页，
                    // 避免"标题孤立在页面顶部、内容被挤到下一页"的空白页问题。
                    val nextBlock = blocks.drop(idx + 1).firstOrNull { it !is MdBlock.ForcedBreak }
                    val nextSubHeight = nextBlock?.let { nb ->
                        when {
                            // 超长表格：取拆分后第一子表的高度（含表头）
                            nb is MdBlock.TableBlock &&
                                    contentHeight(nb) > PptLayoutEngine.PAGE_CONTENT_H ->
                                PptLayoutEngine.splitLongTable(nb).firstOrNull()?.let { blockHeight(it) } ?: 0
                            // 超长代码块：取拆分后第一子块高度
                            nb is MdBlock.TextBlock && nb.type == BlockType.CODE &&
                                    contentHeight(nb) > PptLayoutEngine.PAGE_CONTENT_H ->
                                PptLayoutEngine.splitLongCode(nb).firstOrNull()?.let { blockHeight(it) } ?: 0
                            else -> blockHeight(nb)
                        }
                    } ?: 0

                    // 原有规则：当前页已有非标题内容时，标题应新开一页
                    val hasContent = cur.any { b ->
                        !(b is MdBlock.TextBlock && b.type.ordinal <= BlockType.H6.ordinal)
                    }
                    // 仅含标题的页若已装满（高度超限）才强制分页
                    val titlesFull = !hasContent && usedY + headingH > PptLayoutEngine.PAGE_CONTENT_H

                    // keep-with-new 判断：若标题+下一块能放入当前页剩余空间，则不分页
                    val canKeepTogether = nextSubHeight > 0 &&
                            usedY + headingH + nextSubHeight <= PptLayoutEngine.PAGE_CONTENT_H

                    if (cur.isNotEmpty() && (hasContent || titlesFull) && !canKeepTogether) flush()
                    cur.add(block)
                    usedY += headingH
                }

                else -> {
                    // 超长代码块（```）按可放下的行数拆分为多个子块 → 自动分页、不截断
                    // 超长表格按数据行拆分为多个子表（每页保留表头）→ 自动分页、不截断
                    // 其余块保持单块不拆（段落/列表/引用均整块移动）。
                    //
                    // 关键：传入实际剩余高度 (PAGE_CONTENT_H - usedY) 而非整页高度，
                    // 确保当前页已有内容（如标题）时，拆分出的第一子块能适配剩余空间，
                    // 配合 keep-with-next 避免"标题孤立空白页"。
                    val availH = (PptLayoutEngine.PAGE_CONTENT_H - usedY).coerceAtLeast(PptLayoutEngine.PAGE_CONTENT_H / 3)
                    val subBlocks = when {
                        block is MdBlock.TextBlock && block.type == BlockType.CODE
                            && PptLayoutEngine.contentHeight(block) > availH ->
                            PptLayoutEngine.splitLongCode(block, availH)
                        block is MdBlock.TableBlock
                            && PptLayoutEngine.contentHeight(block) > availH ->
                            PptLayoutEngine.splitLongTable(block, availH)
                        else -> listOf(block)
                    }
                    for (sb in subBlocks) {
                        val bh = blockHeight(sb)
                        // 剩余高度不足放下一整块 → 新开页（绝不拆单个语义块；代码块已在外部按行拆好）
                        if (autoPaginate && cur.isNotEmpty() && usedY + bh > PptLayoutEngine.PAGE_CONTENT_H) {
                            // ── 孤儿标题保护 ──
                            // 场景：页尾有连续多级标题（如 H1+H2），后面紧跟一个放不下的内容块（表格/列表）。
                            // 若直接 flush，这些标题会孤零零留在当前页，内容被挤到下一页。
                            // 解决：将 cur 尾部连续标题剥离，flush 后带回新页与内容团聚。
                            val orphanHeadings = mutableListOf<MdBlock>()
                            while (cur.lastOrNull() is MdBlock.TextBlock &&
                                (cur.last() as MdBlock.TextBlock).type.ordinal <= BlockType.H6.ordinal
                            ) {
                                orphanHeadings.add(0, cur.removeAt(cur.lastIndex)) // 保持原顺序
                            }
                            val orphanH = orphanHeadings.sumOf { blockHeight(it) }
                            usedY -= orphanH

                            flush()

                            // 剥离的标题放入新页，即将与后续内容块同页
                            cur.addAll(orphanHeadings)
                            usedY += orphanH
                        }
                        cur.add(sb)
                        usedY += bh
                    }
                }
            }
        }
        flush()

        return PaginationResult(pages, overflow)
    }

    /** 取该页首个标题文本作为页标题（用于预览提示）。 */
    private fun titleOf(blocks: List<MdBlock>): String {
        for (b in blocks) {
            if (b is MdBlock.TextBlock && b.type.ordinal <= BlockType.H6.ordinal) {
                return b.text.take(40)
            }
        }
        return ""
    }
}
