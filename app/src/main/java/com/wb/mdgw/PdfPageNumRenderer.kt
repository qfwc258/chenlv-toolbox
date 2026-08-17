package com.wb.mdgw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 基于 Android 原生 PDF 渲染 API 的「回退引擎」。
 *
 * 借鉴自用户提供的 PDF 编辑器源码（com.pdfeditor.engine.PdfEngine）：
 * 先把每一页用 [PdfRenderer] 渲染成位图，再用 Canvas 画出页码，最后用
 * [PdfDocument] 重新写出。优点是**不依赖任何第三方库**，且对"权限加密、结构特殊、
 * PDFBox 解析失败"的 PDF 兼容性极好——这些文件往往能被系统渲染器正常打开。
 *
 * 代价：输出为栅格化页面（文字不可再选中），文件体积通常比原版大。
 * 因此本引擎只作为 [PdfPageNum] 首选方案失败时的**兜底**，绝大多数正常 PDF
 * 仍走 PDFBox 无损方案（矢量/文本原样保留，清晰度 100% 不变）。
 *
 * 借鉴用户提供的 PDF 编辑器源码（com.pdfeditor.engine.PdfEngine.saveDocument）：
 * 把**整页**完整渲染为高分辨率位图、绝不缩小，再加页码。本兜底采用 2.5 倍超采样
 * （≈180dpi）渲染，并把高分辨率位图整体缩放到原始物理尺寸写出——既不会像旧版
 * 那样把内容压到左上角而"缩小"，又能尽量保留原 PDF 的清晰度，同时控制文件体积。
 *
 * 注意：PdfRenderer 对"真正设了打开密码"的 PDF 会抛异常，此时两个引擎都会失败，
 * 由调用方给出明确提示。
 */
object PdfPageNumRenderer {

    /**
     * 超采样倍数（浮点）：以 2.5 倍（≈180dpi）渲染，清晰度与体积的平衡点；
     * 单页位图超过像素上限时按 0.5 步长自动降倍，防止内存溢出。
     */
    private const val BASE_SAMPLE = 2.5f
    private const val MAX_PIXELS = 50_000_000  // 单页位图像素上限，防 OOM

    /**
     * @param input  原始 PDF 字节
     * @param opts   页码选项（与 PdfPageNum.Options 字段一致）
     * @return 加好页码的 PDF 字节
     */
    fun addPageNumbers(input: ByteArray, opts: PdfPageNum.Options, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        val inFile = File(System.getProperty("java.io.tmpdir"), "pdfin_${System.currentTimeMillis()}.tmp")
        val outFile = File(System.getProperty("java.io.tmpdir"), "pdfout_${System.currentTimeMillis()}.tmp")
        try {
            inFile.writeBytes(input)

            val fd = ParcelFileDescriptor.open(inFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val totalPages = renderer.pageCount
            onProgress(0, totalPages)

            val doc = PdfDocument()
            val colorInt = Color.argb(
                255,
                (opts.colorR * 255).toInt(),
                (opts.colorG * 255).toInt(),
                (opts.colorB * 255).toInt()
            )

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val w = page.width      // 原始 points
                val h = page.height

                // 计算超采样倍数（受单页像素上限约束，防止 OOM；按 0.5 步长降倍）
                var sample = BASE_SAMPLE
                while (sample > 1f && (w * sample) * (h * sample) > MAX_PIXELS) sample -= 0.5f

                // 高分辨率位图：用 setScale 让整页内容充满位图，
                // 否则内容只会落在位图左上角 1/sample 区域（旧版"页面缩小"的根因）
                val bmpW = (w * sample).roundToInt()
                val bmpH = (h * sample).roundToInt()
                val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val transform = android.graphics.Matrix().apply {
                    setScale(sample, sample)
                }
                page.render(bmp, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // 字号 / 边距随超采样同步放大（坐标系基于高分辨率位图）
                val fsPx = (opts.fontSize * 2.834645669 * sample).toFloat()
                val marginPx = opts.marginMm * 2.834645669 * sample
                drawNumber(bmp, bmpW.toFloat(), bmpH.toFloat(), fsPx, marginPx, colorInt, opts, i)

                // 输出页面保持原始物理尺寸（point），把高分辨率位图整体缩放到该尺寸：
                // 【既不缩小、又保持高清晰度】（等效 SAMPLE×72dpi，远高于默认的 72dpi 栅格化）
                val info = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                val outPage = doc.startPage(info)
                outPage.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                doc.finishPage(outPage)
                bmp.recycle()
                onProgress(i + 1, totalPages)
            }

            renderer.close()
            fd.close()

            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()
            return outFile.readBytes()
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    private fun drawNumber(
        bmp: Bitmap,
        wPx: Float,
        hPx: Float,
        fsPx: Float,
        marginPx: Double,
        colorInt: Int,
        opts: PdfPageNum.Options,
        idx: Int
    ) {
        // 栅格化回退走 Android Canvas，中文与一字线均可正常绘制
        val text = "${opts.prefix}${opts.startPage + idx}${opts.suffix}"
        val paint = Paint().apply {
            color = colorInt
            textSize = fsPx
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val tw = paint.measureText(text)
        val m = marginPx.toFloat()

        val x = when (opts.position) {
            PdfPageNum.Position.TOP_LEFT, PdfPageNum.Position.BOTTOM_LEFT -> tw / 2f + m
            PdfPageNum.Position.TOP_CENTER, PdfPageNum.Position.BOTTOM_CENTER -> wPx / 2f
            PdfPageNum.Position.TOP_RIGHT, PdfPageNum.Position.BOTTOM_RIGHT -> wPx - tw / 2f - m
        }
        val y = when (opts.position) {
            PdfPageNum.Position.TOP_LEFT, PdfPageNum.Position.TOP_CENTER, PdfPageNum.Position.TOP_RIGHT -> m + fsPx
            PdfPageNum.Position.BOTTOM_LEFT, PdfPageNum.Position.BOTTOM_CENTER, PdfPageNum.Position.BOTTOM_RIGHT -> hPx - m
        }

        Canvas(bmp).drawText(text, x, y, paint)
    }
}
