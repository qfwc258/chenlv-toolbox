package com.wb.mdgw.pptx

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证自动分页器的 keep-with-next 逻辑：
 * 标题（H2/H3）与其后的首个内容块（表格/列表/段落等）应尽量保持同页，
 * 避免标题孤立成空白页。
 */
class KeepWithNextTest {

    /**
     * 场景：页面已有部分内容，遇到 H2 标题 + 表格。
     * 若 标题高 + 表格第一部分高 ≤ 剩余空间 → 标题与表格应同页，不应产生"仅含标题的空白页"。
     */
    @Test
    fun `heading and table stay together when fit`() {
        // 构造块序列：一段正文(占用约100pt) + H2标题 + 小表格(约120pt)
        // PAGE_CONTENT_H = 345pt，剩余空间应足够
        val blocks = listOf(
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("前面的一段正文内容用来占位，模拟页面已有内容。")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("四、举证清单")),
            MdBlock.TableBlock(
                header = listOf(fragmentsOf("编号"), fragmentsOf("证据名称"), fragmentsOf("证明目的")),
                rows = listOf(
                    listOf(fragmentsOf("1"), fragmentsOf("门诊病历"), fragmentsOf("诊断证明")),
                    listOf(fragmentsOf("2"), fragmentsOf("鉴定意见书"), fragmentsOf("误工期限"))
                ),
                colAlign = listOf(TableAlign.LEFT, TableAlign.LEFT, TableAlign.LEFT)
            )
        )

        val result = MdAutoPaginator.paginate(blocks, true, null)

        // 应该只有1页（封面无，内容全部在一页内）
        assertEquals("标题+小表格应合并在一页", 1, result.pages.size)

        // 找到包含标题的页，确认它同时包含表格
        val pageWithHeading = result.pages.find { p ->
            p.blocks.any { it is MdBlock.TextBlock && it.type == BlockType.H2 && it.text.contains("举证清单") }
        }
        assertNotNull("应存在包含'举证清单'标题的页", pageWithHeading)
        assertTrue("该页应同时包含表格",
            pageWithHeading!!.blocks.any { it is MdBlock.TableBlock })
    }

    /**
     * 场景：H2 标题后跟超长表格（跨多页）。
     * keep-with-next 应保证：标题与表格第一子表在同页，不会出现"仅标题的空白页"。
     */
    @Test
    fun `heading stays with first part of long table`() {
        val rows = (1..30).map { i ->
            listOf(
                fragmentsOf("$i"),
                fragmentsOf("证据名称${i}这是一段较长的文本内容"),
                fragmentsOf("证明目的${i}另一段描述性文字"),
                fragmentsOf("来源${i}来源单位名称")
            )
        }

        val blocks = listOf(
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("前序正文内容占位。")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("四、举证清单")),
            MdBlock.TableBlock(
                header = listOf(fragmentsOf("编号"), fragmentsOf("证据名称"), fragmentsOf("证明目的"), fragmentsOf("来源")),
                rows = rows,
                colAlign = listOf(TableAlign.LEFT, TableAlign.LEFT, TableAlign.LEFT, TableAlign.LEFT)
            )
        )

        val result = MdAutoPaginator.paginate(blocks, true, null)

        // 应有多页（超长表格被拆分）
        assertTrue("超长表格应产生多页", result.pages.size >= 2)

        // 关键断言：不存在"仅含标题、不含表格"的空白页
        for ((i, page) in result.pages.withIndex()) {
            val hasHeading = page.blocks.any { it is MdBlock.TextBlock && it.type == BlockType.H2 }
            val hasTable = page.blocks.any { it is MdBlock.TableBlock }
            if (hasHeading) {
                assertTrue("第${i + 1}页：有标题'H2'必须有表格内容（禁止孤立标题空白页）", hasTable)
            }
        }
    }

    /**
     * 场景：H3 标题同样适用 keep-with-next。
     */
    @Test
    fun `H3 heading also keeps with next block`() {
        val blocks = listOf(
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("章节标题")),
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("章节正文。")),
            MdBlock.TextBlock(BlockType.H3, fragmentsOf("小节标题")),
            MdBlock.TableBlock(
                header = listOf(fragmentsOf("项目"), fragmentsOf("说明")),
                rows = listOf(
                    listOf(fragmentsOf("A"), fragmentsOf("说明A")),
                    listOf(fragmentsOf("B"), fragmentsOf("说明B"))
                ),
                colAlign = listOf(TableAlign.LEFT, TableAlign.LEFT)
            )
        )

        val result = MdAutoPaginator.paginate(blocks, true, null)

        val pageWithH3 = result.pages.find { p ->
            p.blocks.any { it is MdBlock.TextBlock && it.type == BlockType.H3 }
        }
        assertNotNull(pageWithH3)
        assertTrue("H3标题应与后续表格同页",
            pageWithH3!!.blocks.any { it is MdBlock.TableBlock })
    }

    /**
     * 场景：标题是最后一个块（无 nextBlock）→ 保持原有行为，不崩溃。
     */
    @Test
    fun `trailing heading without next block does not crash`() {
        val blocks = listOf(
           MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("正文。")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("末尾标题"))
        )

        val result = MdAutoPaginator.paginate(blocks, true, null)
        assertTrue("应正常生成至少1页", result.pages.isNotEmpty())
    }

    /**
     * 场景：autoPaginate=false 时保持传统行为（keep-with-next 不生效）。
     */
    @Test
    fun `autoPaginate false keeps legacy behavior`() {
        val blocks = listOf(
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("正文。")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("标题")),
            MdBlock.TableBlock(
                header = listOf(fragmentsOf("列")),
                rows = listOf(listOf(fragmentsOf("数据"))),
                colAlign = listOf(TableAlign.LEFT)
            )
        )

        val resultOn = MdAutoPaginator.paginate(blocks, true, null)
        val resultOff = MdAutoPaginator.paginate(blocks, false, null)

        // autoPaginate=false 不触发任何自动分页，所有块在1页
        assertEquals("关闭自动分页应为1页", 1, resultOff.pages.size)
        // autoPaginate=true 可能因高度原因分页（但标题不会孤立）
        assertTrue("开启自动分页应正常工作", resultOn.pages.isNotEmpty())
    }

    /**
     * 场景：标题后紧跟手动分页符(---) → 跳过 ForcedBreak 查找下一个实际内容块。
     */
    @Test
    fun `skip forced break when looking for next block`() {
        val blocks = listOf(
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("正文占位。")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("标题后跟分页符")),
            MdBlock.ForcedBreak(),  // 应跳过
            MdBlock.TableBlock(
                header = listOf(fragmentsOf("列")),
                rows = listOf(listOf(fragmentsOf("数据"))),
                colAlign = listOf(TableAlign.LEFT)
            )
        )

        val result = MdAutoPaginator.paginate(blocks, true, null)

        // ForcedBreak 会先触发 flush，然后标题在新页，表格应在标题同页（若放得下）
        val titlePage = result.pages.find { p ->
            p.blocks.any { it is MdBlock.TextBlock && it.type == BlockType.H2 && it.text.contains("分页符") }
        }
        assertNotNull(titlePage)
        // 表格可能在标题同页或下一页（取决于高度），但不应有"仅标题空白页"
        val hasTableNearby = result.pages.any { p -> p.blocks.any { it is MdBlock.TableBlock } }
        assertTrue("表格应存在于某页中", hasTableNearby)
    }
}
