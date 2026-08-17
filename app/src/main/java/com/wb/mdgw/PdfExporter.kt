package com.wb.mdgw

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 用 Android 原生 PdfDocument 把公文模型渲染成 PDF。
 *
 * 严格复用 GovDoc 模型中的 PageSetup（纸张 / 页边距）与 ParaProps（对齐 / 缩进 / 行距），
 * 使 PDF 与 Word 输出在版式上逐项对齐——同一份文档、同一套规范，两种格式看起来一致。
 *
 * 修复清单（v3）：
 *  - 【关键】页面尺寸与页边距改为读取 doc.page，不再硬编码。
 *    此前无论选「诉讼文书」（上3.7/下3.5/左2.8/右2.6cm）还是别的规范，
 *    PDF 一律按 上3/下2.8/左右2.5cm 输出，与 Word 版式对不上。
 *  - 【关键】两端对齐改为按「排版单元」分配间距：连续的 ASCII 字母数字视为一个整体，
 *    不会再把 GB/T、2025 这类词内部拉开。
 *  - 【关键】字体解析修正：此前对任何加粗请求都会误判为“设备存在该字体”，
 *    导致中文衬线/黑体映射从未生效。
 *  - 段前 / 段后间距（spaceBeforePt / spaceAfterPt）与 Word 端对齐。
 *  - 主标题避免孤行：页尾放不下标题+后续内容时提前换页。
 *  - 表格：行高按内容自适应、单元格文字居中、表头加粗并在跨页时重复。
 */
object PdfExporter {

    /** 1 厘米 = 28.3465 磅（72 / 2.54） */
    private const val CM_TO_PT = 28.346456692913385

    /** 行首禁则：这些标点不能出现在一行的开头 */
    private val HEAD_FORBIDDEN =
        "。，、；：？！）］｝〕〉》」』】”’%‰℃·…—～!),.:;?]}¢\"'".toSet()

    /** 行尾禁则：这些符号不能出现在一行的结尾 */
    private val TAIL_FORBIDDEN = "（［｛〔〈《「『【“‘([{<£¥".toSet()

    /** 可与 ASCII 字母数字连成一个排版单元的符号（避免 GB/T、3.5、20% 被拆断） */
    private const val WORD_GLUE = ".-/%:"

