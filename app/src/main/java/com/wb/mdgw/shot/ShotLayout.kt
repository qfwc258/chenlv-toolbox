package com.wb.mdgw.shot

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 长截图 / 图片表格排版规划器（纯 Kotlin，无 Android 依赖，JVM 可单测）。
 *
 * 把一组图片排成「columns × rows」无边框表格（行优先填充）后导出 Word/PDF。
 * 两类用法：
 *
 * 1. **长截图切分**（默认，[ShotConfig.splitLongImage]=true）：每张图按参考脚本算法切分
 *    成多段（每段高度 = 图宽 × 比例），各段作为一格排入表格；
 * 2. **整图排版**（[ShotConfig.splitLongImage]=false）：不切分，每张图整图作为一格，
 *    直接按用户设定的行列网格摆放（适合普通照片 2×2、3×3 等网格化归档）。
 *
 * 两个工程化不变量（无论切分还是整图、几列均成立）：
 * - **单段（格）永不跨页**：显示宽 `imgW` 由「列宽」与「单页可用高 ÷ 该格纵横比」的较小者
 *   反推，配合窄边距（四边 1cm，可用高 10.91"），保证 `imgW × aspect ≤ maxSegH`。
 * - **整列不溢出页宽**：`columns × imgW ≤ usableW`。
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

    /** 默认列数（与原长截图功能一致：两列） */
    const val DEFAULT_COLS = 2

    /** 列数可调范围：1（单列长卷）~ 6（密排网格）。超出由 plan 内部 clamp */
    const val MIN_COLS = 1
    const val MAX_COLS = 6

    // ---------- 页面常量（A4 竖版 + 截图/排版文档专用窄边距） ----------

    /** 排版文档页边距（厘米，四边相同）。公文国标边距太宽，放不下高比例段 */
    const val MARGIN_CM = 1.0
    const val PAGE_W_CM = 21.0
    const val PAGE_H_CM = 29.7
    private const val CM_PER_INCH = 2.54

    /** 可用正文宽度（英寸）：19.0cm = 7.4803" */
    val USABLE_W_IN: Double = (PAGE_W_CM - 2 * MARGIN_CM) / CM_PER_INCH

    /** 可用正文高度（英寸）：27.7cm = 10.9094" */
    val USABLE_H_IN: Double = (PAGE_H_CM - 2 * MARGIN_CM) / CM_PER_INCH

    /** 默认列数（[DEFAULT_COLS]）下的单列宽（英寸）：USABLE_W_IN / DEFAULT_COLS ≈ 3.7402" */
    val COL_W_IN: Double = USABLE_W_IN / DEFAULT_COLS

    /** 段高安全垫（英寸）：行高舍入 + 表格边框 + 表格后空段等杂项的余量 */
    private const val SAFE_IN = 0.15

    /** 图宽相对列宽的舍入垫（英寸），防止 EMU 取整后微超列宽被 Word 缩放 */
    private const val COL_PAD_IN = 0.02

    /** 单段（格）显示高度上限（英寸）：10.9094 - 0.15 = 10.7594" */
    val MAX_SEG_H_IN: Double = USABLE_H_IN - SAFE_IN

    /** JPEG 粗略估算系数：字节 ≈ 像素数 × 0.4 / 1e6 MB（截图类内容经验值） */
    private const val BYTES_PER_MILLION_PX = 0.4 * 1024 * 1024

    /** 一张待排版图（宽高为**显示坐标系**下的像素——EXIF 旋转已由引擎层换算） */
    data class ImageInput(val widthPx: Int, val heightPx: Int)

    /**
     * 排版配置（贯穿 Layout / Engine / UI 三层）。
     *
     * @param splitLongImage 是否切分长图。true=参考脚本切分算法；false=整图作为一格、
     *        按 [columns]×[rows] 网格直接摆放（适合普通照片网格化归档）
     * @param splitRatio 切分比例（仅 [splitLongImage]=true 生效），每段高度 = 图宽 × 比例
     * @param columns 列数（1~6），默认 2
     * @param rows 行数（可选）。null=自动（`ceil(段数/columns)`）；非 null 则取
     *        `max(rows, 自动行数)`——保证选中的全部图片都能放下，不会因设小而被裁掉
     */
    data class ShotConfig(
        val splitLongImage: Boolean = true,
        val splitRatio: Double = DEFAULT_RATIO,
        val columns: Int = DEFAULT_COLS,
        val rows: Int? = null,
        /** 导出 PDF 是否自动加页码（默认开，底部居中） */
        val addPageNumber: Boolean = true,
        /** 页码位置：0=底部居中 1=左下 2=右下 3=顶部居中 4=左上 5=右上 */
        val pageNumberPosition: Int = 0
    )

    /**
     * 一个格子（切分模式下是图的一段，整图模式下是整图）。
     * @param imageIndex 属于第几张输入图（0 起，按选择顺序）
     * @param segIndex 该图的第几段（0 起，从上到下；整图模式恒为 0）
     * @param yPx / hPx / wPx 在**显示坐标系**源图上的取段区域（EXIF 旋转已换算）
     * @param dispHIn 该格在文档中的显示高度（英寸）；显示宽全文档统一为 [Plan.imgWIn]
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
     * 排版计划。
     * @param imgWIn 每格图显示宽（英寸，全文档统一）
     * @param cols 实际列数（已 clamp 到 [MIN_COLS, MAX_COLS]）
     * @param rows 实际行数（已合并用户设定与自动下限）
     * @param segments 全部格子（按「图序 → 段/整图序」首尾相接的填充顺序）
     * @param pages 预估页数（估算值，供 UI 展示；矮行会让实际页数略少）
     * @param estimatedBytes 全部格编码后的字节数粗估（大文件警告用）
     */
    data class Plan(
        val imgWIn: Double,
        val cols: Int,
        val rows: Int,
        val segments: List<Segment>,
        val pages: Int,
        val estimatedBytes: Long
    ) {
        /** 行优先：返回第 row 行第 col 列的格（越界 = 空格子） */
        fun cell(row: Int, col: Int): Segment? =
            segments.getOrNull(row * cols + col)
    }

    /**
     * 生成排版计划。
     *
     * @param images 待排版图（显示坐标系尺寸）
     * @param config 排版配置，默认长截图两列切分
     * @return 段数为空的安全计划（rows/pages=0）当 images 为空或全非法尺寸
     */
    fun plan(images: List<ImageInput>, config: ShotConfig = ShotConfig()): Plan {
        val split = config.splitLongImage
        val ratio = if (split) config.splitRatio.coerceIn(MIN_RATIO, MAX_RATIO) else 1.0
        val cols = config.columns.coerceIn(MIN_COLS, MAX_COLS)
        val colW = USABLE_W_IN / cols

        val valid = images.filter { it.widthPx > 0 && it.heightPx > 0 }

        // 1) 先收集「源像素级纵横比」，用于确定最大纵横比（整图模式据此防止单格超高跨页）
        val raw = mutableListOf<RawSeg>()
        var totalPx = 0L
        for ((imageIndex, img) in valid.withIndex()) {
            if (split) {
                // 参考脚本算法：每段高度 = 图宽 × 比例，向上取整；末段截断到图底
                val desiredPx = ceil(img.widthPx * ratio).toInt().coerceAtLeast(1)
                val numParts = ceil(img.heightPx.toDouble() / desiredPx).toInt().coerceAtLeast(1)
                for (seg in 0 until numParts) {
                    val y = seg * desiredPx
                    val h = minOf(desiredPx, img.heightPx - y)
                    if (h <= 0) break
                    raw += RawSeg(imageIndex, seg, y, h, img.widthPx, h.toDouble() / img.widthPx)
                    totalPx += img.widthPx.toLong() * h
                }
            } else {
                // 整图作为一格：纵横比 = 整图高 / 宽
                raw += RawSeg(imageIndex, 0, 0, img.heightPx, img.widthPx, img.heightPx.toDouble() / img.widthPx)
                totalPx += img.widthPx.toLong() * img.heightPx
            }
        }

        // 2) 最大纵横比：切分模式恒为 ratio（末段 ≤ ratio，取 ratio 即保守约束）；
        //    整图模式取所有图纵横比的最大值，防止最高的整图跨页
        val maxAspect = if (split) ratio else (raw.maxOfOrNull { it.aspect } ?: 1.0)

        // 3) 核心自适应：显示宽取「列宽」与「单页可用高 ÷ 纵横比」的较小者，
        //    由此保证 imgW × aspect ≤ maxSegH（单格不跨页）且 columns × imgW ≤ usableW（列不溢出）
        val imgW = if (split) {
            minOf(colW - COL_PAD_IN, MAX_SEG_H_IN / max(ratio, 1e-4))
        } else {
            minOf(colW - COL_PAD_IN, MAX_SEG_H_IN / max(maxAspect, 1e-4))
        }

        // 4) 由统一显示宽反推每格显示高（末段/矮图按真实像素高，不拉伸）
        val segments = raw.map { r ->
            Segment(
                imageIndex = r.imageIndex,
                segIndex = r.segIndex,
                yPx = r.yPx,
                hPx = r.hPx,
                wPx = r.wPx,
                dispHIn = imgW * r.aspect
            )
        }

        // 5) 行数：自动下限 = ceil(段数/列数)；用户设定则取 max，保证全部图片放下
        val autoRows = ceil(segments.size.toDouble() / cols).toInt()
        val userRows = config.rows?.takeIf { it > 0 }
        val rows = if (userRows != null) max(userRows, autoRows) else autoRows

        // 6) 页数估算（保守近似，与原实现一致）：按「每页可容纳的名义行数」取整。
        //    名义行高 = imgW × maxAspect（切分=imgW×ratio）；实际渲染用真实 dispH 逐行
        //    排版并换页，永不跨页，因此此估值只会偏多、不会少。
        val rowsPerPage = max(1, floor(USABLE_H_IN / max(imgW * maxAspect, 1e-4)).toInt())
        val pages = ceil(rows.toDouble() / rowsPerPage).toInt().coerceAtLeast(if (rows == 0) 0 else 1)

        return Plan(
            imgWIn = imgW,
            cols = cols,
            rows = rows,
            segments = segments,
            pages = pages,
            estimatedBytes = (totalPx * BYTES_PER_MILLION_PX / 1_000_000).toLong()
        )
    }

    /** 便捷转发：与旧调用签名兼容（长截图两列切分） */
    fun plan(images: List<ImageInput>, ratio: Double): Plan =
        plan(images, ShotConfig(splitLongImage = true, splitRatio = ratio))

    /** 源像素级段中间体（aspect = 段高/段宽，未乘显示宽） */
    private data class RawSeg(
        val imageIndex: Int,
        val segIndex: Int,
        val yPx: Int,
        val hPx: Int,
        val wPx: Int,
        val aspect: Double
    )
}
