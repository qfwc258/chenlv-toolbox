package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.MdAstParser
import com.wb.mdgw.pptx.MdBlock
import com.wb.mdgw.pptx.PaginationResult
import com.wb.mdgw.pptx.SlidePage
import com.wb.mdgw.pptx.PptLayoutEngine
import com.wb.mdgw.pptx.PptExportEngine
import com.wb.mdgw.pptx.PptThemes
import com.wb.mdgw.pptx.SlideLayout
import org.junit.Test
import java.io.FileOutputStream

/**
 * 回归测试：目录页/结尾页若用户未写内容（空 blocks），此前导出为空白页。
 * 修复后：
 *  - 结尾页默认致谢语「感谢聆听」
 *  - 目录页从全文 H1/H2 标题自动生成条目
 * 验证导出 XML 含文字、非空。
 */
class EmptySpecialPageTest {

    @Test
    fun exportEmptyTocAndEnding() {
        // 构造含 H1/H2 的内容页，供目录页自动抽取
        val contentBlocks = listOf(
            MdBlock.TextBlock(BlockType.H1, fragmentsOf("第一部分 概述")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("1.1 证据种类")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("1.2 证明责任")),
            MdBlock.TextBlock(BlockType.H1, fragmentsOf("第二部分 程序")),
            MdBlock.TextBlock(BlockType.H2, fragmentsOf("2.1 举证期限")),
        )
        val pages = listOf(
            SlidePage(emptyList(), "目录", isCover = false),   // 空 blocks → 触发自动目录
            SlidePage(contentBlocks, "正文", isCover = false),
            SlidePage(emptyList(), "结尾", isCover = false),  // 空 blocks → 触发默认致谢语
        )
        val pag = PaginationResult(pages)
        val theme = PptThemes.ALL[0]

        val layoutOf: (Int) -> SlideLayout = { idx ->
            when (idx) {
                0 -> SlideLayout.TOC
                2 -> SlideLayout.ENDING
                else -> SlideLayout.STANDARD
            }
        }
        val slides = PptLayoutEngine.layout(pag, theme, layoutOf, enableWave = false)
        val out = FileOutputStream("/tmp/kotlin_empty_special.pptx")
        PptExportEngine.exportPptx(slides, theme, out)
        out.close()

        slides.forEachIndexed { i, s ->
            val txt = s.units.joinToString(" | ") { u -> u.fragments.joinToString("") { f -> f.text } }
            println("SLIDE[$i] layout=${s.layout} units=${s.units.size} text='${txt.take(80)}'")
            check(s.units.isNotEmpty()) { "SLIDE[$i] (${s.layout}) 不应为空——修复后特殊页必须含内容" }
        }
        println("EXPORTED path=/tmp/kotlin_empty_special.pptx slides=${slides.size}")
    }
}