    /**
     * 把一行文本切成「排版单元」：
     *  - 连续的 ASCII 字母 / 数字（含 . - / % :）合成一个单元，断行与两端对齐都不拆开它
     *  - 其余（主要是汉字与全角标点）每字一个单元
     */
    private fun splitUnits(text: String): List<String> {
        val units = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code < 128 && c.isLetterOrDigit()) {
                val sb = StringBuilder()
                while (i < text.length && text[i].code < 128 &&
                    (text[i].isLetterOrDigit() || text[i] in WORD_GLUE)
                ) {
                    sb.append(text[i]); i++
                }
                units += sb.toString()
            } else {
                units += c.toString(); i++
            }
        }
        return units
    }

    /**
     * 按公文模型中的字体名解析 Typeface，使 PDF 与 Word 版式字体保持一致。
     *
     * 映射策略（设备若无商用字体，则回退到最接近的系统中文字体）：
     *  - 仿宋_GB2312 / 楷体_GB2312 / 小标宋 / 宋体类 → 衬线体（Noto Serif CJK SC）
     *  - 黑体 / 微软雅黑类 → 无衬线体（Noto Sans CJK SC）
     *
     * 注意：`Typeface.create(未知字体名, BOLD)` 会返回「默认字体的粗体」，
     * 它不等于 Typeface.DEFAULT，所以不能用它来判断字体是否存在——
     * 必须先用 NORMAL 探测字族，存在了再带样式创建。
     */
    fun resolveTypeface(fontName: String, isBold: Boolean): Typeface {
        val style = if (isBold) Typeface.BOLD else Typeface.NORMAL

        /** 探测某个字族在本机是否真实存在 */
        fun exists(name: String): Boolean = try {
            Typeface.create(name, Typeface.NORMAL) != Typeface.DEFAULT
        } catch (_: Exception) {
            false
        }

        // ① 优先使用设备上的精确字体名（部分 ROM 自带方正/中易字体）
        try {
            if (exists(fontName)) return Typeface.create(fontName, style)
        } catch (_: Exception) { }

        // ② 按字体语义映射到系统中文字体
        val isHei = fontName.contains("黑") || fontName.contains("Hei") ||
            fontName.contains("雅黑") || fontName.contains("YaHei") || fontName.contains("SimHei")

        val candidates = if (isHei) {
            listOf("Noto Sans CJK SC", "Noto Sans SC", "source-sans", "sans-serif")
        } else {
            listOf("Noto Serif CJK SC", "Noto Serif SC", "serif")
        }
        for (name in candidates) {
            try {
                if (exists(name)) return Typeface.create(name, style)
            } catch (_: Exception) { }
        }

        // ③ 最后回退：系统 serif / sans + 样式
        val base = if (isHei) Typeface.SANS_SERIF else Typeface.SERIF
        return try {
            Typeface.create(base, style)
        } catch (_: Exception) {
            if (isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /** 把公文模型渲染为 PDF（页码在排版过程中随页绘制，不做二次叠加） */
    fun export(doc: GovDoc): ByteArray {
        val pdf = PdfDocument()

        // —— 纸张与页边距全部来自文档模型（随所选公文规范变化）——
        val p = doc.page
        val pageW = (p.widthCm * CM_TO_PT).toFloat()
        val pageH = (p.heightCm * CM_TO_PT).toFloat()
        val mTop = (p.topCm * CM_TO_PT).toFloat()
        val mBottom = (p.bottomCm * CM_TO_PT).toFloat()
        val mLeft = (p.leftCm * CM_TO_PT).toFloat()
        val mRight = (p.rightCm * CM_TO_PT).toFloat()

        val usableW = (pageW - mLeft - mRight).coerceAtLeast(72f)
        val bottom = (pageH - mBottom).coerceAtLeast(mTop + 72f)

        val pageWi = pageW.roundToInt()
        val pageHi = pageH.roundToInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var pageIndex = 0
        var y = mTop

        // —— 页码：与 Word 页脚共用同一份 PageNumStyle ——
        val pn = doc.pageNumStyle
        val pnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = pn.fontSizePt.toFloat()
            typeface = resolveTypeface(pn.font.ifBlank { doc.bodyFont }, false)
        }

        /**
         * 在当前页底部居中画一个页码数字，位置与 Word 页脚严格对应。
         *
         * Word 的 `w:pgMar/@w:footer` 表示「页脚区顶端距纸张底边的距离」，页脚文字
         * 自该处向下排版，因此文字基线 = (页高 − 页脚距边界) + 字体 ascent。
         */
        fun drawPageNumber(c: Canvas, n: Int) {
            val text = n.toString()
            val footerTop = pageH - (pn.footerDistanceCm * CM_TO_PT).toFloat()
            val fm = pnPaint.fontMetrics
            var baseline = footerTop - fm.ascent
            // 双向保护：既不压住正文，也不溢出纸张下沿
            val minBase = bottom - fm.ascent
            val maxBase = pageH - fm.descent - 2f
            if (baseline < minBase) baseline = minBase
            if (baseline > maxBase) baseline = maxBase

            val x = (pageW - pnPaint.measureText(text)) / 2f
            c.drawText(text, x, baseline, pnPaint)
        }

        fun newPage() {
            page?.let { pdf.finishPage(it) }
            page = pdf.startPage(
                PdfDocument.PageInfo.Builder(pageWi, pageHi, ++pageIndex).create()
            )
            canvas = page!!.canvas
            // 页码在建页时就画好：用独立 Paint，不干扰正文绘制状态
            if (doc.pageNumber) drawPageNumber(canvas!!, pageIndex)
            y = mTop
        }

        fun ensureSpace(h: Float) {
            if (y + h > bottom) newPage()
        }

        /**
         * 中文断行，遵循基本禁则（避头尾）：
         *  - 连续的 ASCII 字母/数字视为整体，不被拆断（如 "2025"、"GB/T"）
         *  - 标点不得出现在行首（。，、；：？！）」等）→ 挤到上一行行尾
         *  - 开引号/开括号不得出现在行尾 → 推到下一行行首
         */
        fun wrap(text: String, firstLineW: Float, restW: Float): List<String> {
            if (text.isEmpty()) return listOf("")

            val units = splitUnits(text)

            val lines = mutableListOf<String>()
            var line = StringBuilder()
            var w = 0f
            fun limit() = if (lines.isEmpty()) firstLineW else restW

            for (t in units) {
                val tw = paint.measureText(t)
                if (w + tw > limit() && line.isNotEmpty()) {
                    // 行尾禁则：开引号/开括号不能留在行尾，推到下一行
                    var carry = ""
                    while (line.isNotEmpty() && line.last() in TAIL_FORBIDDEN) {
                        carry = line.last() + carry
                        line.deleteCharAt(line.length - 1)
                    }
                    lines += line.toString()
                    line = StringBuilder(carry)
                    w = paint.measureText(carry)
                }
                line.append(t)
                w += tw
            }
            if (line.isNotEmpty()) lines += line.toString()

            // 行首禁则：标点若落在行首，挤回上一行末尾（允许该行略微超宽）
            for (li in 1 until lines.size) {
                var cur = lines[li]
                while (cur.length > 1 && cur.first() in HEAD_FORBIDDEN) {
                    lines[li - 1] = lines[li - 1] + cur.first()
                    cur = cur.substring(1)
                }
                lines[li] = cur
            }
            return lines.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        }

        /**
         * 两端对齐绘制一行：把富余宽度均分到「排版单元」之间的缝隙里。
         * 汉字之间会被撑开，但英文单词 / 数字内部保持原样。
         */
        fun drawJustified(line: String, baseX: Float, baseline: Float, targetW: Float) {
            val units = splitUnits(line)
            val lineW = paint.measureText(line)
            if (units.size <= 1 || lineW >= targetW) {
                canvas!!.drawText(line, baseX, baseline, paint)
                return
            }
            val gap = (targetW - lineW) / (units.size - 1).toFloat()
            var cx = baseX
            for (u in units) {
                canvas!!.drawText(u, cx, baseline, paint)
                cx += paint.measureText(u) + gap
            }
        }

        /**
         * 绘制一段文字（可能多行），正确处理：
         *  - 每段首行独立缩进
         *  - 居中 / 右对齐 / 两端对齐
         *  - 行距、段前段后间距
         *  - 主标题避免孤行
         */
        fun drawTextBlock(runs: List<TextRun>, props: ParaProps) {
            val defLineH = (if (props.lineSpacingPt > 0) props.lineSpacingPt else doc.lineSpacingPt).toFloat()

            if (runs.isEmpty()) {
                ensureSpace(defLineH)
                y += defLineH
                return
            }

            val head = runs.first()
            val sizePt = head.sizePt.toFloat()
            // 与 Word 的 <w:spacing w:lineRule="exact"> 严格一致：行高恒等于规范行距，
            // 不因标题字号变大而抬高——否则 PDF 会比 Word 多占纵向空间、分页点错位。
            val lineH = defLineH

            paint.textSize = sizePt
            paint.isFakeBoldText = head.bold
            paint.textSkewX = if (head.italic) -0.25f else 0f
            paint.typeface = resolveTypeface(head.font, head.bold)

            // 段前间距
            if (props.spaceBeforePt > 0) {
                val sb = props.spaceBeforePt.toFloat()
                ensureSpace(sb); y += sb
            }

            // 注意：此处不做「标题孤行提前换页」。
            // Word 未启用对应的 widowControl 行为，PDF 若自作主张提前换页，
            // 会导致两种格式的分页点错开——与「PDF 严格按 Word 排版」的要求相悖。

            val full = runs.joinToString("") { it.text }
            val indent = props.firstLineIndentPt.toFloat()

            // 按 \n 拆分逻辑段，每段各自处理首行缩进
            // 注意：公文靠固定行距控制节奏，段间不额外加空白
            for (raw in full.split("\n")) {
                // 首行可用宽度需扣除缩进，后续行用满宽
                val wrappedLines = wrap(raw, usableW - indent, usableW)

                for (lIdx in wrappedLines.indices) {
                    ensureSpace(lineH)
                    val ln = wrappedLines[lIdx]
                    val fm = paint.fontMetrics
                    val baseline = y - fm.ascent
                    val lineW = paint.measureText(ln)

                    val isFirstLineOfPara = (lIdx == 0)
                    val lead = if (isFirstLineOfPara) indent else 0f

                    when (props.align) {
                        Align.CENTER ->
                            canvas!!.drawText(ln, mLeft + (usableW - lineW) / 2f, baseline, paint)

                        // 右对齐：贴右边距，首行缩进不参与计算
                        Align.RIGHT ->
                            canvas!!.drawText(ln, mLeft + usableW - lineW, baseline, paint)

                        Align.BOTH -> {
                            val isLastLine = (lIdx == wrappedLines.lastIndex)
                            val baseX = mLeft + lead
                            val targetW = usableW - lead
                            // 段落末行按左对齐（公文规范：最后一行不拉伸）
                            if (isLastLine) {
                                canvas!!.drawText(ln, baseX, baseline, paint)
                            } else {
                                drawJustified(ln, baseX, baseline, targetW)
                            }
                        }

                        Align.LEFT ->
                            canvas!!.drawText(ln, mLeft + lead, baseline, paint)
                    }
                    y += lineH
                }
            }

            // 段后间距
            if (props.spaceAfterPt > 0) {
                val sa = props.spaceAfterPt.toFloat()
                ensureSpace(sa); y += sa
            }
        }

        /**
         * 绘制表格：
         *  - 列宽均分，行高按单元格内容自适应
         *  - 单元格文字水平 + 垂直居中（公文表格惯例）
         *  - 首行视为表头：加粗，且跨页时在新页重复
         */
        fun drawTable(rows: List<List<List<TextRun>>>) {
            if (rows.isEmpty()) return

            val colCount = max(rows.maxOfOrNull { it.size } ?: 1, 1)
            val colW = usableW / colCount
            val cellSize = doc.bodySizePt.toFloat()
            val cellLineH = cellSize * 1.35f
            val padX = 4f
            val padY = 4f

            paint.textSkewX = 0f
            paint.textSize = cellSize

            /** 预排版：返回每个单元格的折行结果 */
            fun layoutRow(row: List<List<TextRun>>, bold: Boolean): List<List<String>> {
                paint.isFakeBoldText = bold
                paint.typeface = resolveTypeface(doc.bodyFont, bold)
                return (0 until colCount).map { c ->
                    val txt = row.getOrNull(c)?.joinToString("") { it.text } ?: ""
                    wrap(txt, colW - padX * 2, colW - padX * 2)
                }
            }

            fun rowHeight(cells: List<List<String>>): Float {
                val maxLines = cells.maxOfOrNull { it.size } ?: 1
                return (maxLines * cellLineH + padY * 2).coerceAtLeast(cellSize * 1.9f)
            }

            fun drawRow(cells: List<List<String>>, h: Float, bold: Boolean) {
                paint.isFakeBoldText = bold
                paint.typeface = resolveTypeface(doc.bodyFont, bold)
                var x = mLeft
                for (c in 0 until colCount) {
                    // 边框
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.6f
                    canvas!!.drawRect(x, y, x + colW, y + h, paint)

                    // 文字：水平 + 垂直居中
                    paint.style = Paint.Style.FILL
                    val lines = cells[c]
                    val blockH = lines.size * cellLineH
                    var ty = y + (h - blockH) / 2f - paint.fontMetrics.ascent * 0.82f
                    for (ln in lines) {
                        val tw = paint.measureText(ln)
                        canvas!!.drawText(ln, x + (colW - tw) / 2f, ty, paint)
                        ty += cellLineH
                    }
                    x += colW
                }
                y += h
            }

            // 表头预排版（用于跨页重复）
            val headerCells = layoutRow(rows[0], bold = true)
            val headerH = rowHeight(headerCells)

            ensureSpace(headerH)
            drawRow(headerCells, headerH, bold = true)

            for (r in 1 until rows.size) {
                val cells = layoutRow(rows[r], bold = false)
                val h = rowHeight(cells)
                if (y + h > bottom) {
                    newPage()
                    drawRow(headerCells, headerH, bold = true)   // 新页重复表头
                }
                drawRow(cells, h, bold = false)
            }

            paint.isFakeBoldText = false
            paint.style = Paint.Style.FILL
        }

        newPage()
        for (b in doc.blocks) {
            when (b) {
                is Block.Para -> drawTextBlock(b.runs, b.props)
                is Block.Table -> drawTable(b.rows)
            }
        }
        page?.let { pdf.finishPage(it) }

        val out = java.io.ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }
}
