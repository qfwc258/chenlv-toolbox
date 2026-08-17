package com.wb.mdgw

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File

/**
 * PDF 底层盖章核心（基于项目已有的 Apache-2.0 PDFBox）。
 *
 * 两种绘制层级：
 *  - 衬底（[PDPageContentStream.AppendMode.PREPEND]）：印章插入内容流**最前面**，
 *    后绘制的文字/表格覆盖其上，观感如「印油垫在字下」。**仅对文字型 PDF 有效**；
 *  - 覆盖（[PDPageContentStream.AppendMode.APPEND]）：印章追加到内容流**最后**，
 *    绘制在最上层。**图像型 PDF（整页是扫描图）必须用覆盖，否则章会被扫描图完全盖住**。
 *
 * 透明度通过 [PDExtendedGraphicsState] 控制，模拟真实印油的不完全遮盖质感。
 */
object PdfSeal {

    /**
     * 由印章中心（PDF 左下角原点）与边长，算出 [PDPageContentStream.drawImage]
     * 所需的左下角矩形 (x, y, w, h)。圆形/方形统一用同一边长。
     */
    fun sealRect(pdfCenterX: Float, pdfCenterY: Float, sealPtSize: Float): PDRectangle {
        val half = sealPtSize / 2f
        return PDRectangle(
            (pdfCenterX - half).coerceAtLeast(0f),
            (pdfCenterY - half).coerceAtLeast(0f),
            sealPtSize,
            sealPtSize
        )
    }

    /**
     * 把印章图片绘制到指定页内容流。
     * @param alpha 油墨透明度，0.5~1.0
     * @param image 已与原文档关联的印章图片对象
     * @param prepend true=衬底(内容流最前，仅文字型PDF可见)；false=覆盖(内容流最后，图像型PDF必须)
     */
    fun applySeal(
        doc: PDDocument,
        pageIndex: Int,
        rect: PDRectangle,
        alpha: Float,
        image: PDImageXObject,
        prepend: Boolean
    ) {
        require(pageIndex in 0 until doc.numberOfPages) {
            "页码超出范围：请求第 ${pageIndex + 1} 页，文档共 ${doc.numberOfPages} 页"
        }
        val page: PDPage = doc.getPage(pageIndex)
        // 衬底：PREPEND 把章插到内容流最前（字下），仅对文字型 PDF 有效；
        // 覆盖：APPEND 把章追加到最后（最上层），图像型 PDF 必须用它才可见（否则被扫描图盖住）。
        val mode = if (prepend) PDPageContentStream.AppendMode.PREPEND
        else PDPageContentStream.AppendMode.APPEND
        PDPageContentStream(
            doc, page, mode, true, true
        ).use { cs ->
            val gs = PDExtendedGraphicsState()
            gs.setNonStrokingAlphaConstant(alpha)
            gs.setStrokingAlphaConstant(alpha)
            cs.setGraphicsStateParameters(gs)
            cs.drawImage(image, rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
        }
    }

    /**
     * 便捷封装：从文件加载原 PDF，解码印章 Bitmap，在指定页加印章并写出。
     *
     * @param pdfCenterX 印章中心 X（PDF pt，左下角原点）
     * @param pdfCenterY 印章中心 Y（PDF pt，左下角原点）
     * @param sealPtSize 印章边长（PDF pt）
     * @param prepend true=衬底(字下,仅文字型PDF)；false=覆盖(字上,图像型PDF必须)
     */
    fun sealPdfWithBitmap(
        srcPdf: File,
        outPdf: File,
        pageIndex: Int,
        pdfCenterX: Float,
        pdfCenterY: Float,
        sealPtSize: Float,
        alpha: Float,
        bitmap: Bitmap,
        prepend: Boolean = false
    ) {
        PDDocument.load(srcPdf).use { doc ->
            val image = LosslessFactory.createFromImage(doc, bitmap)
            applySeal(doc, pageIndex, sealRect(pdfCenterX, pdfCenterY, sealPtSize), alpha, image, prepend)
            doc.save(outPdf)
        }
    }
}
