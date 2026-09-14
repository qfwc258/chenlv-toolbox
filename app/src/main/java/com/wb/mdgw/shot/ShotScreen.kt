package com.wb.mdgw.shot

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wb.mdgw.ExportResultDialog
import com.wb.mdgw.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ShotStage { IDLE, READY, DONE }

/** docx MIME（FileUtils 中为 private，按 WordScreen 先例在调用处自带） */
private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** 输出体积粗估阈值（MB），超过则提示分批 */
private const val LARGE_ESTIMATE_MB = 150

/**
 * 把异常翻译为用户友好的中文提示（仿 PdfScreen.friendlyError 的策略：
 * 只翻译明确已知场景，其余保留原始信息）。
 */
private fun friendlyError(t: Throwable): String {
    if (t is OutOfMemoryError || t.message?.contains("OutOfMemory", ignoreCase = true) == true) {
        return "截图过大或张数过多，内存不足。\n\n建议：减少一次处理的张数，或分批转换。"
    }
    if (t is IllegalArgumentException) {
        return t.message?.take(200)?.ifBlank { "参数无效" } ?: "参数无效"
    }
    val excName = t.javaClass.simpleName
    val detail = (t.message ?: "").ifBlank { "(无详细信息)" }.take(200)
    return "处理失败（$excName）：\n$detail"
}

/** 步骤标题（复制自 PdfScreen 的私有实现，保持各工具页视觉一致） */
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

