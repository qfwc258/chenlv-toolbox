package com.wb.mdgw

/**
 * PDF 与屏幕预览坐标系互转。
 *
 * 关键差异：
 *  - PDF 页面坐标系原点在 **左下角**，Y 轴向上；
 *  - 安卓预览 View 坐标系原点在 **左上角**，Y 轴向下。
 * 因此纵向必须做翻转（1 - ratio）。横向按比例即可。
 *
 * 所有方法均为纯函数，便于单元测试与复用。
 */
object PdfCoordinateUtils {

    /**
     * 屏幕预览坐标 → PDF 真实 pt 坐标（左下角原点）。
     *
     * @param screenX 触摸点/印章中心在预览 View 内的 X（px）
     * @param screenY 触摸点/印章中心在预览 View 内的 Y（px）
     * @param viewWidth 预览 View 宽度（px）
     * @param viewHeight 预览 View 高度（px）
     * @param pdfPageWidth PDF 页面宽度（pt）
     * @param pdfPageHeight PDF 页面高度（pt）
     */
    fun screenToPdfPoint(
        screenX: Float,
        screenY: Float,
        viewWidth: Float,
        viewHeight: Float,
        pdfPageWidth: Float,
        pdfPageHeight: Float
    ): Pair<Float, Float> {
        if (viewWidth <= 0f || viewHeight <= 0f) return Pair(0f, 0f)
        val pdfX = screenX / viewWidth * pdfPageWidth
        // 纵向翻转
        val pdfY = pdfPageHeight - (screenY / viewHeight * pdfPageHeight)
        return Pair(pdfX, pdfY)
    }

    /**
     * 屏幕预览坐标 → PDF 真实 pt 坐标（左下角原点），使用「归一化比例」换算。
     *
     * 与 [screenToPdfPoint] 的区别：本函数分母用**预览视图像素尺寸**(viewWidth/viewHeight)，
     * 分子缩放用 **PDF 页面真实 pt 尺寸**(pdfPageWidthPt/pdfPageHeightPt)。二者解耦，
     * 因此即便预览位图来自 [android.graphics.pdf.PdfRenderer]（渲染像素，随设备 density 放大、
     * 不等于 PDF pt），导出坐标仍能精确落到 PDF 用户空间，避免印章被推到页面之外。
     *
     * 用法：传入的 viewWidth/viewHeight 应为预览可见区域的像素尺寸（与印章中心坐标同一坐标系），
     * pdfPageWidthPt/pdfPageHeightPt 应为当前页 MediaBox 的真实 pt 尺寸。
     */
    fun screenToPdfPointNormalized(
        screenX: Float,
        screenY: Float,
        viewWidth: Float,
        viewHeight: Float,
        pdfPageWidthPt: Float,
        pdfPageHeightPt: Float
    ): Pair<Float, Float> {
        if (viewWidth <= 0f || viewHeight <= 0f) return Pair(0f, 0f)
        val nx = screenX / viewWidth
        val ny = 1f - screenY / viewHeight // 纵向翻转：屏幕左上原点 → PDF 左下原点
        return Pair(nx * pdfPageWidthPt, ny * pdfPageHeightPt)
    }

    /** PDF pt 坐标（左下角原点）→ 屏幕预览坐标（左上角原点）。[screenToPdfPoint] 的逆运算。 */
    fun pdfToScreenPoint(
        pdfX: Float,
        pdfY: Float,
        viewWidth: Float,
        viewHeight: Float,
        pdfPageWidth: Float,
        pdfPageHeight: Float
    ): Pair<Float, Float> {
        if (pdfPageWidth <= 0f || pdfPageHeight <= 0f) return Pair(0f, 0f)
        val screenX = pdfX / pdfPageWidth * viewWidth
        val screenY = viewHeight - (pdfY / pdfPageHeight * viewHeight)
        return Pair(screenX, screenY)
    }

    /**
     * 预览视图内的屏幕边长（px）→ PDF pt 边长。
     * 用横向比例换算（预览保持页面比例，横纵比例一致）。
     */
    fun screenSizeToPdfPt(
        screenSize: Float,
        viewWidth: Float,
        pdfPageWidth: Float
    ): Float {
        if (viewWidth <= 0f) return screenSize
        return screenSize / viewWidth * pdfPageWidth
    }
}
