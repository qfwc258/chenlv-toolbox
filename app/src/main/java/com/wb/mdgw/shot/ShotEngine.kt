package com.wb.mdgw.shot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.wb.mdgw.DocxWriter
import com.wb.mdgw.FileUtils
import com.wb.mdgw.PageSetup
import com.wb.mdgw.inchToEmu
import java.io.ByteArrayOutputStream

/**
 * 长截图切分引擎：选图 → [probe] 探尺寸 → [process] 切分并生成 docx + pdf。
 *
 * 内存策略：**BitmapRegionDecoder 逐段解码**，全程不整图载入——峰值内存 ≈ 单段
 * 位图（如 1080×3888 ARGB ≈ 16MB），与长图总高无关；每段「画 PDF + 压缩进 docx」
 * 完成后立即 recycle。
 *
 * EXIF：相册照片可能横图竖存（orientation 90/180/270）。probe 阶段把宽高换算成
 * 「显示坐标系」，切分时把显示坐标的段矩形映射回存储坐标再解码，解码后按需旋转。
 * 手机长截图通常无 EXIF 旋转，走 0° 直通路径。
 *
 * 排版：Word 侧为无边框两列表格（[DocxWriter.addImageTable]），PDF 侧为双列自绘
 * （android.graphics.pdf.PdfDocument），两者**行优先顺序一致**，阅读顺序相同。
 */
object ShotEngine {

    /** 一张已探测的长截图。widthPx/heightPx 为**显示坐标系**尺寸（EXIF 已换算） */
    data class ImageRef(
        val uri: Uri,
        val displayName: String,
        val widthPx: Int,
        val heightPx: Int
    )

    /** 处理产物：两种格式的字节 + 布局计划（页数等元信息供 UI 展示） */
    data class Result(
        val docxBytes: ByteArray,
        val pdfBytes: ByteArray,
        val plan: ShotLayout.Plan
    )

    /** JPEG 压缩质量：截图类内容 88 在体积与清晰度间平衡良好 */
    private const val JPEG_QUALITY = 88

    /** 一个段在 PDF 页面上的落位（第一遍纯计算的产物） */
    private class Slot(
        val seg: ShotLayout.Segment,
        val rect: RectF,        // PDF 页面坐标（pt）
        val rowIdx: Int,
        val colIdx: Int,
        val pageNo: Int         // 1 起；用于第二遍的换页时机
    )

    /**
     * 探测图片尺寸（不分配像素内存）。无法解码（HEIF 特例/GIF/损坏文件）返回 null，
     * 由 UI 层剔除并提示。
     */
    fun probe(context: Context, uri: Uri): ImageRef? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val rotation = readRotation(context, uri)
        // 显示坐标系宽高：90/270 时互换
        val w = if (rotation == 90 || rotation == 270) opts.outHeight else opts.outWidth
        val h = if (rotation == 90 || rotation == 270) opts.outWidth else opts.outHeight

