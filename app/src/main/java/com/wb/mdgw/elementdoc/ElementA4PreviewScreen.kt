package com.wb.mdgw.elementdoc

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wb.mdgw.BrandTokens

/**
 * 要素式文书「官方 A4 预览」页：用 WebView 渲染 ElementDocHtmlBuilder 生成的 A4 纸面 HTML，
 * 所见即打印；顶部「A4 打印 / 存 PDF」调用系统打印框架（用户在系统界面选「另存为 PDF」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementA4PreviewScreen(
    html: String,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Throwable) {}
                try { wv.destroy() } catch (_: Throwable) {}
            }
        }
    }

    Scaffold(
        containerColor = BrandTokens.BrandPaper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("官方 A4 预览", style = BrandTokens.BrandTopBarTitleStyle.copy(fontSize = 18.sp))
                        Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val wv = webView
                        if (wv == null || !loaded) {
                            Toast.makeText(context, "预览尚未加载完成，请稍后再试", Toast.LENGTH_SHORT).show()
                        } else {
                            printHtmlToPdf(wv, "要素式文书-$title", context)
                        }
                    }) {
                        Icon(Icons.Default.Print, contentDescription = "A4打印/存PDF")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandTokens.BrandPaper)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color(0xFFE8E8E8))) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "按 A4 纸面 1:1 还原官方版式；点右上角打印图标可「另存为 PDF」或直接打印。",
                    fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            A4WebView(
                html = html,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onReady = { wv -> webView = wv },
                onLoaded = { loaded = true }
            )
        }
    }
}

/** 通过系统打印框架把预览 WebView 导出为 PDF（A4，零边距由 CSS @page 控制） */
private fun printHtmlToPdf(webView: WebView, jobName: String, context: Context) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (printManager == null) {
        Toast.makeText(context, "当前设备不支持打印 / 导出 PDF", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val adapter = webView.createPrintDocumentAdapter(jobName)
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, adapter, attrs)
    }.onFailure {
        Toast.makeText(context, "导出 PDF 失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun A4WebView(
    html: String,
    modifier: Modifier = Modifier,
    onReady: (WebView) -> Unit,
    onLoaded: () -> Unit
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                setBackgroundColor(0xFFE8E8E8.toInt())
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { onLoaded() }
                }
                onReady(this)
            }
        },
        update = { wv ->
            if (wv.tag as? String != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier
    )
}
