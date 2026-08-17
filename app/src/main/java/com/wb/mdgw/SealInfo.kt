package com.wb.mdgw

/**
 * 单个印章的盖章参数。
 *
 * 屏幕坐标采用「预览视图内」的像素值（左上角原点），导出时由 [PdfCoordinateUtils]
 * 换算成 PDF pt 坐标（左下角原点），避免把预览缩放比例硬编码进印章本身。
 *
 * 印章以**中心**为锚点记录位置，缩放时只需等比改变 [screenSize]，导出后自动换算成
 * PDF 中的左下角矩形，圆形/方形统一用同一边长，避免被拉成椭圆。
 */
data class SealInfo(
    /** 印章透明 PNG 的本地路径（应用私有目录，避免白底遮挡文字） */
    val sealPath: String,
    /** 盖章页码，0 基 */
    val pageIndex: Int,
    /** 预览视图内印章中心 X（px） */
    var centerScreenX: Float,
    /** 预览视图内印章中心 Y（px） */
    var centerScreenY: Float,
    /** 预览视图内印章边长（px），圆/方同值 */
    var screenSize: Float,
    /** 油墨透明度，0.5~1.0，默认 0.8 模拟真实盖章质感 */
    val alpha: Float = 0.8f
)
