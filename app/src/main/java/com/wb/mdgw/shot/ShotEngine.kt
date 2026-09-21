package com.wb.mdgw.shot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.wb.mdgw.DocxWriter
import com.wb.mdgw.FileUtils
import com.wb.mdgw.PageSetup
import com.wb.mdgw.PdfPageNum
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

    private const val TAG = "ShotEngine"

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
     * 探测图片尺寸（尽量不分配像素内存）。
     *
     * 优先用 [BitmapFactory] 读 bounds；若失败（HEIF/AVIF/特殊 WebP / 损坏文件），
     * 在 Android P+ 上用 [ImageDecoder] 读头信息再试一次。仍失败返回 null，由 UI 层剔除。
     */
    fun probe(context: Context, uri: Uri): ImageRef? = runCatching {
        var width = 0
        var height = 0

        // 1) BitmapFactory（大部分 PNG/JPG/WebP 直接过）
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            width = opts.outWidth
            height = opts.outHeight
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 2) ImageDecoder 读头（支持 HEIF、AVIF 等 BitmapFactory 读不出的格式）
            // 用 1×1 crop + 大 sample 避免真实分配像素
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.setCrop(Rect(0, 0, 1, 1))
                decoder.setTargetSampleSize(8)
            }
        }

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "probe 无法读取图片尺寸: ${FileUtils.displayName(context, uri)}")
            return null
        }

        val rotation = readRotation(context, uri)
        // 显示坐标系宽高：90/270 时互换
        val w = if (rotation == 90 || rotation == 270) height else width
        val h = if (rotation == 90 || rotation == 270) width else height

        ImageRef(
            uri = uri,
            displayName = FileUtils.displayName(context, uri).ifBlank { "截图" },
            widthPx = w,
            heightPx = h
        )
    }.onFailure {
        Log.w(TAG, "probe 失败: ${FileUtils.displayName(context, uri)}", it)
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

    /** 兜底采样时的最大目标边长（px）：1440 足够满足 A4 两列打印质量，同时控内存 */
    private const val FALLBACK_MAX_DIM = 1440

    /**
     * 区域解码（带多重降级）。
     *
     * 1. [BitmapRegionDecoder]：首选，逐段解码不载全图；
     * 2. [ImageDecoder]（API 28+）：支持 HEIF/AVIF/特殊 WebP 等；
     * 3. [BitmapFactory] 整图采样 + 裁剪：最后兜底。
     *
     * @return null 表示该段（进而该图）确实无法解码
     */
    @Suppress("DEPRECATION")
    private fun decodeSegment(
        context: Context,
        ref: ImageRef,
        seg: ShotLayout.Segment,
        rotation: Int
    ): Bitmap? {
        val storageH = if (rotation == 90 || rotation == 270) ref.widthPx else ref.heightPx
        val region = mapRegion(seg, ref.heightPx, storageH, rotation)

        // 1) BitmapRegionDecoder（首选）
        context.contentResolver.openInputStream(ref.uri)?.use { ins ->
            val decoder = runCatching { BitmapRegionDecoder.newInstance(ins, false) }.getOrNull()
            if (decoder != null) {
                return try {
                    rotateIfNeeded(decoder.decodeRegion(region, BitmapFactory.Options()), rotation)
                } catch (e: Exception) {
                    Log.w(TAG, "BitmapRegionDecoder 失败: ${ref.displayName}", e)
                    null
                } finally {
                    decoder.recycle()
                }
            }
        }

        // 2) ImageDecoder（API 28+，对 HEIF/AVIF/特殊 WebP 更友好）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, ref.uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setCrop(region)
                    // 限制采样：避免整图按原分辨率分配
                    decoder.setTargetSampleSize(
                        Integer.highestOneBit((region.width() / FALLBACK_MAX_DIM).coerceAtLeast(1))
                    )
                }
                return rotateIfNeeded(bmp, rotation)
            }.onFailure {
                Log.w(TAG, "ImageDecoder 失败: ${ref.displayName}", it)
            }
        }

        // 3) 整图采样 + 裁剪（最后兜底）
        return decodeSegmentByFullBitmap(context, ref, region, rotation)
    }

    /** 用 BitmapFactory 先解码整图（采样控内存），再裁剪出目标区域 */
    private fun decodeSegmentByFullBitmap(
        context: Context,
        ref: ImageRef,
        region: Rect,
        rotation: Int
    ): Bitmap? = runCatching {
        val storageW = if (rotation == 90 || rotation == 270) ref.heightPx else ref.widthPx
        val storageH = if (rotation == 90 || rotation == 270) ref.widthPx else ref.heightPx
        val maxDim = maxOf(storageW, storageH)
        val sample = Integer.highestOneBit((maxDim / FALLBACK_MAX_DIM).coerceAtLeast(1))

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(ref.uri)?.use { ins ->
            val full = BitmapFactory.decodeStream(ins, null, opts)
                ?: return@runCatching null
            val scaled = Rect(
                region.left / sample,
                region.top / sample,
                region.right / sample,
                region.bottom / sample
            )
            // 边界保护（采样后坐标可能因整除略有偏差）
            scaled.left = scaled.left.coerceIn(0, full.width)
            scaled.top = scaled.top.coerceIn(0, full.height)
            scaled.right = scaled.right.coerceIn(scaled.left, full.width)
            scaled.bottom = scaled.bottom.coerceIn(scaled.top, full.height)
            if (scaled.width() <= 0 || scaled.height() <= 0) {
                full.recycle()
                return@runCatching null
            }
            val cropped = Bitmap.createBitmap(full, scaled.left, scaled.top, scaled.width(), scaled.height())
            if (cropped != full) full.recycle()
            rotateIfNeeded(cropped, rotation)
        }
    }.onFailure {
        Log.w(TAG, "整图采样解码失败: ${ref.displayName}", it)
    }.getOrNull()

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
        config: ShotLayout.ShotConfig,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        val plan = ShotLayout.plan(
            images.map { ShotLayout.ImageInput(it.widthPx, it.heightPx) }, config
        )
        require(plan.segments.isNotEmpty()) { "没有可排版的图片" }
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
        // 列中心 x 坐标：columns 列沿可用宽度均匀分布（单列即居中整宽）
        val colCenterX = FloatArray(plan.cols) { c ->
            margin + (2 * c + 1) * usableW / (2 * plan.cols)
        }
        val imgWPt = (plan.imgWIn * 72).toFloat()

        val slots = ArrayList<Slot>(total)
        var yPt = margin
        var pageNo = 1
        for (row in 0 until plan.rows) {
            // 行高 = 本行各列中显示高的最大值（矮格不占多余行）
            var rowHPt = 0f
            for (col in 0 until plan.cols) {
                val seg = plan.cell(row, col) ?: continue
                rowHPt = maxOf(rowHPt, (seg.dispHIn * 72f).toFloat())
            }
            if (rowHPt > 0f && yPt + rowHPt > pageH - margin) {
                pageNo++
                yPt = margin
            }
            for (col in 0 until plan.cols) {
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
        val cells = List(plan.rows) { arrayOfNulls<DocxWriter.ImageCell>(plan.cols) }
        var done = 0
        var openPage: PdfDocument.Page? = null
        var openPageNo = 0

        try {
            for ((imgIdx, ref) in images.withIndex()) {
                val segs = segsByImage[imgIdx] ?: continue
                val rotation = readRotation(context, ref.uri)
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
                    val bmp = decodeSegment(context, ref, seg, rotation)
                        ?: continue // 该图格式不支持：跳过此段（该图其它段也会跳过）
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
            }

            writer.addImageTable(plan.cols, cells.map { it.toList() })
            val docxBytes = writer.build("长截图文档")

            openPage?.let { pdf.finishPage(it) }
            val pdfOut = ByteArrayOutputStream(1 shl 18)
            pdf.writeTo(pdfOut)
            var pdfBytes = pdfOut.toByteArray()
            // 自动加页码（失败不阻断导出）
            if (config.addPageNumber) {
                val position = when (config.pageNumberPosition) {
                    1 -> PdfPageNum.Position.BOTTOM_LEFT
                    2 -> PdfPageNum.Position.BOTTOM_RIGHT
                    3 -> PdfPageNum.Position.TOP_CENTER
                    4 -> PdfPageNum.Position.TOP_LEFT
                    5 -> PdfPageNum.Position.TOP_RIGHT
                    else -> PdfPageNum.Position.BOTTOM_CENTER
                }
                pdfBytes = runCatching {
                    PdfPageNum.addPageNumbersRobust(
                        pdfBytes,
                        PdfPageNum.Options(position = position, fontSize = 4)
                    )
                }.getOrElse {
                    android.util.Log.w("ShotEngine", "加页码失败，导出无页码 PDF: ${it.message}")
                    pdfBytes
                }
            }
            return Result(docxBytes, pdfBytes, plan)
        } finally {
            pdf.close()
        }
    }

    /** 便捷转发：与旧调用签名兼容（长截图两列切分，仅传比例） */
    fun process(
        context: Context,
        images: List<ImageRef>,
        ratio: Double,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result = process(context, images, ShotLayout.ShotConfig(splitLongImage = true, splitRatio = ratio), onProgress)
}
