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

    private fun injectReaderOnlyMode(view: WebView) {
        val js = """
            (function() {
                try {
                    window.__tryReaderOnlyMode = function() {
                        try {
                            var reader = null;
                            var readerType = '';
                            
                            // 0. 优先通过 id 找到 OFD 阅读器 iframe（已知 id 为 previewIframe）
                            var previewIframe = document.getElementById('previewIframe');
                            if (previewIframe) {
                                reader = previewIframe;
                                readerType = 'previewIframe';
                            }
                            
                            // 1. 优先找 iframe（WPS 在线预览通常是 iframe）
                            if (!reader) {
                            var iframes = document.querySelectorAll('iframe');
                            if (iframes.length > 0) {
                                var maxArea = 0;
                                for (var i = 0; i < iframes.length; i++) {
                                    var area = iframes[i].offsetWidth * iframes[i].offsetHeight;
                                    if (area > maxArea && area > 5000) {
                                        maxArea = area;
                                        reader = iframes[i];
                                        readerType = 'iframe';
                                    }
                                }
                            }
                            }
                            
                            // 2. 其次找 canvas（OFD 阅读器通常用 canvas）
                            if (!reader) {
                                var canvases = document.querySelectorAll('canvas');
                                if (canvases.length > 0) {
                                    var maxArea2 = 0;
                                    for (var j = 0; j < canvases.length; j++) {
                                        var area2 = canvases[j].offsetWidth * canvases[j].offsetHeight;
                                        if (area2 > maxArea2 && area2 > 5000) {
                                            maxArea2 = area2;
                                            reader = canvases[j];
                                            readerType = 'canvas';
                                        }
                                    }
                                }
                            }
                            
                            // 3. 找包含法规特征文字的最大元素
                            if (!reader) {
                                var allElements = document.querySelectorAll('div, section, article');
                                var maxArea3 = 0;
                                for (var k = 0; k < allElements.length; k++) {
                                    var el = allElements[k];
                                    var area3 = el.offsetWidth * el.offsetHeight;
                                    var text = el.textContent || '';
                                    // 检查是否包含法规特征：第X条、第X章、目录、页码格式(数字/数字)
                                    var hasLawFeature = /第[一二三四五六七八九十百千0-9]+[条章节篇编]/.test(text) || 
                                                        text.indexOf('目录') >= 0 || 
                                                        /\d+\s*\/\s*\d+/.test(text);
                                    if (area3 > maxArea3 && area3 > window.innerWidth * window.innerHeight * 0.15 && hasLawFeature) {
                                        maxArea3 = area3;
                                        reader = el;
                                        readerType = 'law-text';
                                    }
                                }
                            }
                            
                            // 4. 最后找页面中面积最大的 div
                            if (!reader) {
                                var allDivs = document.querySelectorAll('div');
                                var maxArea4 = 0;
                                for (var l = 0; l < allDivs.length; l++) {
                                    var el2 = allDivs[l];
                                    var area4 = el2.offsetWidth * el2.offsetHeight;
                                    if (area4 > maxArea4 && area4 > window.innerWidth * window.innerHeight * 0.3) {
                                        maxArea4 = area4;
                                        reader = el2;
                                        readerType = 'largest-div';
                                    }
                                }
                            }
                            
                            if (!reader) {
                                // 没有找到，1秒后重试（最多重试20次）
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
                            
                            // 找到从 body 到 reader 的路径
                            var path = [];
                            var current = reader;
                            while (current && current !== document.body) {
                                path.unshift(current);
                                current = current.parentElement;
                            }
                            
                            // 从 body 开始，只保留路径上的元素，隐藏其他兄弟元素
                            // 但保留 func-area 区域（包含下载按钮和 WPS版本/公报原版标签），用户直接点击下载
                            function keepOnlyPath(parent, pathIndex) {
                                if (pathIndex >= path.length) return;
                                var target = path[pathIndex];
                                var children = parent.children;
                                for (var i = 0; i < children.length; i++) {
                                    var child = children[i];
                                    if (child === target) {
                                        keepOnlyPath(child, pathIndex + 1);
                                    } else {
                                        // 检查是否是 func-area 区域或包含 func-area
                                        var childClass = child.className || '';
                                        var isFuncArea = typeof childClass === 'string' && childClass.indexOf('func-area') >= 0;
                                        var hasFuncArea = child.querySelector && child.querySelector('.func-area');
                                        
                                        if (isFuncArea || hasFuncArea) {
                                            // 保留 func-area 区域，调整样式适配手机
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
                                            // 隐藏其他非路径上的元素
                                            child.style.display = 'none';
                                        }
                                    }
                                }
                            }
                            keepOnlyPath(document.body, 0);
                            
                            // 设置 body 和 html 为全屏
                            document.documentElement.style.margin = '0';
                            document.documentElement.style.padding = '0';
                            document.documentElement.style.overflow = 'hidden';
                            document.body.style.margin = '0';
                            document.body.style.padding = '0';
                            document.body.style.overflow = 'hidden';
                            document.body.style.background = '#fff';
                            
                            // 设置路径上所有元素为全屏宽度
                            for (var m = 0; m < path.length; m++) {
                                var el = path[m];
                                el.style.width = '100%';
                                el.style.maxWidth = '100%';
                                el.style.margin = '0';
                                el.style.padding = '0';
                                el.style.boxSizing = 'border-box';
                                el.style.overflow = 'auto';
                            }
                            
                            // 设置 reader 为全屏
                            reader.style.width = '100%';
                            reader.style.height = '100vh';
                            reader.style.maxWidth = '100%';
                            reader.style.display = 'block';
                            reader.style.border = 'none';
                            
                            // 适配手机宽度：如果是 iframe，直接设置全屏；否则遍历内部元素缩放
                            setTimeout(function() {
                                try {
                                    if (reader.tagName === 'IFRAME') {
                                        // iframe 是跨域的 OFD 阅读器
                                        // 目标：显示宽度=手机宽度，显示高度=手机高度（拉满至底部）
                                        // 方案：设置 iframe 原始宽度为 750px，用 transform 缩放到手机宽度
                                        // OFD 内容实际宽度约 700px，设置 750px 让内容拉满手机宽度
                                        var originalWidth = 750; // OFD 内容原始宽度
                                        var scale = window.innerWidth / originalWidth;
                                        
                                        // 找到 iframe 的直接父容器
                                        var iframeParent = reader.parentElement;
                                        
                                        // func-area 原始高度约 44px，与 iframe 同缩放后的显示高度
                                        var funcAreaOriginalHeight = 44;
                                        var funcAreaDisplayHeight = funcAreaOriginalHeight * scale;
                                        
                                        if (iframeParent) {
                                            // 设置父容器为全屏（减去 func-area 缩放后的高度），overflow hidden
                                            iframeParent.style.position = 'relative';
                                            iframeParent.style.width = '100%';
                                            iframeParent.style.height = (window.innerHeight - funcAreaDisplayHeight) + 'px';
                                            iframeParent.style.minHeight = (window.innerHeight - funcAreaDisplayHeight) + 'px';
                                            iframeParent.style.overflow = 'hidden';
                                            iframeParent.style.margin = '0';
                                            iframeParent.style.padding = '0';
                                            iframeParent.style.marginTop = funcAreaDisplayHeight + 'px';
                                        }
                                        
                                        // 设置 iframe 原始尺寸（缩放前），高度减去 func-area 缩放后的高度
                                        reader.style.width = originalWidth + 'px';
                                        reader.style.height = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                                        reader.style.minHeight = ((window.innerHeight - funcAreaDisplayHeight) / scale) + 'px';
                                        reader.style.border = 'none';
                                        reader.style.display = 'block';
                                        reader.style.margin = '0';
                                        reader.style.padding = '0';
                                        
                                        // 用 CSS transform 缩放 iframe，使其显示宽度=手机宽度
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
                                        
                                        // 设置 body 和 html 为全屏，但允许 func-area 区域显示在顶部
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
                                        
                                        // func-area 区域与 iframe 同缩放
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
                                        var currentIframeSrc = reader.src;
                                        
                                        // 定期检查：1. 显示下载弹出框 2. 检测 iframe 变化并重新缩放
                                        setInterval(function() {
                                            try {
                                                // 1. 显示下载弹出框
                                                var popups = document.querySelectorAll('.el-tooltip__popper, [class*="dropdown"], [class*="popover"], [class*="el-popper"]');
                                                for (var i = 0; i < popups.length; i++) {
                                                    var popup = popups[i];
                                                    var popupText = (popup.textContent || '').trim();
                                                    if (popupText.indexOf('下载') >= 0 || popupText.indexOf('WPS') >= 0 || popupText.indexOf('公报') >= 0) {
                                                        popup.style.display = 'block';
                                                        popup.style.visibility = 'visible';
                                                        popup.style.opacity = '1';
                                                        popup.style.zIndex = '9999';
                                                        var popupRect = popup.getBoundingClientRect();
                                                        if (popupRect.top < 0) {
                                                            popup.style.top = funcAreaHeight + 'px';
                                                        }
                                                        if (popupRect.left < 0) {
                                                            popup.style.left = '10px';
                                                        }
                                                        if (popupRect.right > window.innerWidth) {
                                                            popup.style.left = (window.innerWidth - popupRect.width - 10) + 'px';
                                                        }
                                                    }
                                                }
                                                
                                                // 2. 检测 iframe src 变化（切换 WPS版本/公报原版后），重新应用缩放
                                                var iframe = document.querySelector('#previewIframe, iframe');
                                                if (iframe && iframe.src !== currentIframeSrc) {
                                                    currentIframeSrc = iframe.src;
                                                    console.log('iframe src changed, re-applying scale...');
                                                    
                                                    // 延迟重新应用缩放，等待新内容加载
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
                                                    }, 1000);
                                                }
                                                
                                                // 3. 确保下载按钮可见且可点击
                                                var downloadBtn = document.querySelector('.download, [class*="download"]');
                                                if (downloadBtn) {
                                                    downloadBtn.style.display = '';
                                                    downloadBtn.style.visibility = 'visible';
                                                    downloadBtn.style.opacity = '1';
                                                    downloadBtn.style.pointerEvents = 'auto';
                                                    downloadBtn.style.cursor = 'pointer';
                                                }
                                            } catch(e) {}
                                        }, 500);
                                    } else {
                                        // 非 iframe 情况：遍历 reader 内所有元素，找到宽度大于屏幕的元素并缩放
                                        var allElements = reader.querySelectorAll('*');
                                        var scaledCount = 0;
                                        
                                        for (var i = 0; i < allElements.length; i++) {
                                            var el = allElements[i];
                                            // 只处理有实际宽度且大于屏幕宽度的元素
                                            if (el.offsetWidth > window.innerWidth && el.offsetHeight > 50) {
                                                // 计算缩放比例（留10px边距）
                                                var scale = (window.innerWidth - 10) / el.offsetWidth;
                                                
                                                // 用 transform 缩放
                                                el.style.transform = 'scale(' + scale + ')';
                                                el.style.transformOrigin = 'top center';
                                                el.style.margin = '0 auto';
                                                el.style.display = 'block';
                                                
                                                // 计算缩放后的高度
                                                var scaledHeight = el.offsetHeight * scale;
                                                
                                                // 给元素添加一个占位兄弟元素，撑起缩放后的高度
                                                if (!el.nextElementSibling || !el.nextElementSibling.classList.contains('scale-placeholder')) {
                                                    var placeholder = document.createElement('div');
                                                    placeholder.className = 'scale-placeholder';
                                                    placeholder.style.height = scaledHeight + 'px';
                                                    placeholder.style.width = '100%';
                                                    placeholder.style.pointerEvents = 'none';
                                                    el.parentNode.insertBefore(placeholder, el.nextSibling);
                                                } else {
                                                    el.nextElementSibling.style.height = scaledHeight + 'px';
                                                }
                                                
                                                scaledCount++;
                                            }
                                        }
                                    }
                                    
                                    // 确保 reader 可以滚动
                                    reader.style.overflow = 'auto';
                                    reader.style.webkitOverflowScrolling = 'touch';
                                    
                                    // 触发 resize 事件
                                    window.dispatchEvent(new Event('resize'));
                                    
                                } catch(e) {
                                    console.log('adapt mobile width error:', e);
                                }
                            }, 2000);
                            
                            // 添加 viewport meta 标签
                            var viewport = document.querySelector('meta[name="viewport"]');
                            if (!viewport) {
                                viewport = document.createElement('meta');
                                viewport.setAttribute('name', 'viewport');
                                document.head.appendChild(viewport);
                            }
                            viewport.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes');
                            
                            // 延迟触发 resize 事件
                            setTimeout(function() {
                                window.dispatchEvent(new Event('resize'));
                            }, 500);
                            
                        } catch(e) {
                            console.log('readerOnlyMode error:', e);
                        }
                    };
                    
                    window.__tryReaderOnlyMode();
                    
                } catch(e) {
                    console.log('injectReaderOnlyMode error:', e);
                }
            })();
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