        ImageRef(
            uri = uri,
            displayName = FileUtils.displayName(context, uri).ifBlank { "截图" },
            widthPx = w,
            heightPx = h
        )
    }.getOrNull()

    /** 读取 EXIF 旋转角，归一为 0/90/180/270；读取失败按无旋转处理 */
    private fun readRotation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            when (ExifInterface(ins).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    /**
     * 把显示坐标系的段矩形映射回存储坐标系。
     *
     * 推导（以 90° 为例，显示 = 存储顺时针转 90°）：
     * 存储像素 (xs,ys) → 显示 (Hs-1-ys, xs)；反解得 xs = yd、ys = Wd-1-xd。
     * 显示段 [0,Wd)×[y, y+h) → 存储矩形 (y, 0, y+h, storageH)（storageH = 显示宽）。
     */
    private fun mapRegion(seg: ShotLayout.Segment, displayH: Int, storageH: Int, rotation: Int): Rect {
        return when (rotation) {
            90 -> Rect(seg.yPx, 0, seg.yPx + seg.hPx, storageH)
            180 -> Rect(0, displayH - seg.yPx - seg.hPx, seg.wPx, displayH - seg.yPx)
            270 -> Rect(displayH - seg.yPx - seg.hPx, 0, displayH - seg.yPx, storageH)
            else -> Rect(0, seg.yPx, seg.wPx, seg.yPx + seg.hPx)
        }
    }

    /** 解码出的段位图按 EXIF 旋回显示方向（0° 时原样返回，不产生新位图） */
    private fun rotateIfNeeded(bmp: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out != bmp) bmp.recycle()
        return out
    }

    /**
     * 全流程：切分 + 组装 docx（两列表格）+ 绘制 PDF（双列排布）。
     *
     * 实现为两遍：
     * 1. **纯计算**：行高 / 换页 / 每段 PDF 落位矩形（无位图分配）；
     * 2. **逐图解码**：每张图只开一次 BitmapRegionDecoder，其各段解码 → 画 PDF →
     *    压缩登记进 docx → recycle，峰值内存与长图总高无关。
     *
     * @param onProgress 进度回调 (doneSegments, totalSegments)，在 IO 线程回调；
     *        UI 侧用 scope.launch(Dispatchers.Main.immediate) 转发。
     * @throws OutOfMemoryError 截图过多/过大时；由 UI 层翻译为友好提示
     */
    fun process(
        context: Context,
        images: List<ImageRef>,
        ratio: Double,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        val plan = ShotLayout.plan(
            images.map { ShotLayout.ImageInput(it.widthPx, it.heightPx) }, ratio
        )
        require(plan.segments.isNotEmpty()) { "没有可切分的图片" }
        val total = plan.segments.size

        // ---------- docx：截图文档专用窄边距 + gridless（防行网格撑破页高） ----------
        val writer = DocxWriter(
            page = PageSetup(
                widthCm = ShotLayout.PAGE_W_CM,
                heightCm = ShotLayout.PAGE_H_CM,
                topCm = ShotLayout.MARGIN_CM,
                bottomCm = ShotLayout.MARGIN_CM,
                leftCm = ShotLayout.MARGIN_CM,
                rightCm = ShotLayout.MARGIN_CM
            ),
            gridless = true
        )

        // ---------- 第一遍：纯计算每段在 PDF 页面上的落位 ----------
        val pageW = 595.28f  // 21cm × 28.3465pt/cm
        val pageH = 841.89f  // 29.7cm
        val margin = (ShotLayout.MARGIN_CM * 28.3465).toFloat()
        val usableW = pageW - 2 * margin
        val colCenterX = floatArrayOf(margin + usableW / 4f, margin + 3 * usableW / 4f)
        val imgWPt = (plan.imgWIn * 72).toFloat()

        val slots = ArrayList<Slot>(total)
        var yPt = margin
        var pageNo = 1
        for (row in 0 until plan.rows) {
            val left = plan.cell(row, 0)
            val right = plan.cell(row, 1)
            // 行高 = 本行左右段显示高的最大值（矮段不占多余行）
            val rowHPt = (maxOf(left?.dispHIn ?: 0.0, right?.dispHIn ?: 0.0) * 72).toFloat()
            if (rowHPt > 0f && yPt + rowHPt > pageH - margin) {
                pageNo++
                yPt = margin
            }
            for (col in 0 until ShotLayout.COLS) {
                val seg = plan.cell(row, col) ?: continue
                val cx = colCenterX[col]
                slots += Slot(
                    seg = seg,
                    rect = RectF(
                        cx - imgWPt / 2f, yPt,
                        cx + imgWPt / 2f, yPt + (seg.dispHIn * 72f).toFloat()
                    ),
                    rowIdx = row,
                    colIdx = col,
                    pageNo = pageNo
                )
            }
            yPt += rowHPt
        }

        // ---------- 第二遍：逐图逐段解码，画 PDF + 登记 docx ----------
        // 全局序号 → 落位（slots 按行优先顺序生成，与 plan.segments 的填充顺序一致）
        val slotBySegIndex: Map<Int, Slot> =
            plan.segments.indices.associateWith { slots[it] }
        // 按图分组（组内保序）：图序 → 该图各段 (全局段序号, 段)
        val segsByImage: Map<Int, List<Pair<Int, ShotLayout.Segment>>> =
            plan.segments.withIndex()
                .groupBy { (_, s) -> s.imageIndex }
                .mapValues { (_, v) -> v.map { it.index to it.value } }

        val pdf = PdfDocument()
        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        // docx 表格网格（行 × 列 → ImageCell）
        val cells = List(plan.rows) { arrayOfNulls<DocxWriter.ImageCell>(ShotLayout.COLS) }
        var done = 0
        var openPage: PdfDocument.Page? = null
        var openPageNo = 0

        try {
            for ((imgIdx, ref) in images.withIndex()) {
                val segs = segsByImage[imgIdx] ?: continue
                val rotation = readRotation(context, ref.uri)
                val decoder = context.contentResolver.openInputStream(ref.uri)?.use {
                    BitmapRegionDecoder.newInstance(it, false)
                } ?: continue
                try {
                    for ((segIdx, seg) in segs) {
                        val slot = slotBySegIndex.getValue(segIdx)
                        // 换页：finish 旧页 → start 新页（页按段落顺序顺序生成）
                        if (slot.pageNo != openPageNo) {
                            openPage?.let { pdf.finishPage(it) }
                            openPage = pdf.startPage(
                                PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), slot.pageNo).create()
                            )
                            // PDF 默认无底色，透明 PNG 在部分查看器里会黑底
                            openPage!!.canvas.drawColor(Color.WHITE)
                            openPageNo = slot.pageNo
                        }
                        val bmp = rotateIfNeeded(
                            decoder.decodeRegion(
                                mapRegion(seg, ref.heightPx, decoder.height, rotation),
                                BitmapFactory.Options()
                            ),
                            rotation
                        )
                        // PDF：画到预算好的矩形（保持纵横比由 dispH 按实际像素高计算保证）
                        openPage!!.canvas.drawBitmap(bmp, null, slot.rect, filterPaint)
                        // docx：压缩登记（透明 PNG 保持 PNG，否则 JPEG）
                        val bos = ByteArrayOutputStream(1 shl 16)
                        val ext = if (bmp.hasAlpha()) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos); "png"
                        } else {
                            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos); "jpeg"
                        }
                        cells[slot.rowIdx][slot.colIdx] = DocxWriter.ImageCell(
                            writer.addImage(bos.toByteArray(), ext),
                            inchToEmu(plan.imgWIn),
                            inchToEmu(seg.dispHIn)
                        )
                        bmp.recycle() // 峰值内存控制：用完即回收
                        onProgress(++done, total)
                    }
                } finally {
                    decoder.recycle()
                }
            }

            writer.addImageTable(ShotLayout.COLS, cells.map { it.toList() })
            val docxBytes = writer.build("长截图文档")

            openPage?.let { pdf.finishPage(it) }
            val pdfOut = ByteArrayOutputStream(1 shl 18)
            pdf.writeTo(pdfOut)
            return Result(docxBytes, pdfOut.toByteArray(), plan)
        } finally {
            pdf.close()
        }
    }
}
