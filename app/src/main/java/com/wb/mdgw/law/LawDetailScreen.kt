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
 * 注入 JS，隐藏网站多余元素，只显示 OFD 阅读器内容区域
 *
 * 核心逻辑：
 * 1. 找到 id=previewIframe 的 OFD 阅读器 iframe
 * 2. 设置 iframe 原始宽度为 750px，用 CSS transform scale 缩放到手机宽度
 * 3. func-area 工具栏与 iframe 同缩放，固定在顶部
 * 4. 隐藏其他多余元素
 * 5. 定期检测 iframe src 变化（切换 WPS版本/公报原版），重新应用缩放
 */
private fun injectReaderOnlyMode(view: WebView) {
    val js = """
        (function() {
            try {
                window.__tryReaderOnlyMode = function() {
                    try {
                        // 找到 OFD 阅读器 iframe
                        var previewIframe = document.getElementById('previewIframe');
                        if (!previewIframe) {
                            var iframes = document.querySelectorAll('iframe');
                            if (iframes.length > 0) previewIframe = iframes[0];
                        }
                        
                        if (!previewIframe) {
                            if (!window.__readerRetryCount) window.__readerRetryCount = 0;
                            if (window.__readerRetryCount < 20) {
                                window.__readerRetryCount++;
                                setTimeout(window.__tryReaderOnlyMode, 1000);
                            }
                            return;
                        }
                        
                        // 已经处理过，不重复处理
                        if (window.__readerOnlyModeApplied) return;
                        window.__readerOnlyModeApplied = true;
                        
                        // 隐藏页面顶部导航、侧边栏等多余元素
                        var body = document.body;
                        var children = body.children;
                        for (var i = 0; i < children.length; i++) {
                            var child = children[i];
                            var childClass = child.className || '';
                            var isFuncArea = typeof childClass === 'string' && childClass.indexOf('func-area') >= 0;
                            var hasFuncArea = child.querySelector && child.querySelector('.func-area');
                            
                            if (isFuncArea || hasFuncArea) {
                                child.style.display = 'block';
                                child.style.position = 'relative';
                                child.style.width = '100%';
                                child.style.maxWidth = '100%';
                                child.style.margin = '0';
                                child.style.padding = '8px 12px';
                                child.style.boxSizing = 'border-box';
                                child.style.background = '#fff';
                                child.style.borderBottom = '1px solid #eee';
                                child.style.zIndex = '100';
                            } else {
                                child.style.display = 'none';
                            }
                        }
                        
                        // 设置 body 和 html 为全屏
                        document.documentElement.style.margin = '0';
                        document.documentElement.style.padding = '0';
                        document.documentElement.style.overflow = 'hidden';
                        body.style.margin = '0';
                        body.style.padding = '0';
                        body.style.overflow = 'hidden';
                        body.style.background = '#fff';
                        
                        // OFD 内容原始宽度约 700px，设置 750px 让内容拉满手机宽度
                        var originalWidth = 750;
                        var scale = window.innerWidth / originalWidth;
                        
                        // func-area 原始高度约 44px
                        var funcAreaOriginalHeight = 44;
                        var funcAreaDisplayHeight = funcAreaOriginalHeight * scale;
                        
                        // 找到 iframe 的直接父容器
                        var iframeParent = previewIframe.parentElement;
                        
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
                        previewIframe.style.width = originalWidth + 'px';
                        previewIframe.style.height = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                        previewIframe.style.minHeight = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                        previewIframe.style.border = 'none';
                        previewIframe.style.display = 'block';
                        previewIframe.style.margin = '0';
                        previewIframe.style.padding = '0';
                        
                        // 用 CSS transform 缩放 iframe，使其显示宽度=手机宽度
                        previewIframe.style.transform = 'scale(' + scale + ')';
                        previewIframe.style.transformOrigin = 'top left';
                        
                        // 确保 iframe 的所有祖先元素也是全屏
                        var ancestor = previewIframe.parentElement;
                        while (ancestor && ancestor !== document.body) {
                            ancestor.style.width = '100%';
                            ancestor.style.height = '100%';
                            ancestor.style.minHeight = '100vh';
                            ancestor.style.margin = '0';
                            ancestor.style.padding = '0';
                            ancestor.style.overflow = 'hidden';
                            ancestor = ancestor.parentElement;
                        }
                        
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
                        
                        // 记录当前 iframe 的 src，用于检测变化
                        var currentIframeSrc = previewIframe.src;
                        
                        // 定期检测 iframe src 变化（切换 WPS版本/公报原版后），重新应用缩放
                        setInterval(function() {
                            try {
                                var iframe = document.querySelector('#previewIframe, iframe');
                                if (iframe && iframe.src !== currentIframeSrc) {
                                    currentIframeSrc = iframe.src;
                                    
                                    setTimeout(function() {
                                        try {
                                            var newIframe = document.querySelector('#previewIframe, iframe');
                                            if (newIframe) {
                                                var origWidth = 750;
                                                var newScale = window.innerWidth / origWidth;
                                                var fHeight = 44;
                                                var fArea = document.querySelector('.func-area');
                                                if (fArea) {
                                                    fHeight = fArea.offsetHeight || 44;
                                                }
                                                
                                                newIframe.style.width = origWidth + 'px';
                                                newIframe.style.height = ((window.innerHeight - fHeight) / newScale) + 'px';
                                                newIframe.style.minHeight = ((window.innerHeight - fHeight) / newScale) + 'px';
                                                newIframe.style.transform = 'scale(' + newScale + ')';
                                                newIframe.style.transformOrigin = 'top left';
                                                newIframe.style.border = 'none';
                                                newIframe.style.display = 'block';
                                                newIframe.style.margin = '0';
                                                newIframe.style.padding = '0';
                                                
                                                var iParent = newIframe.parentElement;
                                                if (iParent) {
                                                    iParent.style.marginTop = fHeight + 'px';
                                                    iParent.style.height = (window.innerHeight - fHeight) + 'px';
                                                    iParent.style.minHeight = (window.innerHeight - fHeight) + 'px';
                                                }
                                            }
                                        } catch(e) {
                                            console.error('Re-apply scale error:', e);
                                        }
                                    }, 1500);
                                }
                            } catch(e) {
                                console.error('Interval check error:', e);
                            }
                        }, 2000);
                        
                    } catch(e) {
                        console.error('Reader mode error:', e);
                    }
                };
                
                window.__tryReaderOnlyMode();
            } catch(e) {
                console.error('Inject error:', e);
            }
        })()
    """.trimIndent()

    view.evaluateJavascript(js, null)
}
