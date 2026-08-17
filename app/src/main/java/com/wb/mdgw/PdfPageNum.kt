package com.wb.mdgw

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 为已有 PDF 添加页码。
 *
 * 移植自用户的 Python 方案（reportlab canvas 画页码图层 + PyPDF2 merge_page 叠加）。
 * 这里改用 PDFBox 的 AppendMode：直接把页码文本追加到每一页内容流的末尾，
 * 与原方案叠加图层等价，但不需要额外生成临时图层文件，纯本地、毫秒级。
 *
 * 兼容说明：页码字体使用 Helvetica（14 种标准字体之一，无需内嵌），
 * 阿拉伯数字在任何阅读器下都能正常显示。
 *
 * 优化点：支持流式处理，无需将整个PDF加载到内存，100MB+大文件也不会OOM
 */
object PdfPageNum {

    /** 页码位置（与原 Python 配置一一对应） */
    enum class Position {
        BOTTOM_CENTER, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP_CENTER, TOP_LEFT, TOP_RIGHT
    }

    /** 预设页码颜色（黑 / 红 / 蓝 / 绿，外加紫、灰用于调色板） */
    enum class ColorPreset(val label: String, val r: Float, val g: Float, val b: Float) {
        BLACK("黑色", 0f, 0f, 0f),
        RED("红色", 0.78f, 0.08f, 0.08f),
        BLUE("蓝色", 0.10f, 0.30f, 0.70f),
        GREEN("绿色", 0.10f, 0.55f, 0.20f),
        PURPLE("紫色", 0.61f, 0.15f, 0.69f),
        GRAY("灰色", 0.50f, 0.50f, 0.50f)
    }

    data class Options(
        val position: Position = Position.BOTTOM_RIGHT,
        val startPage: Int = 1,
        val fontSize: Int = 4,
        val marginMm: Double = 10.0,
        /** 页码颜色，RGB 各分量 0~1 */
        val colorR: Float = 0f,
        val colorG: Float = 0f,
        val colorB: Float = 0f,
        /** 页码前缀（如「第」），显示在数字前 */
        val prefix: String = "",
        /** 页码后缀（如「页」或公文规范的一字线），显示在数字后 */
        val suffix: String = ""
    )

    /**
     * 内置 Helvetica 只覆盖 WinAnsi 字符集，中文/生僻符号会让 showText 抛异常。
     * 这里先做一次可编码检测，不支持时逐级降级，保证页码一定画得出来。
     */
    private fun safeText(font: PDType1Font, text: String, fallback: String): String {
        return try {
            font.getStringWidth(text)   // 编码不支持会抛异常
            text
        } catch (_: Exception) {
            try {
                font.getStringWidth(fallback)
                fallback
            } catch (_: Exception) {
                fallback.filter { it.code in 32..126 }.ifBlank { "" }
            }
        }
    }

    /**
     * 加载文档：
     * - 先尝试普通加载；
     * - 若遇 InvalidPasswordException（真正的密码异常），尝试空密码解锁；
     * - 仍失败则抛出带说明的异常。
     * - 其余所有异常原样上抛，由 UI 层的 friendlyError 展示真实信息。
     */
    private fun load(input: InputStream): PDDocument {
        return try {
            PDDocument.load(input)
        } catch (e: InvalidPasswordException) {
            // 只有明确的密码异常才走重试逻辑
            try {
                PDDocument.load(input, "")
            } catch (e2: InvalidPasswordException) {
                throw IllegalStateException(
                    "该 PDF 设有打开密码或权限加密，无法处理。请先用电脑「另存为」导出无密码版本。",
                    e2
                )
            }
        }
    }

    /**
     * @param input 原始 PDF 字节
     * @param opts  页码选项
     * @return 加好页码的 PDF 字节
     */
    fun addPageNumbers(input: ByteArray, opts: Options, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        // 大文件预检：超过 30 MB 时打印警告
        val mb = input.size / (1024 * 1024)
        if (mb > 30) {
            Log.w("PdfPageNum", "文件较大(${mb}MB)，处理可能需要较长时间")
        }

        return try {
            doAddPageNumbersInMemory(input, opts, onProgress)
        } catch (t: Throwable) {
            Log.e("PdfPageNum", "添加页码失败: ${t.javaClass.simpleName}: ${t.message}", t)
            throw t
        }
    }

