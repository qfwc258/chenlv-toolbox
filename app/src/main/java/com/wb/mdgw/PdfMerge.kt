package com.wb.mdgw

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF 合并：把多份 PDF 按顺序合并为一份，可选加页码。
 *
 * 双引擎策略：
 *  - 首选 PDFBox：源文档 importPage 逐页拷贝（无损，保留矢量/文字/图片）；
 *  - PDFBox 失败时回退原生 [PdfRenderer] 栅格化合并（2.5 倍超采样，≈180dpi）。
 */
object PdfMerge {

    private const val TAG = "PdfMerge"
    private const val BASE_SAMPLE = 2.5f
    private const val MAX_PIXELS = 50_000_000

    /** 待合并的单个文件（内存中的字节 + 展示名） */
    data class SourceItem(val name: String, val bytes: ByteArray)

    private fun loadDoc(input: ByteArray): PDDocument {
        return try {
            PDDocument.load(input)
        } catch (e: InvalidPasswordException) {
            try { PDDocument.load(input, "") }
            catch (e2: InvalidPasswordException) {
                throw IllegalStateException(
                    "文件「${e2.message?.take(20) ?: "未知"}」设有打开密码，请先导出无密码版本。", e2
                )
            }
        }
    }

    /**
     * 合并多个 PDF 字节源为一份。
     * @param sources 按顺序排列的待合并文件
     * @param onProgress (已处理文件数, 总文件数)
     * @return 合并后的 PDF 字节
     */
    fun merge(
        sources: List<SourceItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ByteArray {
        require(sources.isNotEmpty()) { "没有可合并的文件" }
        onProgress(0, sources.size)
        return try {
            mergeByPdfBox(sources, onProgress).also {
                Log.i(TAG, "合并已用 PDFBox 无损方案完成，共 ${sources.size} 份")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PDFBox 方案失败，回退原生渲染: ${t.javaClass.simpleName}: ${t.message}")
            mergeByRenderer(sources, onProgress)
        }
    }

    private fun mergeByPdfBox(
        sources: List<SourceItem>,
        onProgress: (done: Int, total: Int) -> Unit
    ): ByteArray {
        val merged = PDDocument()
        try {
            sources.forEachIndexed { i, src ->
                loadDoc(src.bytes).use { doc ->
                    if (doc.isEncrypted) runCatching { doc.setAllSecurityToBeRemoved(true) }
                    for (p in 0 until doc.numberOfPages) {
                        merged.importPage(doc.getPage(p))
                    }
                }
                onProgress(i + 1, sources.size)
            }
            val bos = ByteArrayOutputStream()
            merged.save(bos)
            return bos.toByteArray()
        } finally {
            merged.close()
        }
    }

    private fun mergeByRenderer(
        sources: List<SourceItem>,
        onProgress: (done: Int, total: Int) -> Unit
    ): ByteArray {
        val tmpFiles = sources.map { s ->
            val f = File(System.getProperty("java.io.tmpdir"), "mrgin_${System.currentTimeMillis()}_${s.name.hashCode()}.tmp")
            f.writeBytes(s.bytes); f
        }
        val pdfDoc = PdfDocument()
        try {
            var pageNo = 0
            tmpFiles.forEachIndexed { i, tmpFile ->
                val pfd = ParcelFileDescriptor.open(tmpFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                try {
                    for (p in 0 until renderer.pageCount) {
                        val page = renderer.openPage(p)
                        val w = page.width
                        val h = page.height
                        var sample = BASE_SAMPLE
                        while (sample > 1f && (w * sample) * (h * sample) > MAX_PIXELS) sample -= 0.5f
                        val bmp = Bitmap.createBitmap(
                            (w * sample).toInt(), (h * sample).toInt(), Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(Color.WHITE)
                        val mat = android.graphics.Matrix().apply { setScale(sample, sample) }
                        page.render(bmp, null, mat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()

                        val info = PdfDocument.PageInfo.Builder(w, h, ++pageNo).create()
                        val outPage = pdfDoc.startPage(info)
                        outPage.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                        pdfDoc.finishPage(outPage)
                        bmp.recycle()
                    }
                } finally {
                    renderer.close()
                    pfd.close()
                }
                onProgress(i + 1, sources.size)
            }
            val tmpOut = File(System.getProperty("java.io.tmpdir"), "mrgout_${System.currentTimeMillis()}.tmp")
            tmpOut.outputStream().use { pdfDoc.writeTo(it) }
            return tmpOut.readBytes().also { tmpOut.delete() }
        } finally {
            pdfDoc.close()
            tmpFiles.forEach { it.delete() }
        }
    }
}
