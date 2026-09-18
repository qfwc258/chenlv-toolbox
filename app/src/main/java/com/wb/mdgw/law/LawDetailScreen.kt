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
 * 注入 JS，调整 OFD 阅读器自适应手机（应用模式）
 *
 * 实现方案（和法律宝典一致）：
 * 1. 找到 previewIframe（OFD 阅读器）
 * 2. 设置 iframe 原始宽度为 750px
 * 3. 计算 scale = 手机屏幕宽度 / 750
 * 4. func-area 固定在顶部（position: fixed）
 * 5. body 和 html 设为 overflow: hidden，整个页面不能滚动
 * 6. iframe 占满剩余空间，内部自己滚动
 */
private fun injectReaderOnlyMode(view: WebView) {
    val js = """
        (function() {
            try {
                var originalWidth = 750;
                
                function adjustReader() {
                    var reader = document.getElementById('previewIframe');
                    if (!reader) {
                        var iframes = document.querySelectorAll('iframe');
                        if (iframes.length > 0) reader = iframes[0];
                    }
                    
                    if (!reader) {
                        setTimeout(adjustReader, 500);
                        return;
                    }
                    
                    // 计算缩放比例
                    var scale = window.innerWidth / originalWidth;
                    
                    // func-area 原始高度约 44px
                    var funcAreaOriginalHeight = 44;
                    var funcAreaDisplayHeight = funcAreaOriginalHeight * scale;
                    
                    // 找到 iframe 的直接父容器
                    var iframeParent = reader.parentElement;
                    
                    if (iframeParent) {
                        iframeParent.style.position = 'relative';
                        iframeParent.style.width = '100%';
                        iframeParent.style.height = (window.innerHeight - funcAreaDisplayHeight) + 'px';
                        iframeParent.style.minHeight = (window.innerHeight - funcAreaDisplayHeight) + 'px';
                        iframeParent.style.overflow = 'hidden';
                        iframeParent.style.margin = '0';
                        iframeParent.style.padding = '0';
                        iframeParent.style.marginTop = funcAreaDisplayHeight + 'px';
                    }
                    
                    // 设置 iframe 原始尺寸
                    reader.style.width = originalWidth + 'px';
                    reader.style.height = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                    reader.style.minHeight = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                    reader.style.border = 'none';
                    reader.style.display = 'block';
                    reader.style.margin = '0';
                    reader.style.padding = '0';
                    
                    // 用 transform 缩放 iframe
                    reader.style.transform = 'scale(' + scale + ')';
                    reader.style.transformOrigin = 'top left';
                    
                    // 确保 iframe 的所有祖先元素也是全屏
                    var ancestor = reader.parentElement;
                    while (ancestor && ancestor !== document.body) {
                        ancestor.style.width = '100%';
                        ancestor.style.height = '100%';
                        ancestor.style.minHeight = '100vh';
                        ancestor.style.margin = '0';
                        ancestor.style.padding = '0';
                        ancestor.style.overflow = 'hidden';
                        ancestor = ancestor.parentElement;
                    }
                    
                    // 设置 body 和 html 为全屏
                    document.body.style.width = '100%';
                    document.body.style.height = '100%';
                    document.body.style.minHeight = '100vh';
                    document.body.style.overflow = 'hidden';
                    document.body.style.margin = '0';
                    document.body.style.padding = '0';
                    document.documentElement.style.width = '100%';
                    document.documentElement.style.height = '100%';
                    document.documentElement.style.overflow = 'hidden';
                    document.documentElement.style.margin = '0';
                    document.documentElement.style.padding = '0';
                    
                    // func-area 区域与 iframe 同缩放，固定在顶部
                    var funcArea = document.querySelector('.func-area');
                    if (funcArea) {
                        funcArea.style.position = 'fixed';
                        funcArea.style.top = '0';
                        funcArea.style.left = '0';
                        funcArea.style.width = originalWidth + 'px';
                        funcArea.style.height = funcAreaOriginalHeight + 'px';
                        funcArea.style.transform = 'scale(' + scale + ')';
                        funcArea.style.transformOrigin = 'top left';
                        funcArea.style.zIndex = '1000';
                        funcArea.style.background = '#fff';
                        funcArea.style.boxSizing = 'border-box';
                        funcArea.style.padding = '8px 12px';
                        funcArea.style.borderBottom = '1px solid #eee';
                        funcArea.style.margin = '0';
                        funcArea.style.display = 'flex';
                        funcArea.style.alignItems = 'center';
                        funcArea.style.justifyContent = 'space-between';
                    }
                    
                    // 隐藏其他所有元素，只保留 func-area 和 iframe 所在容器
                    var allElements = document.body.children;
                    for (var i = 0; i < allElements.length; i++) {
                        var el = allElements[i];
                        // 保留 func-area 和 iframe 的祖先容器
                        var isAncestorOfIframe = false;
                        var check = reader;
                        while (check) {
                            if (check === el) {
                                isAncestorOfIframe = true;
                                break;
                            }
                            check = check.parentElement;
                        }
                        // 保留 func-area
                        var isFuncArea = (el === funcArea);
                        
                        if (!isAncestorOfIframe && !isFuncArea) {
                            el.style.display = 'none';
                        }
                    }
                    
                    // 触发 resize 事件
                    window.dispatchEvent(new Event('resize'));
                }
                
                // 延迟执行，等待页面渲染
                setTimeout(adjustReader, 1500);
                
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
