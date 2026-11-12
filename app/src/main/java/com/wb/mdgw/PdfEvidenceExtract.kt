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
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * PDF 证据提取：把一份 PDF 按「页号规则」拆成若干独立 PDF，分别命名为「证据N-自定义名称」。
 *
 * 移植自用户的 Python 参考实现（PyPDF2 的 parse_page_range + extract_pdf_pages）：
 *  - 页号规则形如 `1,2-3,4-15,16-18,19`，按逗号切分为若干片段；
 *    每个片段是单页 `n` 或闭区间 `a-b`，区间起止倒置时自动交换；
 *  - 片段数量与自定义名称数量一一对应，产物命名为 `证据1-名称.pdf`、`证据2-名称.pdf`……
 *
 * 与加页码/盖章一致的「双引擎」策略：
 *  1. 首选 PDFBox 无损拆分（[extractByPdfBox]）：保留原页矢量/文字/图片，清晰度不变；
 *  2. PDFBox 失败（权限加密 / 特殊结构 / 解析异常）时回退原生 [PdfRenderer] 栅格化重写，
 *     以 2.5 倍超采样（≈180dpi）尽量保真。
 *
 * 全程纯本地处理，文件不上传任何服务器。
 */
object PdfEvidenceExtract {

    private const val TAG = "PdfEvidenceExtract"

    /** 栅格化回退的超采样倍数与单页像素上限（与 PdfPageNumRenderer 保持一致） */
    private const val BASE_SAMPLE = 2.5f
    private const val MAX_PIXELS = 50_000_000

    /** 一个证据片段：闭区间 [start, end]，页码从 1 开始；start == end 表示单页。 */
    data class Segment(val start: Int, val end: Int) {
        val pageCount: Int get() = end - start + 1
        override fun toString(): String = if (start == end) "$start" else "$start-$end"
    }

    /** 解析结果：[error] 非空表示解析失败；否则 [segments] 为解析出的片段（非空）。 */
    data class Parsed(val segments: List<Segment> = emptyList(), val error: String? = null)

    private val RANGE_RE = Regex("""^(\d+)\s*-\s*(\d+)$""")
    private val SINGLE_RE = Regex("""^(\d+)$""")

    /**
     * 解析页号规则 "1,2-3,4-15,16-18,19" 为片段列表。
     *
     * 与参考实现保持一致（区间倒置自动交换），但对非法片段**不再静默抛弃**，
     * 而是返回明确错误——避免片段被跳过导致证据编号与自定义名称错位。
     */
    fun parse(text: String): Parsed {
        val blocks = text.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return Parsed(error = "请输入页号，如 1,2-3,4-15")

        val segs = ArrayList<Segment>(blocks.size)
        blocks.forEachIndexed { idx, blk ->
            val mRange = RANGE_RE.matchEntire(blk)
            val mSingle = SINGLE_RE.matchEntire(blk)
            when {
                mRange != null -> {
                    var s = mRange.groupValues[1].toIntOrNull()
                    var e = mRange.groupValues[2].toIntOrNull()
                    if (s == null || e == null) {
                        return Parsed(error = "第 ${idx + 1} 段「$blk」数字过大或格式有误")
                    }
                    if (s > e) { val t = s; s = e; e = t }
                    segs += Segment(s, e)
                }
                mSingle != null -> {
                    val p = mSingle.groupValues[1].toIntOrNull()
                        ?: return Parsed(error = "第 ${idx + 1} 段「$blk」数字过大")
                    segs += Segment(p, p)
                }
                else -> return Parsed(error = "第 ${idx + 1} 段「$blk」格式有误，应为「1」或「2-3」")
            }
        }
        return Parsed(segments = segs)
    }

    /** 校验全部片段是否落在 [1, totalPages] 内；返回错误信息，全部合法返回 null。 */
    fun validate(segments: List<Segment>, totalPages: Int): String? {
        if (totalPages <= 0) return "无法读取 PDF 页数，请重新选择文件"
        segments.forEachIndexed { idx, s ->
            if (s.start < 1 || s.end > totalPages) {
                return "第 ${idx + 1} 段「$s」超出范围：本 PDF 共 $totalPages 页（有效页码 1-$totalPages）"
            }
        }
        return null
    }

    /** 按片段生成产物文件名：名称为空时用 `证据N.pdf`，否则 `证据N-名称.pdf`。 */
    fun outputName(index: Int, rawName: String): String {
        val cleaned = rawName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        return if (cleaned.isBlank()) "证据${index + 1}.pdf" else "证据${index + 1}-$cleaned.pdf"
    }

    /** 读取 PDF 页数：PDFBox 优先，失败回退原生渲染器。 */
    fun pageCount(input: ByteArray): Int {
        return try {
            loadDoc(input).use { it.numberOfPages }
        } catch (t: Throwable) {
            Log.w(TAG, "PDFBox 读取页数失败，回退原生渲染器: ${t.javaClass.simpleName}: ${t.message}")
            rendererPageCount(input)
        }
    }