/**
 * 长截图转 Word / PDF 屏：选图（可多选）→ 设置切分比例 → 生成 → 双格式导出。
 *
 * 流程骨架仿 PdfScreen（SAF 选文件 + Dispatchers.IO 处理 + 进度遮罩 + 保存分享），
 * 引擎见 [ShotEngine]，布局规划见 [ShotLayout]。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ShotScreen(initialUri: Uri? = null, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(ShotStage.IDLE) }
    var selected by remember { mutableStateOf<List<ShotEngine.ImageRef>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }
    var ratioText by remember { mutableStateOf("3.6") }

    var busy by remember { mutableStateOf(false) }
    var doneSeg by remember { mutableStateOf(0) }
    var totalSeg by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<ShotEngine.Result?>(null) }

    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showLargeWarning by remember { mutableStateOf(false) }

    var savedDocx by remember { mutableStateOf<FileUtils.SavedFile?>(null) }
    var savedPdf by remember { mutableStateOf<FileUtils.SavedFile?>(null) }
    var showDocxResult by remember { mutableStateOf(false) }
    var showPdfResult by remember { mutableStateOf(false) }

    /** 当前输入的切分比例；非法输入按默认 3.6 参与预估与生成 */
    val ratio: Double = ratioText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: ShotLayout.DEFAULT_RATIO

    /** 实时布局预估（选图 / 比例变化即重算，纯计算无位图开销） */
    val previewPlan = remember(selected, ratio) {
        ShotLayout.plan(selected.map { ShotLayout.ImageInput(it.widthPx, it.heightPx) }, ratio)
    }

    /** 追加图片：探测尺寸，失败剔除并提示 */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            probing = true
            val probed = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    ShotEngine.probe(context, uri)
                }
            }
            val failed = uris.size - probed.size
            if (probed.isNotEmpty()) {
                // 去重（同一张图重复选择时只保留一次）
                val existing = selected.map { it.uri.toString() }.toSet()
                selected = selected + probed.filter { it.uri.toString() !in existing }
                stage = ShotStage.READY
            }
            if (failed > 0) {
                scope.launch { snackbar.showSnackbar("$failed 张图片无法解码，已跳过（请使用 PNG / JPG 截图）") }
            }
            probing = false
        }
    }

    // 外部分享入口预填（系统相册「分享到陈律工具箱」直达本 Tab）
    LaunchedEffect(initialUri) {
        initialUri?.let { addImages(listOf(it)) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> addImages(uris) }

    /** 实际执行切分与双格式生成 */
    fun executeProcess() {
        if (selected.isEmpty()) return
        scope.launch {
            busy = true
            doneSeg = 0
            totalSeg = 0
            runCatching {
                withContext(Dispatchers.IO) {
                    ShotEngine.process(context, selected, ratio) { done, total ->
                        scope.launch(Dispatchers.Main.immediate) {
                            doneSeg = done
                            totalSeg = total
                        }
                    }
                }
            }.onSuccess { r ->
                result = r
                savedDocx = null
                savedPdf = null
                stage = ShotStage.DONE
                scope.launch { snackbar.showSnackbar("✓ 切分完成，共 ${r.plan.segments.size} 段") }
            }.onFailure {
                errorMessage = friendlyError(it)
                showErrorDialog = true
            }
            busy = false
        }
    }

    fun doProcess() {
        if (selected.isEmpty()) return
        val mb = previewPlan.estimatedBytes / (1024 * 1024)
        if (mb > LARGE_ESTIMATE_MB) showLargeWarning = true else executeProcess()
    }

    /** 导出结果文件（kind: docx / pdf），保存后弹统一结果弹窗 */
    fun export(kind: String) {
        val r = result ?: return
        val base = FileUtils.baseName(selected.firstOrNull()?.displayName ?: "").ifBlank { "长截图" }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    if (kind == "docx") {
                        val name = "${base}_切分.docx"
                        FileUtils.saveToDownloads(context, name, r.docxBytes, DOCX_MIME) to name
                    } else {
                        val name = "${base}_切分.pdf"
                        FileUtils.saveToDownloads(context, name, r.pdfBytes, FileUtils.PDF_MIME) to name
                    }
                }
            }.onSuccess { (sf, _) ->
                if (kind == "docx") { savedDocx = sf; showDocxResult = true }
                else { savedPdf = sf; showPdfResult = true }
            }.onFailure {
                errorMessage = friendlyError(it)
                showErrorDialog = true
            }
            busy = false
        }
    }

    fun openOrShare(sf: FileUtils.SavedFile?, name: String, mime: String, open: Boolean) {
        sf ?: return
        runCatching {
            context.startActivity(
                if (open) FileUtils.openIntent(sf.uri, mime)
                else FileUtils.shareIntent(sf.uri, name, mime)
            )
        }.onFailure {
            scope.launch { snackbar.showSnackbar(friendlyError(it)) }
        }
    }

    // ====== 全屏忙遮罩（处理中不可关闭） ======
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
                        if (probing) "正在读取截图…" else "正在切分并生成文档…",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(14.dp))
                    if (totalSeg > 0) {
                        val progress = doneSeg.toFloat() / totalSeg
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "已切分 $doneSeg / $totalSeg 段（${(progress * 100).toInt()}%）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("正在准备…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // ====== 大体积警告弹窗 ======
    if (showLargeWarning) {
        AlertDialog(
            onDismissRequest = { showLargeWarning = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF1976D2)) },
            title = { Text("内容较多") },
            text = {
                val mb = previewPlan.estimatedBytes / (1024 * 1024)
                Text("预计生成内容约 ${mb} MB（${previewPlan.segments.size} 段），处理耗时与内存占用较大。\n\n是否继续？")
            },
            confirmButton = {
                Button(onClick = {
                    showLargeWarning = false
                    executeProcess()
                }) { Text("继续处理") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLargeWarning = false }) { Text("取消") }
            }
        )
    }

    // ====== 导出结果弹窗 ======
    ExportResultDialog(
        visible = showDocxResult,
        onDismiss = { showDocxResult = false },
        title = "Word 文档已保存",
        fileName = "${FileUtils.baseName(selected.firstOrNull()?.displayName ?: "").ifBlank { "长截图" }}_切分.docx",
        savePath = savedDocx?.displayPath ?: "",
        fileIcon = Icons.Default.Description,
        onOpen = { openOrShare(savedDocx, "切分文档", DOCX_MIME, open = true) },
        onShare = { openOrShare(savedDocx, "切分文档", DOCX_MIME, open = false) }
    )
    ExportResultDialog(
        visible = showPdfResult,
        onDismiss = { showPdfResult = false },
        title = "PDF 文档已保存",
        fileName = "${FileUtils.baseName(selected.firstOrNull()?.displayName ?: "").ifBlank { "长截图" }}_切分.pdf",
        savePath = savedPdf?.displayPath ?: "",
        fileIcon = Icons.Default.PictureAsPdf,
        onOpen = { openOrShare(savedPdf, "切分文档", FileUtils.PDF_MIME, open = true) },
        onShare = { openOrShare(savedPdf, "切分文档", FileUtils.PDF_MIME, open = false) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 步骤 1：选择长截图 ----------
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepTitle(1, "选择长截图（可多选）")
                Button(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selected.isEmpty()) "选取截图（可多选）" else "继续添加截图")
                }
                selected.forEachIndexed { i, img ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${i + 1}. ${img.displayName}", fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${img.widthPx}×${img.heightPx}px",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { selected = selected.filterIndexed { j, _ -> j != i } }, enabled = !busy, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    Text(
                        "共 ${selected.size} 张 · 预计 ${previewPlan.segments.size} 段 · 约 ${previewPlan.pages} 页",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 步骤 2：切分设置 ----------
        if (selected.isNotEmpty()) {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepTitle(2, "切分比例")
                    OutlinedTextField(
                        value = ratioText,
                        onValueChange = { ratioText = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("每段高度 = 图宽 × 比例") },
                        supportingText = { Text("范围 ${ShotLayout.MIN_RATIO.toInt()} ~ ${ShotLayout.MAX_RATIO.toInt()}，默认 ${ShotLayout.DEFAULT_RATIO}；比例越大每段越长") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val segHCm = previewPlan.imgWIn * ratio * 2.54
                    Text(
                        "每段显示高 ≈ ${"%.1f".format(segHCm)}cm（自动适配 A4 页面，单段不跨页）· 两列表格排布",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 步骤 3：生成 ----------
            Button(
                onClick = { doProcess() },
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("处理中…")
                } else {
                    Icon(Icons.Default.ContentCut, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始切分并生成", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---------- 结果：双格式导出 ----------
        if (stage == ShotStage.DONE && result != null) {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(Modifier.width(8.dp))
                        Text("✓ 切分完成（${result!!.plan.segments.size} 段 · ${result!!.plan.pages} 页）", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { export("docx") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("导出 Word")
                        }
                        Button(
                            onClick = { export("pdf") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("导出 PDF")
                        }
                    }
                    TextButton(
                        onClick = {
                            result = null
                            savedDocx = null
                            savedPdf = null
                            stage = ShotStage.READY
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("重新生成") }
                }
            }
        }

        // ---------- 功能说明 ----------
        if (selected.isEmpty()) {
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("功能说明", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "把手机长截图按「每段高度 = 图宽 × 比例」切分为多段，以两列表格排入 A4 文档，" +
                            "可导出 Word 与 PDF 两种格式。\n\n" +
                            "· 支持一次选择多张长截图，按选择顺序合并进同一文档\n" +
                            "· 比例可调（1.0 ~ 5.0），段高自动适配页面、单段不跨页\n" +
                            "· 相册「分享」长截图到本应用可直达此页\n" +
                            "· 纯本地处理，截图不会上传",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
