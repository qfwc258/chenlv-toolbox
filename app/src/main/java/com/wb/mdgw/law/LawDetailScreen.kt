package com.wb.mdgw.law

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
                                // 开始轮询等待 OFD 加载
                                waitForOfdAndInject(view, 0)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return false
                            }
                        }
                        webChromeClient = WebChromeClient()
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
 * 1. 找到 previewIframe，获取它的实际渲染宽度
 * 2. 计算缩放比例 = 手机屏幕宽度 / iframe实际宽度
 * 3. 用 document.body.style.zoom 缩放整个页面
 * 4. 自动滚动到 func-area（目录/下载/WPS版本）位置，使其显示在顶端
 * 5. 整个页面可上下左右自由滚动
 * 6. 定期检测 iframe src 变化，切换版本后重新计算缩放
 */
private fun injectReaderOnlyMode(view: WebView) {
    val js = """
        (function() {
            try {
                window.__adjustOfdReader = function() {
                    try {
                        // 找到 OFD 阅读器 iframe
                        var previewIframe = document.getElementById('previewIframe');
                        if (!previewIframe) {
                            var iframes = document.querySelectorAll('iframe');
                            if (iframes.length > 0) previewIframe = iframes[0];
                        }
                        
                        if (!previewIframe) {
                            if (!window.__ofdRetryCount) window.__ofdRetryCount = 0;
                            if (window.__ofdRetryCount < 20) {
                                window.__ofdRetryCount++;
                                setTimeout(window.__adjustOfdReader, 1000);
                            }
                            return;
                        }
                        
                        // 获取 iframe 的实际渲染宽度
                        var iframeWidth = previewIframe.offsetWidth || previewIframe.getBoundingClientRect().width;
                        if (iframeWidth < 100) {
                            // iframe 还没布局好，等一下再试
                            setTimeout(window.__adjustOfdReader, 500);
                            return;
                        }
                        
                        // 计算缩放比例：让 iframe 宽度 = 手机屏幕宽度
                        var screenWidth = window.innerWidth;
                        var scale = screenWidth / iframeWidth;
                        
                        // 用 zoom 缩放整个页面
                        document.body.style.zoom = scale;
                        document.documentElement.style.zoom = scale;
                        
                        // 自动滚动到 func-area（目录/下载/WPS版本按钮）位置
                        var funcArea = document.querySelector('.func-area');
                        if (funcArea) {
                            var rect = funcArea.getBoundingClientRect();
                            var scrollY = window.pageYOffset || document.documentElement.scrollTop;
                            var targetY = scrollY + rect.top;
                            window.scrollTo(0, targetY);
                        }
                        
                        // 记录当前 iframe 的 src
                        var currentSrc = previewIframe.src;
                        
                        // 定期检测 iframe src 变化，重新调整缩放
                        setInterval(function() {
                            try {
                                var iframe = document.querySelector('#previewIframe, iframe');
                                if (iframe && iframe.src !== currentSrc) {
                                    currentSrc = iframe.src;
                                    setTimeout(function() {
                                        var newIframe = document.querySelector('#previewIframe, iframe');
                                        if (newIframe) {
                                            var w = newIframe.offsetWidth || newIframe.getBoundingClientRect().width;
                                            if (w > 100) {
                                                var s = window.innerWidth / w;
                                                document.body.style.zoom = s;
                                                document.documentElement.style.zoom = s;
                                            }
                                        }
                                    }, 2000);
                                }
                            } catch(e) {}
                        }, 2000);
                        
                    } catch(e) {
                        console.error('Adjust OFD error:', e);
                    }
                };
                
                window.__adjustOfdReader();
            } catch(e) {
                console.error('Inject error:', e);
            }
        })()
    """.trimIndent()

    view.evaluateJavascript(js, null)
}
