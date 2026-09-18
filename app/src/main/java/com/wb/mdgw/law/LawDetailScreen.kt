package com.wb.mdgw.law

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebView.HitTestResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 法规详情界面 - WebView + OFD 阅读器自适应手机
 *
 * 移植自法律宝典的完整实现：
 * 1. 等待 previewIframe 加载完成
 * 2. 注入 JS 隐藏多余元素，只保留 OFD 阅读器和 func-area 工具栏
 * 3. iframe 原始宽度设为 750px，用 CSS transform scale 缩放到手机宽度
 * 4. func-area 工具栏与 iframe 同缩放，固定在顶部
 * 5. 定期检测 iframe src 变化（切换 WPS版本/公报原版），重新应用缩放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LawDetailScreen(
    law: Law,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        law.title,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString = LawConstants.USER_AGENT
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                // 页面加载完成后立即注入缩放 JS
                                // 延迟 500ms 确保页面基本渲染完成
                                view?.postDelayed({
                                    injectReaderOnlyMode(view)
                                }, 500)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false
                            }
                        }
                        webChromeClient = WebChromeClient()

                        // 下载支持：WebView 默认不提供下载功能，需手动设置 DownloadListener
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(url))
                                request.setMimeType(mimeType)
                                // 添加 Cookie，确保下载请求携带登录态
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null) {
                                    request.addRequestHeader("cookie", cookies)
                                }
                                request.addRequestHeader("User-Agent", userAgent)
                                request.setDescription("正在下载")
                                request.setTitle(guessFileName(url, contentDisposition))
                                request.allowScanningByMediaScanner()
                                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                request.setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    guessFileName(url, contentDisposition)
                                )
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        loadUrl(law.detailUrl ?: LawConstants.OFFICIAL_URL)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 加载中
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("加载中...", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/**
 * 等待 OFD iframe 加载完成后再注入 JS，避免白屏
 * 轮询检测 id 为 previewIframe 的 iframe 是否存在
 */
private fun waitForOfdAndInject(view: WebView?, retryCount: Int) {
    if (view == null) return

    // 最多轮询 30 次（每次 500ms，总共 15 秒）
    if (retryCount >= 30) {
        injectReaderOnlyMode(view)
        return
    }

    // 检测 previewIframe 是否存在
    val checkJs = """
        (function() {
            var iframe = document.getElementById('previewIframe');
            if (iframe && iframe.src && iframe.src.length > 0) {
                return 'ready';
            }
            return 'not-ready';
        })()
    """.trimIndent()

    view.evaluateJavascript(checkJs) { result ->
        if (result == "\"ready\"") {
            // OFD iframe 已存在，再等待 1 秒确保内容加载完成，然后注入 JS
            view.postDelayed({
                injectReaderOnlyMode(view)
            }, 1000)
        } else {
            // OFD iframe 还没加载，500ms 后重试
            view.postDelayed({
                waitForOfdAndInject(view, retryCount + 1)
            }, 500)
        }
    }
}

/**
 * 注入 JS，调整 OFD 阅读器自适应手机
 *
 * 实现方案：
 * 1. 找到 previewIframe（OFD 阅读器）
 * 2. 设置 iframe 原始宽度为 1200px
 * 3. 计算 scale = 手机屏幕宽度 / 1200
 * 4. 用 CSS transform: scale() 缩放 iframe
 * 5. 自动滚动到 func-area（目录/下载/WPS版本）位置
 * 6. 整个页面可上下左右自由滚动
 */
private fun injectReaderOnlyMode(view: WebView) {
    val js = """
        (function() {
            try {
                var DESIGN_WIDTH = 2000;
                
                function adjustIframe() {
                    var iframe = document.getElementById('previewIframe');
                    if (!iframe) {
                        var iframes = document.querySelectorAll('iframe');
                        if (iframes.length > 0) iframe = iframes[0];
                    }
                    
                    if (!iframe) {
                        setTimeout(adjustIframe, 500);
                        return;
                    }
                    
                    // 计算缩放比例
                    var screenWidth = window.innerWidth;
                    var scale = screenWidth / DESIGN_WIDTH;
                    
                    // 设置 iframe 原始宽度
                    iframe.style.width = DESIGN_WIDTH + 'px';
                    iframe.style.border = 'none';
                    iframe.style.display = 'block';
                    
                    // 用 transform 缩放 iframe
                    iframe.style.transform = 'scale(' + scale + ')';
                    iframe.style.transformOrigin = 'top left';
                    
                    // 自动滚动到 func-area 位置
                    var funcArea = document.querySelector('.func-area');
                    if (funcArea) {
                        funcArea.scrollIntoView();
                    }
                }
                
                // 延迟执行，等待页面渲染
                setTimeout(adjustIframe, 1500);
                
            } catch(e) {
                console.error('Adjust OFD error:', e);
            }
        })()
    """.trimIndent()

    view.evaluateJavascript(js, null)
}

/**
 * 从 URL 或 Content-Disposition 中提取文件名
 */
private fun guessFileName(url: String?, contentDisposition: String?): String {
    // 优先从 Content-Disposition 中提取
    if (!contentDisposition.isNullOrBlank()) {
        val regex = Regex("filename=\"?([^\";]+)\"?")
        val match = regex.find(contentDisposition)
        if (match != null) {
            return match.groupValues[1]
        }
    }
    // 从 URL 中提取
    if (!url.isNullOrBlank()) {
        val cleanUrl = url.split("?")[0]
        val lastSegment = cleanUrl.substringAfterLast("/")
        if (lastSegment.isNotBlank() && lastSegment.contains(".")) {
            return lastSegment
        }
    }
    // 默认文件名
    return "法规文档_${System.currentTimeMillis()}.docx"
}