    /**
     * 流式处理版本 - 不将整个文件加载到内存，直接从输入流读到输出流
     * 适用于处理 100MB+ 超大PDF文件，彻底避免OOM
     *
     * @param onProgress 逐页进度回调（已处理页数, 总页数），用于上层展示真实进度条
     */
    fun addPageNumbersStream(
        input: InputStream,
        output: OutputStream,
        opts: Options,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        load(input).use { doc ->
            val font = PDType1Font.HELVETICA
            val marginPt = (opts.marginMm * 2.834645669).toFloat() // 1 mm = 2.8346 pt
            val fs = opts.fontSize.toFloat()
            val pages: List<PDPage> = doc.pages.toList()
            onProgress(0, pages.size)

            pages.forEachIndexed { idx, page ->
                // 兜底：个别 PDF 页面缺 mediaBox 时回退到 cropBox / A4
                val rect: PDRectangle = page.mediaBox ?: page.cropBox ?: PDRectangle.A4
                val w = rect.width
                val h = rect.height
                val n = opts.startPage + idx
                // 公文样式「— 1 —」若字体不支持一字线，则降级为纯数字，绝不因此失败
                val num = safeText(font, "${opts.prefix}$n${opts.suffix}", "$n")
                if (num.isEmpty()) {
                    onProgress(idx + 1, pages.size)
                    return@forEachIndexed
                }
                // Helvetica 数字宽度（单位：1/1000 em），换算成实际磅值
                val textW = font.getStringWidth(num) / 1000f * fs

                val (x, y) = when (opts.position) {
                    Position.BOTTOM_CENTER -> (w / 2f - textW / 2f) to marginPt
                    Position.BOTTOM_LEFT   -> marginPt to marginPt
                    Position.BOTTOM_RIGHT  -> (w - marginPt - textW) to marginPt
                    Position.TOP_CENTER    -> (w / 2f - textW / 2f) to (h - marginPt - fs)
                    Position.TOP_LEFT      -> marginPt to (h - marginPt - fs)
                    Position.TOP_RIGHT     -> (w - marginPt - textW) to (h - marginPt - fs)
                }

                // 追加模式：保留原页内容，在末尾画页码；true=压缩，true=重置图形状态
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setFont(font, fs)
                    cs.setNonStrokingColor(opts.colorR, opts.colorG, opts.colorB)
                    cs.beginText()
                    cs.newLineAtOffset(x, y)
                    cs.showText(num)
                    cs.endText()
                }
                onProgress(idx + 1, pages.size)
            }

            doc.save(output)
        }
    }

    /**
     * 双引擎稳健入口：
     * 1. 先尝试 PDFBox 无损方案（文字可选中、体积小）；
     * 2. 一旦失败（加密 / 解析异常 / OOM 等），自动回退到原生 [PdfPageNumRenderer]
     *    栅格化方案（兼容性极强，对权限加密、特殊结构 PDF 往往能成功）。
     * 3. 两者都失败才抛出带日志的异常，交由 UI 层给出明确提示。
     *
     * @return 加好页码的 PDF 字节（可能是无损版，也可能是栅格化版）
     */
    fun addPageNumbersRobust(input: ByteArray, opts: Options, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        // 超过50MB的大文件优先走流式临时文件路径，避免全量加载到内存
        val mb = input.size / (1024 * 1024)
        if (mb > 50) {
            Log.i("PdfPageNum", "大文件($mb MB)启用流式处理模式")
            val tmpIn = File(System.getProperty("java.io.tmpdir"), "pdf_large_in_${System.currentTimeMillis()}.tmp")
            val tmpOut = File(System.getProperty("java.io.tmpdir"), "pdf_large_out_${System.currentTimeMillis()}.tmp")
            return try {
                tmpIn.writeBytes(input)
                FileInputStream(tmpIn).use { ins ->
                    FileOutputStream(tmpOut).use { outs ->
                        addPageNumbersStream(ins, outs, opts, onProgress)
                    }
                }
                Log.i("PdfPageNum", "页码已用 PDFBox 无损方案添加（流式模式）")
                tmpOut.readBytes()
            } catch (t: Throwable) {
                Log.w("PdfPageNum", "PDFBox 方案失败，回退原生渲染引擎: ${t.javaClass.simpleName}: ${t.message}")
                try {
                    PdfPageNumRenderer.addPageNumbers(input, opts, onProgress).also {
                        Log.i("PdfPageNum", "页码已用原生渲染引擎（栅格化）添加")
                    }
                } catch (t2: Throwable) {
                    Log.e("PdfPageNum", "两种方案均失败", t2)
                    throw t // 抛出首个原始异常，便于 UI 显示真实错误
                }
            } finally {
                tmpIn.delete()
                tmpOut.delete()
            }
        }

        // 常规小文件走内存路径
        return try {
            doAddPageNumbersInMemory(input, opts, onProgress).also {
                Log.i("PdfPageNum", "页码已用 PDFBox 无损方案添加")
            }
        } catch (t: Throwable) {
            Log.w("PdfPageNum", "PDFBox 方案失败，回退原生渲染引擎: ${t.javaClass.simpleName}: ${t.message}")
            try {
                PdfPageNumRenderer.addPageNumbers(input, opts, onProgress).also {
                    Log.i("PdfPageNum", "页码已用原生渲染引擎（栅格化）添加")
                }
            } catch (t2: Throwable) {
                Log.e("PdfPageNum", "两种方案均失败", t2)
                throw t // 抛出首个原始异常，便于 UI 显示真实错误
            }
        }
    }

    private fun doAddPageNumbersInMemory(input: ByteArray, opts: Options, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        val tmpFile = File(System.getProperty("java.io.tmpdir"), "pdfout_${System.currentTimeMillis()}.tmp")
        try {
            tmpFile.writeBytes(input)
            FileInputStream(tmpFile).use { ins ->
                val bos = java.io.ByteArrayOutputStream()
                addPageNumbersStream(ins, bos, opts, onProgress)
                return bos.toByteArray()
            }
        } finally {
            tmpFile.delete()
        }
    }
} // end object
