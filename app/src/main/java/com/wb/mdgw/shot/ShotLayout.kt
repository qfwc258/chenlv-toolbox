package com.wb.mdgw.shot

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 长截图切分布局规划器（纯 Kotlin，无 Android 依赖，JVM 可单测）。
 *
 * 参考算法源自用户提供的 Python 脚本：每段高度 = 原图宽度 × 比例（默认 3.6），
 * 两列表格排布。在此之上做了两处工程化改进：
 *
 * 1. **尺寸自适应**：Python 版图片显示宽固定 2.6"，在公文页边距（可用高 8.86"）
 *    下 2.6×3.6=9.36" 的段高会溢出页高。本规划器改为由「页面可用高度 ÷ 比例」
 *    反推显示宽上限，配合窄边距（四边 1cm，可用高 10.91"），保证
 *    `imgW × ratio ≤ maxSegH` **恒成立**（单段永不跨页）且 `2×imgW ≤ usableW`
 *    （两列不溢出）。
 * 2. **末段独立定高**：各段显示高度按其实际像素高独立计算（dispH = imgW×hPx/wPx），
 *    末尾矮段不会被拉伸到名义段高。
 *
 * 单位约定：英寸（布局主单位）→ EMU（docx DrawingML，[com.wb.mdgw.inchToEmu]）/
 * pt（PDF，1 英寸 = 72pt）由消费方换算。
 */
object ShotLayout {

    /** 默认切分比例（每段高度 = 图宽 × 比例），来自参考脚本 */
    const val DEFAULT_RATIO = 3.6

    /** 比例可调范围：1.0（接近正方）~ 5.0（细长段） */
    const val MIN_RATIO = 1.0
    const val MAX_RATIO = 5.0

    // ---------- 页面常量（A4 竖版 + 截图文档专用窄边距） ----------

    /** 截图文档页边距（厘米，四边相同）。公文国标边距太宽，放不下 3.6 比例的段 */
    const val MARGIN_CM = 1.0
    const val PAGE_W_CM = 21.0
    const val PAGE_H_CM = 29.7
    private const val CM_PER_INCH = 2.54

    /** 可用正文宽度（英寸）：19.0cm = 7.4803" */
    val USABLE_W_IN: Double = (PAGE_W_CM - 2 * MARGIN_CM) / CM_PER_INCH

    /** 可用正文高度（英寸）：27.7cm = 10.9094" */
    val USABLE_H_IN: Double = (PAGE_H_CM - 2 * MARGIN_CM) / CM_PER_INCH

    /** 半列宽度（英寸） */
    val COL_W_IN: Double = USABLE_W_IN / 2.0

    /** 段高安全垫（英寸）：行高舍入 + 表格边框 + 表格后空段等杂项的余量 */
    private const val SAFE_IN = 0.15

    /** 图宽相对列宽的舍入垫（英寸），防止 EMU 取整后微超列宽被 Word 缩放 */
    private const val COL_PAD_IN = 0.02

    /** 单段显示高度上限（英寸）：10.9094 - 0.15 = 10.7594" */
    val MAX_SEG_H_IN: Double = USABLE_H_IN - SAFE_IN

    /** 两列排布的列数 */
    const val COLS = 2

    /** JPEG 粗略估算系数：字节 ≈ 像素数 × 0.4 / 1e6 MB（截图类内容经验值） */
    private const val BYTES_PER_MILLION_PX = 0.4 * 1024 * 1024

    /** 一张待切分图（宽高为**显示坐标系**下的像素——EXIF 旋转已由引擎层换算） */
    data class ImageInput(val widthPx: Int, val heightPx: Int)

    /**
     * 一个切分段。
     * @param imageIndex 属于第几张输入图（0 起，按选择顺序）
     * @param segIndex 该图的第几段（0 起，从上到下）
     * @param yPx / hPx / wPx 在**显示坐标系**源图上的取段区域（EXIF 旋转已换算）
     * @param dispHIn 该段在文档中的显示高度（英寸）；显示宽全段统一为 [Plan.imgWIn]
     */
    data class Segment(
        val imageIndex: Int,
        val segIndex: Int,
        val yPx: Int,
        val hPx: Int,
        val wPx: Int,
        val dispHIn: Double
    )

    /**
     * 切分计划。
     * @param imgWIn 每段图显示宽（英寸，全文档统一）
     * @param segments 全部段（按「图序 → 段序」首尾相接的填充顺序）
     * @param rows 两列表格总行数
     * @param pages 预估页数（估算值，供 UI 展示；末段矮段会让实际页数略少）
     * @param estimatedBytes 全部段编码后的字节数粗估（大文件警告用）
     */
    data class Plan(
        val imgWIn: Double,
        val segments: List<Segment>,
        val rows: Int,
        val pages: Int,
        val estimatedBytes: Long
    ) {
        /** 两列行优先：返回第 row 行第 col 列的段（越界 = 空格子） */
        fun cell(row: Int, col: Int): Segment? =
            segments.getOrNull(row * COLS + col)
    }

    /**
     * 生成切分计划。ratio 内部 clamp 到 [MIN_RATIO, MAX_RATIO]；
     * images 为空或含非法尺寸（宽/高 ≤ 0）时返回段数为空的安全计划。
     */
    fun plan(images: List<ImageInput>, ratio: Double): Plan {
        val r = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        val valid = images.filter { it.widthPx > 0 && it.heightPx > 0 }

        // 核心自适应公式：显示宽取「列宽」与「高度反推宽」的较小者。
        // 由此保证名义段高 imgW×ratio ≤ MAX_SEG_H_IN 恒成立（推导见类注释），
        // 因此无需再对单图做比例收缩。
        val imgW = minOf(COL_W_IN - COL_PAD_IN, MAX_SEG_H_IN / r)

        val segments = mutableListOf<Segment>()
        var totalPx = 0L
        for (( imageIndex, img) in valid.withIndex()) {
            // 每段像素高度（参考脚本算法：图宽 × 比例，向上取整）
            val desiredPx = ceil(img.widthPx * r).toInt().coerceAtLeast(1)
            val numParts = ceil(img.heightPx.toDouble() / desiredPx).toInt().coerceAtLeast(1)
            for (seg in 0 until numParts) {
                val y = seg * desiredPx
                val h = minOf(desiredPx, img.heightPx - y)
                if (h <= 0) break
                segments += Segment(
                    imageIndex = imageIndex,
                    segIndex = seg,
                    yPx = y,
                    hPx = h,
                    wPx = img.widthPx,
                    dispHIn = imgW * h.toDouble() / img.widthPx
                )
                totalPx += img.widthPx.toLong() * h
            }
        }

        val rows = ceil(segments.size.toDouble() / COLS).toInt()
        // 每页行数：按名义段高估（末段矮段使实际页数只少不多）
        val rowsPerPage = max(1, floor(USABLE_H_IN / (imgW * r)).toInt())
        val pages = ceil(rows.toDouble() / rowsPerPage).toInt().coerceAtLeast(if (rows == 0) 0 else 1)

        return Plan(
            imgWIn = imgW,
            segments = segments,
            rows = rows,
            pages = pages,
            estimatedBytes = (totalPx * BYTES_PER_MILLION_PX / 1_000_000).toLong()
        )
    }
}
