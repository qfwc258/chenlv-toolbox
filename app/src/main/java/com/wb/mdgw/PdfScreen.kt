package com.wb.mdgw

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import android.net.Uri
import android.os.ParcelFileDescriptor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wb.mdgw.SegmentedTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class PdfTab { PAGE_NUM, SEAL }
private enum class PdfStage { IDLE, LOADED, DONE }

/** 大文件阈值（MB），超过则弹出警告 */
private const val LARGE_FILE_MB = 30

private fun isPdf(uri: Uri, fileName: String): Boolean {
    val n = fileName.lowercase()
    if (n.endsWith(".pdf")) return true
    return uri.toString().contains("pdf", ignoreCase = true)
}

/** 把角度归一化到 [0, 360) */
private fun normDeg(deg: Float): Float {
    var d = deg % 360f
    if (d < 0f) d += 360f
    return d
}

/**
 * 把印章位图按角度（顺时针）旋转，返回新的透明正方形位图。
 * 旋转后外接正方形边长取原图对角线，确保任意角度都不裁切；调用方据此等比例放大
 * 绘制方框，使「印章直径」在预览（原地旋转）与导出（矩阵缩放）中保持一致。
 */
private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    if (degrees % 360f == 0f) return src
    val w = src.width
    val h = src.height
    val diag = kotlin.math.hypot(w.toDouble(), h.toDouble()).toInt()
    val result = Bitmap.createBitmap(diag, diag, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    canvas.translate(diag / 2f, diag / 2f)
    canvas.rotate(degrees)
    canvas.translate(-w / 2f, -h / 2f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return result
}

/**
 * 把异常翻译为用户友好的中文提示。
 * 策略：只对明确已知的场景做专门翻译；其余一律保留原始异常信息（类名+消息），
 * 避免把非加密错误误报成"加密格式"。
 */
private fun friendlyError(t: Throwable): String {
    if (t is OutOfMemoryError || t.message?.contains("OutOfMemory", ignoreCase = true) == true) {
        return "文件过大，内存不足。\n\n建议：先用电脑压缩 PDF（如打印成缩小版）再导入；或拆分成小文件分别处理。"
    }
    val excName = t.javaClass.simpleName
    val msg = t.message ?: ""
    if (excName == "InvalidPasswordException" ||
        msg.contains("password", ignoreCase = true) && (
            excName.contains("Crypt", ignoreCase = true) ||
            excName.contains("Encryption", ignoreCase = true)
        )
    ) {
        return "该 PDF 设有打开密码或权限加密。\n\n请在电脑上用 PDF 阅读器打开后「另存为」导出无密码版本，再导入本应用。"
    }
    val detail = msg.ifBlank { "(无详细信息)" }.take(200)
    return "处理失败（$excName）：\n$detail"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PdfScreen(initialUri: Uri? = null, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var stage by remember { mutableStateOf(PdfStage.IDLE) }
    var fileName by remember { mutableStateOf("") }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf("") }
    var outName by remember { mutableStateOf("") }
    var savedFile by remember { mutableStateOf<FileUtils.SavedFile?>(null) }

    // 处理进度（真实逐页进度）
    var processedPages by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }

    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showLargeFileWarning by remember { mutableStateOf(false) }

    // 功能页签：加页码 / 盖章
    var tab by remember { mutableStateOf(PdfTab.PAGE_NUM) }

    var position by remember { mutableStateOf(PdfPageNum.Position.BOTTOM_RIGHT) }
    var startPage by remember { mutableStateOf("1") }
    var fontSize by remember { mutableStateOf("4") }
    var marginMm by remember { mutableStateOf("10") }
    var prefix by remember { mutableStateOf("") }
    var colorPreset by remember { mutableStateOf(PdfPageNum.ColorPreset.BLACK) }

    fun loadUri(uri: Uri) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = FileUtils.displayName(context, uri)
                    if (!isPdf(uri, name)) throw IllegalStateException("请选择 PDF 文件")
                    name to FileUtils.readBytes(context, uri)
                }
            }.onSuccess { (name, bytes) ->
                fileName = name
                pdfBytes = bytes
                stage = PdfStage.LOADED
                savedPath = ""
            }.onFailure {
                errorMessage = friendlyError(it)
                showErrorDialog = true
            }
            busy = false
        }
    }

    LaunchedEffect(initialUri) {
        initialUri?.let { loadUri(it) }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadUri(it)
        }
    }

    /** 加页码：实际执行处理 */
    fun executeProcess() {
        val data = pdfBytes ?: return
        scope.launch {
            busy = true
            processedPages = 0
            totalPages = 0
            progress = 0f
            runCatching {
                withContext(Dispatchers.IO) {
                    val opts = PdfPageNum.Options(
                        position = position,
                        startPage = startPage.toIntOrNull()?.coerceAtLeast(0) ?: 1,
                        fontSize = fontSize.toIntOrNull()?.coerceIn(4, 72) ?: 4,
                        marginMm = marginMm.toDoubleOrNull()?.coerceIn(0.0, 50.0) ?: 10.0,
                        colorR = colorPreset.r,
                        colorG = colorPreset.g,
                        colorB = colorPreset.b,
                        prefix = prefix
                    )
                    val bytes = PdfPageNum.addPageNumbersRobust(data, opts) { done, total ->
                        scope.launch(Dispatchers.Main.immediate) {
                            processedPages = done
                            totalPages = total
                            progress = if (total > 0) done.toFloat() / total else 0f
                        }
                    }
                    val name = (FileUtils.baseName(fileName).ifBlank { "文档" }) + "_页码.pdf"
                    val sf = FileUtils.saveToDownloads(context, name, bytes, FileUtils.PDF_MIME)
                    Pair(sf, name)
                }
            }.onSuccess { (sf, name) ->
                savedFile = sf
                outName = name
                savedPath = sf.displayPath
                stage = PdfStage.DONE
                scope.launch { snackbar.showSnackbar("✓ 页码已添加完成") }
            }.onFailure {
                errorMessage = friendlyError(it)
                showErrorDialog = true
            }
            busy = false
        }
    }

    fun doProcess() {
        val data = pdfBytes ?: return
        val mb = data.size / (1024 * 1024)
        if (mb > LARGE_FILE_MB) showLargeFileWarning = true else executeProcess()
    }

    fun shareResult(open: Boolean) {
        val sf = savedFile ?: return
        runCatching {
            context.startActivity(
                if (open) FileUtils.openIntent(sf.uri, FileUtils.PDF_MIME)
                else FileUtils.shareIntent(sf.uri, outName, FileUtils.PDF_MIME)
            )
        }.onFailure {
            scope.launch { snackbar.showSnackbar(friendlyError(it)) }
        }
    }

    // ====== 全屏加载遮罩 ======
    if (busy) {
        Dialog(
            onDismissRequest = { /* 处理中不可关闭 */ },
            DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(240.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (tab == PdfTab.SEAL) "正在盖章…" else "正在添加页码…",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                    if (tab == PdfTab.PAGE_NUM && totalPages > 0) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已处理 $processedPages / $totalPages 页（${(progress * 100).toInt()}%）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "正在准备…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ====== 错误弹窗 ======
    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE65100)) },
            title = { Text("处理失败") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) { Text("知道了") }
            }
        )
    }

    // ====== 大文件警告弹窗 ======
    if (showLargeFileWarning) {
        AlertDialog(
            onDismissRequest = { showLargeFileWarning = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF1976D2)) },
            title = { Text("文件较大") },
            text = {
                val mb = (pdfBytes?.size ?: 0) / (1024 * 1024)
                Text("该文件约 ${mb} MB，处理可能需要较长时间（取决于页数和设备性能）。\n\n是否继续？")
            },
            confirmButton = {
                Button(onClick = {
                    showLargeFileWarning = false
                    executeProcess()
                }) { Text("继续处理") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLargeFileWarning = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 功能页签 ----------
        SegmentedTabs(
            items = listOf("加页码" to Icons.Default.Subject, "盖章" to Icons.Default.GppGood),
            selectedIndex = tab.ordinal,
            onSelect = { tab = PdfTab.values()[it] },
            modifier = Modifier.fillMaxWidth()
        )

        // ---------- 步骤 1 选择文件（两个功能共用） ----------
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepTitle(1, "选择 PDF 文件")
                Button(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (stage == PdfStage.IDLE) "选取 PDF 文件" else "重新选择文件")
                }
                if (fileName.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fileName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val kb = (pdfBytes?.size ?: 0) / 1024
                                val sizeStr = if (kb > 1024) "${kb / 1024} MB" else "$kb KB"
                                Text(sizeStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ---------- 功能内容 ----------
        if (pdfBytes != null) {
            when (tab) {
                PdfTab.PAGE_NUM -> PageNumberSection(
                    stage = stage, position = position, onPosition = { position = it },
                    startPage = startPage, onStartPage = { startPage = it },
                    fontSize = fontSize, onFontSize = { fontSize = it },
                    marginMm = marginMm, onMarginMm = { marginMm = it },
                    prefix = prefix, onPrefix = { prefix = it },
                    colorPreset = colorPreset, onColorPreset = { colorPreset = it },
                    onProcess = { doProcess() }, busy = busy
                )
                PdfTab.SEAL -> SealSection(
                    context = context, scope = scope, density = density,
                    pdfBytes = pdfBytes!!, fileName = fileName,
                    snackbar = snackbar,
                    onBusy = { busy = it }, onError = {
                        errorMessage = it
                        showErrorDialog = true
                    }
                )
            }
        }

        // ---------- 加页码结果 ----------
        if (tab == PdfTab.PAGE_NUM && stage == PdfStage.DONE) {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(Modifier.width(8.dp))
                        Text("✓ 页码已添加并保存", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(outName, fontWeight = FontWeight.Medium)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("保存位置：$savedPath", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { shareResult(open = true) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("打开文件")
                        }
                        Button(onClick = { shareResult(open = false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("分享文件")
                        }
                    }
                }
            }
        }

        // ---------- 说明 ----------
        if (stage == PdfStage.IDLE) {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("功能说明", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("• 加页码：在每页按所选位置叠加页码，不改变原文字与版式", fontSize = 13.sp)
                    Text("• 盖章：选择透明印章 PNG，拖拽定位后衬在文字下方导出", fontSize = 13.sp)
                    Text("• 纯本地处理，文件不上传任何服务器", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("暂不支持已加密（设密码）的 PDF。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// =====================================================================
// 加页码功能区
// =====================================================================
@Composable
private fun PageNumberSection(
    stage: PdfStage,
    position: PdfPageNum.Position,
    onPosition: (PdfPageNum.Position) -> Unit,
    startPage: String, onStartPage: (String) -> Unit,
    fontSize: String, onFontSize: (String) -> Unit,
    marginMm: String, onMarginMm: (String) -> Unit,
    prefix: String, onPrefix: (String) -> Unit,
    colorPreset: PdfPageNum.ColorPreset, onColorPreset: (PdfPageNum.ColorPreset) -> Unit,
    onProcess: () -> Unit, busy: Boolean
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepTitle(2, "页码设置")
            Text("页码位置", fontWeight = FontWeight.Medium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PdfPageNum.Position.values().forEach { v ->
                    FilterChip(
                        selected = position == v, onClick = { onPosition(v) },
                        label = {
                            Text(
                                when (v) {
                                    PdfPageNum.Position.TOP_LEFT -> "↖"
                                    PdfPageNum.Position.TOP_CENTER -> "↑"
                                    PdfPageNum.Position.TOP_RIGHT -> "↗"
                                    PdfPageNum.Position.BOTTOM_LEFT -> "↙"
                                    PdfPageNum.Position.BOTTOM_CENTER -> "↓"
                                    PdfPageNum.Position.BOTTOM_RIGHT -> "↘"
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PagePreview(position, colorPreset, prefix)
            }
            OutlinedTextField(
                value = prefix, onValueChange = onPrefix,
                label = { Text("前缀（可选，如「第」）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = startPage, onValueChange = { onStartPage(it.filter { c -> c.isDigit() }) },
                    label = { Text("起始页码") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fontSize, onValueChange = { onFontSize(it.filter { c -> c.isDigit() }) },
                    label = { Text("字号(pt)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = marginMm, onValueChange = { onMarginMm(it.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text("边距(mm)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Text("页码颜色", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PdfPageNum.ColorPreset.values().forEach { cp ->
                    Surface(
                        onClick = { onColorPreset(cp) }, shape = RoundedCornerShape(50),
                        color = Color(cp.r, cp.g, cp.b),
                        modifier = Modifier
                            .size(34.dp)
                            .then(if (colorPreset == cp) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)) else Modifier)
                    ) {}
                }
            }
            Text(
                "页码从「起始页码」开始连续递增，可加前缀。字体为 Helvetica，颜色可自定义。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Button(
        onClick = onProcess, enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text("处理中…")
        } else {
            Icon(Icons.AutoMirrored.Filled.Subject, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("添加页码", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// =====================================================================
// 盖章功能区
// =====================================================================
@Composable
private fun SealSection(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    density: androidx.compose.ui.unit.Density,
    pdfBytes: ByteArray,
    fileName: String,
    snackbar: SnackbarHostState,
    onBusy: (Boolean) -> Unit,
    onError: (String) -> Unit
) {
    // 预览 PDF 渲染器：把 pdfBytes 落盘后用原生 PdfRenderer 打开
    val rendererPair = remember(pdfBytes) {
        val f = File(context.cacheDir, "seal_preview_${System.currentTimeMillis()}.pdf")
        f.writeBytes(pdfBytes)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd) to f
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { rendererPair.first.close() }
            runCatching { rendererPair.second.delete() }
        }
    }
    val renderer = rendererPair.first
    val pageCount = renderer.pageCount

    var currentPage by remember { mutableStateOf(0) }
    if (currentPage >= pageCount && pageCount > 0) currentPage = pageCount - 1

    // 印章图片：选择 PNG → 复制私有目录 → 解码预览
    var sealPath by remember { mutableStateOf<String?>(null) }
    var sealBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val sealPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = FileUtils.displayName(context, it)
                val dir = File(context.filesDir, "seals").apply { mkdirs() }
                val safe = (name.ifBlank { "seal" }).replace(Regex("[^\\w.\\-]"), "_")
                val dst = File(dir, "seal_${System.currentTimeMillis()}_$safe")
                dst.writeBytes(FileUtils.readBytes(context, it))
                val bmp = BitmapFactory.decodeFile(dst.absolutePath)
                if (bmp == null) throw IllegalStateException("无法解码该图片，请选择透明 PNG 印章")
                sealPath = dst.absolutePath
                sealBitmap = bmp.asImageBitmap()
            }.onFailure {
                onError(friendlyError(it))
            }
        }
    }

    // 预设 / 自定义尺寸（厘米）
    var selectedPreset by remember { mutableStateOf<SizeUtils.Preset?>(SizeUtils.Preset.COMPANY) }
    var customCm by remember { mutableStateOf("4") }

    // 透明度
    var alpha by remember { mutableStateOf(0.8f) }

    // 旋转角度（度，0~360，顺时针）
    var sealRotation by remember { mutableStateOf(0f) }

    // 印章层级：false=覆盖(最上层，图像型/扫描件PDF必须，否则章被扫描图盖住看不到)
    //          true=衬底(文字下方，仅文字型PDF有效，让字透出)
    var sealUnderText by remember { mutableStateOf(false) }

    // 印章在预览视图内的位置（px）与边长（px）
    var sealCenterX by remember { mutableStateOf(0f) }
    var sealCenterY by remember { mutableStateOf(0f) }
    var sealScreenSize by remember { mutableStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }

    // 当前预览页位图
    var previewBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pdfPageW by remember { mutableStateOf(0f) }
    var pdfPageH by remember { mutableStateOf(0f) }
    // 当前页真实 PDF pt 尺寸（MediaBox），用于导出坐标换算与实时坐标反馈。
    // 注意：上面的 pdfPageW/H 来自 PdfRenderer 渲染像素（随设备 density 放大、≠ PDF pt），
    // 不能直接当作 PDF 用户空间坐标传给底层盖章，否则 density≠1 时印章会被推到页面外。
    var pdfPtW by remember { mutableStateOf(0f) }
    var pdfPtH by remember { mutableStateOf(0f) }
    // 预览视图实际像素尺寸（供导出时屏幕坐标→PDF坐标换算，跨 BoxWithConstraints 作用域）
    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    LaunchedEffect(renderer, currentPage) {
        if (pageCount == 0) return@LaunchedEffect
        val p = renderer.openPage(currentPage)
        pdfPageW = p.width.toFloat()
        pdfPageH = p.height.toFloat()
        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        p.close()
        previewBmp = bmp.asImageBitmap()
        // 读取当前页真实 PDF pt 尺寸（MediaBox）供导出坐标换算与实时反馈。
        // pdfPageW/H 来自 PdfRenderer 渲染像素（与设备 density 相关、不等于 PDF pt），
        // 不能直接作为 PDF 用户空间坐标传给底层盖章，否则 density≠1 时印章会被推到页面外。
        pdfBytes?.let { bytes ->
            runCatching {
                PDDocument.load(bytes).use { doc ->
                    val mb = doc.getPage(currentPage).mediaBox
                    pdfPtW = mb.width
                    pdfPtH = mb.height
                }
            }
        }
    }

    // 由尺寸来源计算厘米值
    val sealCm: Float = selectedPreset?.cm ?: (customCm.toFloatOrNull() ?: 4f)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 步骤 2 选择印章图片
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepTitle(2, "选择印章图片")
                Text("请选择一张透明背景的 PNG 印章（带白底会遮挡文字）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { sealPicker.launch(arrayOf("image/png", "image/*")) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (sealPath == null) "选择印章 PNG" else "重新选择印章")
                }
                sealBitmap?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap = it, contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White, RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(6.dp))
                                .padding(4.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("印章已载入", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 步骤 3 尺寸与透明度
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepTitle(3, "印章尺寸与透明度")
                Text("预设规格（直径/边长）", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeUtils.Preset.values().forEach { p ->
                        FilterChip(
                            selected = selectedPreset == p,
                            onClick = { selectedPreset = p },
                            label = { Text("${p.label} ${p.cm}cm") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = customCm,
                    onValueChange = { v ->
                        customCm = v.filter { c -> c.isDigit() || c == '.' }
                        selectedPreset = null
                    },
                    label = { Text("自定义边长（厘米）") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("当前尺寸：${"%.1f".format(sealCm)} 厘米", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("透明度", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = alpha, onValueChange = { alpha = it },
                        valueRange = 0.5f..1f, steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${(alpha * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(44.dp))
                }
                Text("旋转角度", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = sealRotation, onValueChange = { sealRotation = it },
                        valueRange = 0f..360f, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${sealRotation.toInt()}°", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sealRotation = normDeg(sealRotation - 90f) }, modifier = Modifier.weight(1f)) { Text("↺ -90°") }
                    OutlinedButton(onClick = { sealRotation = normDeg(sealRotation + 90f) }, modifier = Modifier.weight(1f)) { Text("↻ +90°") }
                }
                Text("印章层级", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !sealUnderText, onClick = { sealUnderText = false },
                        label = { Text("覆盖(字上)") }, modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = sealUnderText, onClick = { sealUnderText = true },
                        label = { Text("衬底(字下)") }, modifier = Modifier.weight(1f)
                    )
                }
                Text("图像型PDF(扫描件)请选「覆盖」，否则章被扫描图盖住看不到；文字型PDF可选「衬底」让字透出。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 步骤 4 预览与拖拽定位
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepTitle(4, "预览与定位")
                // 页码输入框：用独立 String 状态管理文本，避免「value 直接派生自 currentPage」
                // 导致的二次编辑失效（输入被回退、光标错乱）。空输入不更新 currentPage，
                // 仅在页码被外部（越界 clamp）改变时才回写输入框文本。
                var pageInput by remember { mutableStateOf((currentPage + 1).toString()) }
                LaunchedEffect(currentPage) {
                    val expected = (currentPage + 1).toString()
                    if (pageInput != expected) pageInput = expected
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("页码", fontSize = 13.sp)
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { txt ->
                            pageInput = txt
                            val digits = txt.filter { c -> c.isDigit() }
                            if (digits.isNotEmpty()) {
                                val v = digits.toIntOrNull() ?: 1
                                currentPage = (v - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            }
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp)
                    )
                    Text("/ 共 $pageCount 页", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("手指拖动移动印章，双指捏合缩放大小。预览区印章置顶便于编辑；导出后自动衬到文字下方。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val vw = constraints.maxWidth.toFloat()
                    viewW = vw
                    val ratio = if (pdfPageW > 0f) pdfPageH / pdfPageW else 1.414f
                    val vh = vw * ratio
                    viewH = vh

                    // 首次定位到页面中心：必须等预览页尺寸(pdfPageW)就绪后再初始化。
                    // 否则选图过早时 pdfPageW 仍为 0，印章像素尺寸会被算成巨大值而移出可视区
                    //（竖向页面渲染像素更多、稍慢，更容易踩中该时机窗）。
                    LaunchedEffect(viewW, viewH, sealBitmap, pdfPageW) {
                        if (!initialized && viewW > 0 && sealBitmap != null && pdfPageW > 0f) {
                            sealCenterX = viewW / 2f
                            sealCenterY = viewH / 2f
                            sealScreenSize = SizeUtils.cmToPt(sealCm) * (vw / pdfPageW)
                            initialized = true
                        }
                    }
                    // 尺寸来源 / 页面切换时同步预览像素尺寸（保持中心）；预览未就绪时不计算，避免巨大值
                    val targetPx = if (pdfPageW > 0f) SizeUtils.cmToPt(sealCm) * (vw / pdfPageW) else 0f
                    LaunchedEffect(targetPx) { if (initialized && targetPx > 0f) sealScreenSize = targetPx }

                    val sizeDp = with(density) { sealScreenSize.toDp() }
                    val offsetX = (sealCenterX - sealScreenSize / 2f).roundToInt()
                    val offsetY = (sealCenterY - sealScreenSize / 2f).roundToInt()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { vh.toDp() })
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(6.dp))
                    ) {
                        previewBmp?.let {
                            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize())
                        }
                        if (sealBitmap != null && initialized) {
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(offsetX, offsetY) }
                                    .then(if (sealScreenSize > 0f) Modifier.requiredSize(sizeDp) else Modifier)
                                    .pointerInput(sealBitmap) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            sealCenterX += pan.x
                                            sealCenterY += pan.y
                                            val next = (sealScreenSize * zoom).coerceIn(
                                                SizeUtils.MIN_SEAL_PT * (vw / pdfPageW.coerceAtLeast(1f)),
                                                SizeUtils.MAX_SEAL_PT * (vw / pdfPageW.coerceAtLeast(1f))
                                            )
                                            sealScreenSize = next
                                        }
                                    }
                            ) {
                                Image(bitmap = sealBitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize().rotate(sealRotation))
                            }
                        }
                    }

                    // 实时坐标反馈（预览 px → PDF pt）
                    if (initialized && pdfPtW > 0f && pdfPtH > 0f) {
                        val (pdfCx, pdfCy) = PdfCoordinateUtils.screenToPdfPointNormalized(
                            sealCenterX, sealCenterY, vw, vh, pdfPtW, pdfPtH
                        )
                        val pdfSize = PdfCoordinateUtils.screenSizeToPdfPt(sealScreenSize, vw, pdfPtW)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "印章中心：(${"%.1f".format(pdfCx)}, ${"%.1f".format(pdfCy)}) pt · 边长 ${"%.1f".format(pdfSize)} pt",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 步骤 5 导出盖章 PDF
        Button(
            onClick = {
                if (sealPath == null) { onError("请先选择印章图片"); return@Button }
                if (pageCount == 0) { onError("PDF 无页面"); return@Button }
                if (pdfPtW <= 0f || pdfPtH <= 0f) { onError("页面尺寸读取失败，请稍后重试"); return@Button }
                // 用真实 PDF pt 尺寸换算坐标（pdfPageW/H 是渲染像素、不等于 pt，density≠1 时会把章推出页面）
                val (pdfCx, pdfCy) = PdfCoordinateUtils.screenToPdfPointNormalized(
                    sealCenterX, sealCenterY, viewW, viewH, pdfPtW, pdfPtH
                )
                val pdfSize = SizeUtils.cmToPt(sealCm)
                val srcFile = File(context.cacheDir, "seal_src_${System.currentTimeMillis()}.pdf").apply { writeBytes(pdfBytes) }
                val outName2 = (FileUtils.baseName(fileName).ifBlank { "文档" }) + "_盖章.pdf"
                onBusy(true)
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val raw = BitmapFactory.decodeFile(sealPath!!)
                                ?: throw IllegalStateException("印章图片丢失，请重新选择")
                            // 透明度预乘到印章位图：PNG 自带 alpha，再按滑块衰减，
                            // 确保「透明度」滑块真实生效（图形状态 alpha 对位图未必生效）。
                            val alphaBmp = if (alpha < 1f) {
                                val out = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
                                val cv = android.graphics.Canvas(out)
                                val pt = android.graphics.Paint().apply { this.alpha = (alpha * 255).toInt() }
                                cv.drawBitmap(raw, 0f, 0f, pt)
                                out
                            } else raw
                            // 按设定角度（顺时针）旋转，得到外接正方形位图
                            val bmp = rotateBitmap(alphaBmp, sealRotation)
                            // 旋转后位图边长可能变大（取对角线），据此放大绘制方框，
                            // 保证「印章直径」与预览一致（预览为原地旋转，包围盒即印章直径）。
                            val drawPt = pdfSize * (bmp.width.toFloat() / raw.width.toFloat())
                            val out = File(context.cacheDir, "seal_out_${System.currentTimeMillis()}.pdf")
                            PdfSeal.sealPdfWithBitmap(
                                srcPdf = srcFile, outPdf = out, pageIndex = currentPage,
                                pdfCenterX = pdfCx, pdfCenterY = pdfCy,
                                sealPtSize = drawPt, alpha = 1f, bitmap = bmp,
                                prepend = sealUnderText
                            )
                            val sf = FileUtils.saveToDownloads(context, outName2, out.readBytes(), FileUtils.PDF_MIME)
                            sf to outName2
                        }
                    }
                    if (result.isSuccess) {
                        val (sf, name) = result.getOrThrow()
                        onBusy(false)
                        snackbar.showSnackbar("✓ 已盖章并保存：$name")
                    } else {
                        onBusy(false)
                        onError(friendlyError(result.exceptionOrNull()!!))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.GppGood, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("盖章并导出", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PagePreview(
    position: PdfPageNum.Position,
    colorPreset: PdfPageNum.ColorPreset,
    prefix: String
) {
    val dotColor = Color(colorPreset.r, colorPreset.g, colorPreset.b)
    val align = when (position) {
        PdfPageNum.Position.TOP_LEFT -> Alignment.TopStart
        PdfPageNum.Position.TOP_CENTER -> Alignment.TopCenter
        PdfPageNum.Position.TOP_RIGHT -> Alignment.TopEnd
        PdfPageNum.Position.BOTTOM_LEFT -> Alignment.BottomStart
        PdfPageNum.Position.BOTTOM_CENTER -> Alignment.BottomCenter
        PdfPageNum.Position.BOTTOM_RIGHT -> Alignment.BottomEnd
    }
    Box(
        Modifier
            .width(96.dp)
            .height(132.dp)
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFFBDBDBD), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = if (it == 4) 0.6f else 0.85f)
                        .height(4.dp)
                        .background(Color(0xFFE0E0E0), RoundedCornerShape(2.dp))
                )
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = align) {
            Surface(color = dotColor, shape = RoundedCornerShape(3.dp)) {
                Text(
                    "${prefix.ifBlank { "" }}1",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StepTitle(step: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50), modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$step", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