    private fun rendererPageCount(input: ByteArray): Int {
        val f = File(System.getProperty("java.io.tmpdir"), "evpcount_${System.currentTimeMillis()}.tmp")
        try {
            f.writeBytes(input)
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            return try {
                renderer.pageCount
            } finally {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        } finally {
            f.delete()
        }
    }

    /**
     * 拆分入口：把 [input] 按 [segments] 逐段拆出。
     *
     * 与加页码的内存路径一致，返回全部产物字节，由 [PdfScreen] 在 [Dispatchers.IO] 上
     * 统一拿到结果后再逐份落盘。
     * @return 与 [segments] 顺序一一对应的产物字节列表。
     */
    fun extract(
        input: ByteArray,
        segments: List<Segment>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<ByteArray> {
        require(segments.isNotEmpty()) { "没有可提取的片段" }
        onProgress(0, segments.size)
        return try {
            extractByPdfBox(input, segments, onProgress).also {
                Log.i(TAG, "证据已用 PDFBox 无损拆分，共 ${segments.size} 段")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PDFBox 方案失败，回退原生渲染引擎: ${t.javaClass.simpleName}: ${t.message}")
            extractByRenderer(input, segments, onProgress).also {
                Log.i(TAG, "证据已用原生渲染引擎（栅格化）拆分，共 ${segments.size} 段")
            }
        }
    }

    /**
     * 加载文档：先普通加载；遇 InvalidPasswordException 尝试空密码解锁；
     * 仍失败抛出带说明的异常。其余异常原样上抛，交由 UI 层展示真实信息。
     */
    private fun loadDoc(input: ByteArray): PDDocument {
        return try {
            PDDocument.load(input)
        } catch (e: InvalidPasswordException) {
            try {
                PDDocument.load(input, "")
            } catch (e2: InvalidPasswordException) {
                throw IllegalStateException(
                    "该 PDF 设有打开密码或权限加密，无法处理。请先用电脑「另存为」导出无密码版本。",
                    e2
                )
            }
        }
    }

    /** PDFBox 无损拆分：一次性加载源文档，逐个片段导入页面后写出。 */
    private fun extractByPdfBox(
        input: ByteArray,
        segments: List<Segment>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<ByteArray> {
        loadDoc(input).use { src ->
            // 权限加密（无打开密码但限制修改）时移除安全策略，否则 save 会失败
            if (src.isEncrypted) {
                runCatching { src.setAllSecurityToBeRemoved(true) }
            }
            val out = ArrayList<ByteArray>(segments.size)
            segments.forEachIndexed { i, seg ->
                PDDocument().use { dst ->
                    for (p in (seg.start - 1) until seg.end) {
                        dst.importPage(src.getPage(p))
                    }
                    val bos = ByteArrayOutputStream()
                    dst.save(bos)
                    out += bos.toByteArray()
                }
                onProgress(i + 1, segments.size)
            }
            return out
        }
    }

    /**
     * 原生渲染回退：逐页栅格化后重写为独立 PDF。
     * 采用 2.5 倍超采样（≈180dpi）渲染、再整体缩放到原始物理尺寸写出，
     * 既保留清晰度又不改变页面尺寸（做法与 PdfPageNumRenderer 一致）。
     */
    private fun extractByRenderer(
        input: ByteArray,
        segments: List<Segment>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<ByteArray> {
        val inFile = File(System.getProperty("java.io.tmpdir"), "evin_${System.currentTimeMillis()}.tmp")
        try {
            inFile.writeBytes(input)
            val fd = ParcelFileDescriptor.open(inFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val results = ArrayList<ByteArray>(segments.size)
            try {
                segments.forEachIndexed { i, seg ->
                    val doc = PdfDocument()
                    try {
                        var outPageNo = 0
                        for (p in (seg.start - 1) until seg.end) {
                            val page = renderer.openPage(p)
                            val w = page.width
                            val h = page.height

                            var sample = BASE_SAMPLE
                            while (sample > 1f && (w * sample) * (h * sample) > MAX_PIXELS) sample -= 0.5f

                            val bmpW = (w * sample).roundToInt()
                            val bmpH = (h * sample).roundToInt()
                            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            val transform = android.graphics.Matrix().apply { setScale(sample, sample) }
                            page.render(bmp, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            val info = PdfDocument.PageInfo.Builder(w, h, ++outPageNo).create()
                            val outPage = doc.startPage(info)
                            outPage.canvas.drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                            doc.finishPage(outPage)
                            bmp.recycle()
                        }
                        val tmp = File(System.getProperty("java.io.tmpdir"), "evout_${System.currentTimeMillis()}_$i.tmp")
                        FileOutputStream(tmp).use { doc.writeTo(it) }
                        results += tmp.readBytes()
                        tmp.delete()
                    } finally {
                        doc.close()
                    }
                    onProgress(i + 1, segments.size)
                }
            } finally {
                renderer.close()
                fd.close()
            }
            return results
        } finally {
            inFile.delete()
        }
    }
}
