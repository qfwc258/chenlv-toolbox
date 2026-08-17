package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfCoordinateUtilsTest {

    @Test
    fun screenToPdf_topLeft() {
        // 预览左上角 (0,0) → PDF 左上角，即 pdfX=0, pdfY=页面高度（左下角原点向上）
        val (x, y) = PdfCoordinateUtils.screenToPdfPoint(
            screenX = 0f, screenY = 0f,
            viewWidth = 100f, viewHeight = 200f,
            pdfPageWidth = 300f, pdfPageHeight = 600f
        )
        assertEquals(0f, x, 1e-5f)
        assertEquals(600f, y, 1e-5f)
    }

    @Test
    fun screenToPdf_bottomCenter() {
        // 预览底部中心 → PDF 底部中心（左下角原点，y=0）
        val (x, y) = PdfCoordinateUtils.screenToPdfPoint(
            screenX = 50f, screenY = 200f,
            viewWidth = 100f, viewHeight = 200f,
            pdfPageWidth = 300f, pdfPageHeight = 600f
        )
        assertEquals(150f, x, 1e-5f)
        assertEquals(0f, y, 1e-5f)
    }

    @Test
    fun roundTrip_isIdentity() {
        val sx = 30f; val sy = 70f
        val vw = 100f; val vh = 200f; val pw = 300f; val ph = 600f
        val (px, py) = PdfCoordinateUtils.screenToPdfPoint(sx, sy, vw, vh, pw, ph)
        val (bx, by) = PdfCoordinateUtils.pdfToScreenPoint(px, py, vw, vh, pw, ph)
        assertEquals(sx, bx, 1e-5f)
        assertEquals(sy, by, 1e-5f)
    }

    @Test
    fun sizeConversion() {
        // 预览 100px 宽对应 PDF 300pt 宽，则 50px → 150pt
        val pt = PdfCoordinateUtils.screenSizeToPdfPt(screenSize = 50f, viewWidth = 100f, pdfPageWidth = 300f)
        assertEquals(150f, pt, 1e-5f)
    }

    @Test
    fun normalized_isDensityIndependent() {
        // 关键回归测试：导出坐标必须用真实 PDF pt 尺寸，与预览渲染像素（density）无关。
        // 模拟 density=2.75 的设备：预览渲染像素远大于真实 pt（A4: 595x842 pt → ~1636x2316 px）。
        // 预览视图像素尺寸 viewW/viewH 与渲染像素一致，但 PDF 真实 pt 为 595x842。
        // 印章置中（screenX=viewW/2, screenY=viewH/2）必须映射到页面中心 (297.5, 421)。
        val viewW = 1636f; val viewH = 2316f      // 渲染像素（density=2.75）
        val pdfPtW = 595f; val pdfPtH = 842f       // 真实 PDF pt（MediaBox）
        val (cx, cy) = PdfCoordinateUtils.screenToPdfPointNormalized(
            screenX = viewW / 2f, screenY = viewH / 2f,
            viewWidth = viewW, viewHeight = viewH,
            pdfPageWidthPt = pdfPtW, pdfPageHeightPt = pdfPtH
        )
        assertEquals(297.5f, cx, 1e-3f)
        assertEquals(421f, cy, 1e-3f)
    }

    @Test
    fun normalized_topLeftCorner() {
        // 预览左上角 → PDF 左上角（左下角原点向上）：x=0, y=页面高度
        val (x, y) = PdfCoordinateUtils.screenToPdfPointNormalized(
            screenX = 0f, screenY = 0f,
            viewWidth = 1636f, viewHeight = 2316f,
            pdfPageWidthPt = 595f, pdfPageHeightPt = 842f
        )
        assertEquals(0f, x, 1e-3f)
        assertEquals(842f, y, 1e-3f)
    }
}
