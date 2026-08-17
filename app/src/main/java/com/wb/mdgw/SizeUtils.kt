package com.wb.mdgw

/**
 * 尺寸换算工具：厘米(cm) ↔ PDF 磅(pt)。
 *
 * 换算基准：1 英寸 = 2.54 厘米 = 72 磅(pt)，这是 PDF 与排版领域的通用约定。
 * 所有印章尺寸最终都换算成 pt，才能在 iText/PDFBox 中以页面左下角为原点精确绘制。
 */
object SizeUtils {
    private const val PT_PER_INCH = 72f
    private const val CM_PER_INCH = 2.54f

    /** 厘米 → PDF 磅 */
    fun cmToPt(cm: Float): Float = cm / CM_PER_INCH * PT_PER_INCH

    /** PDF 磅 → 厘米 */
    fun ptToCm(pt: Float): Float = pt * CM_PER_INCH / PT_PER_INCH

    // ===== 预设印章规格（圆/方直径或边长，单位：厘米）=====
    const val COMPANY_SEAL_CM = 4.0f      // 公章（圆形，默认）
    const val FINANCE_SEAL_CM = 3.8f      // 财务章（圆形）
    const val PERSON_SEAL_CM = 1.6f       // 法人章（方形）

    /** 预设规格附带的中文标签与厘米值，供 UI 单选使用 */
    enum class Preset(val label: String, val cm: Float) {
        COMPANY("公章", COMPANY_SEAL_CM),
        FINANCE("财务章", FINANCE_SEAL_CM),
        PERSON("法人章", PERSON_SEAL_CM)
    }

    /** 公章尺寸（pt），圆形/方形统一用同一边长，避免椭圆 */
    val companyPt: Float = cmToPt(COMPANY_SEAL_CM)
    val financePt: Float = cmToPt(FINANCE_SEAL_CM)
    val personPt: Float = cmToPt(PERSON_SEAL_CM)

    /** 拖拽/缩放时允许的最小、最大边长（pt），防止印章过小或过大 */
    val MIN_SEAL_PT: Float = cmToPt(1.0f)
    val MAX_SEAL_PT: Float = cmToPt(6.0f)
}
