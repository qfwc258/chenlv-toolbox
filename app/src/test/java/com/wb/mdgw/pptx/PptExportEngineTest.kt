package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.MdAstParser
import com.wb.mdgw.pptx.MdAutoPaginator
import com.wb.mdgw.pptx.PptLayoutEngine
import com.wb.mdgw.pptx.PptExportEngine
import com.wb.mdgw.pptx.PptThemes
import org.junit.Test
import java.io.FileOutputStream

/**
 * 用真实 MD→PPTX 全管线（解析→分页→布局→导出）生成 pptx，
 * 写出到 /tmp/kotlin_test.pptx，供 python-pptx 严格校验 OOXML 合法性。
 */
class PptExportEngineTest {

    @Test
    fun exportSampleAndDump() {
        val md = """---
title: 民事答辩状
---
# 一级标题示例

这是一段正文，包含 **加粗** 与 *斜体* 以及 ~~删除线~~ 文本。

## 二级标题

- 列表项一
- 列表项二

1. 有序一
2. 有序二

> 引用一段话，说明防溢出与统一版式的重要性。

```
code line 1
code line 2
```
"""
        val parsed = MdAstParser.parse(md)
        val pag = MdAutoPaginator.paginate(parsed.blocks, true, parsed.coverTitle)
        val theme = PptThemes.ALL[0]
        val slides = PptLayoutEngine.layout(pag, theme, { SlideLayout.STANDARD })
        val out = FileOutputStream("/tmp/kotlin_test.pptx")
        PptExportEngine.exportPptx(slides, theme, out)
        out.close()
        println("PPTX_EXPORTED slides=${slides.size} overflowPages=${pag.overflowPages.size}")
    }
}
