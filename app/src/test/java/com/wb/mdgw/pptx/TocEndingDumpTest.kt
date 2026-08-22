package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.MdAstParser
import com.wb.mdgw.pptx.MdBlock
import com.wb.mdgw.pptx.BlockType
import com.wb.mdgw.pptx.PaginationResult
import com.wb.mdgw.pptx.SlidePage
import com.wb.mdgw.pptx.PptLayoutEngine
import com.wb.mdgw.pptx.PptExportEngine
import com.wb.mdgw.pptx.PptThemes
import com.wb.mdgw.pptx.SlideLayout
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 复现用户场景：TOC 页 + 带波浪的内容页 + 结尾页，enableWave=true，
 * 导出到 /tmp/kotlin_toc_ending.pptx，供 python-pptx / 原始 XML 严格校验。
 */
class TocEndingDumpTest {

    private fun headings(blocks: List<MdBlock>) =
        blocks.filterIsInstance<MdBlock.TextBlock>().filter { it.type in setOf(BlockType.H1, BlockType.H2, BlockType.H3) }
    private fun nonHeadings(blocks: List<MdBlock>) =
        blocks.filter { it !is MdBlock.TextBlock || it.type !in setOf(BlockType.H1, BlockType.H2, BlockType.H3) }

    @Test
    fun exportTocAndEnding() {
        val md = """---
title: 民事诉讼证据规则讲解
author: 张三
---

# 目录

## 1.1 概述
## 1.2 证据种类
## 2.1 证明责任
## 2.2 举证期限

# 第一部分 概述

这是一段正文，包含 **加粗** 与 *斜体* 以及 ~~删除线~~ 文本，用于演示内容页的渲染。

## 证据种类

- 当事人陈述
- 书证
- 物证

> 引用一段话，说明举证的重要性。

```
code line 1
code line 2
```

# 结尾

感谢聆听

汇报人：张三
日期：2026 年 8 月
"""
        val parsed = MdAstParser.parse(md)
        val blocks = parsed.blocks
        val hs = headings(blocks)
        val rest = nonHeadings(blocks)

        // 页0：目录（取首个一级标题 + 其余二级标题）
        val tocBlocks = listOf(hs.first()) + hs.drop(1)
        // 页1：内容（其余块，含正文/列表/引用/代码）
        val contentBlocks = rest
        // 页2：结尾（一个引用/段落作为致谢 + 落款）
        val endingMain = MdBlock.TextBlock(BlockType.QUOTE, fragmentsOf("感谢聆听"))
        val endingMeta = listOf(
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("汇报人：张三")),
            MdBlock.TextBlock(BlockType.PARAGRAPH, fragmentsOf("日期：2026 年 8 月")),
        )
        val endingBlocks = listOf(endingMain) + endingMeta

        val pages = listOf(
            SlidePage(tocBlocks, "目录", isCover = false),
            SlidePage(contentBlocks, "第一部分 概述", isCover = false),
            SlidePage(endingBlocks, "结尾", isCover = false),
        )
        val pag = PaginationResult(pages)
        val theme = PptThemes.fromTone(PptThemes.DEFAULT_TONE)

        val layoutOf: (Int) -> SlideLayout = { idx ->
            when (idx) {
                0 -> SlideLayout.TOC
                2 -> SlideLayout.ENDING
                else -> SlideLayout.STANDARD
            }
        }
        val slides = PptLayoutEngine.layout(pag, theme, layoutOf, enableWave = true)
        val out = FileOutputStream("/tmp/kotlin_toc_ending.pptx")
        PptExportEngine.exportPptx(slides, theme, out)
        out.close()

        // 把每页布局坐标摘要打到 stdout，便于核查 TOC/结尾页是否有异常
        slides.forEachIndexed { i, s ->
            val kinds = s.units.joinToString(",") { it.type.name }
            println("SLIDE[$i] layout=${s.layout} cover=${s.cover} deco.bars=${s.deco?.bars?.size} units=${kinds}")
            s.units.forEach { u ->
                val txt = u.fragments.joinToString("") { f -> f.text }.take(20)
                println("   unit ${u.type.name} x=${u.x} y=${u.y} w=${u.w} h=${u.h} color=${u.color} text='$txt'")
            }
            s.deco?.bars?.forEach { b ->
                println("   bar x=${b.x} y=${b.y} w=${b.w} h=${b.h}")
            }
        }
        println("EXPORTED path=/tmp/kotlin_toc_ending.pptx slides=${slides.size}")
    }
}
