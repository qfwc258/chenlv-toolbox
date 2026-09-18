package com.wb.mdgw.law

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * TVBox 模式 - 后台 WebView 数据提取器
 *
 * 原理：用隐藏的 WebView 加载网站初始化环境（Cookie、localStorage 等），
 * 然后通过 evaluateJavascript 执行 fetch 调用搜索 API，
 * WebView 自动处理请求头、Cookie、CORS 等，模拟真实浏览器环境。
 */
class LawWebParser private constructor(context: Context) {

    private val webView: WebView
    private var isInitialized = false
    private val initLock = Any()

    init {
        webView = WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(0, 0)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = LawConstants.USER_AGENT
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    synchronized(initLock) {
                        isInitialized = true
                        (initLock as Object).notifyAll()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
            webChromeClient = WebChromeClient()
        }
    }

    /**
     * 初始化 WebView 环境（加载网站首页）
     */
    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized) return@withContext true

        val deferred = CompletableDeferred<Boolean>()
        synchronized(initLock) {
            if (isInitialized) {
                deferred.complete(true)
                return@withContext true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                synchronized(initLock) {
                    isInitialized = true
                }
                deferred.complete(true)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    deferred.complete(false)
                }
            }
        }

        webView.loadUrl(LawConstants.OFFICIAL_URL)

        withTimeoutOrNull(10000) {
            deferred.await()
        } ?: false
    }

    /**
     * 搜索法规
     *
     * @param keyword 搜索关键词
     * @param page 页码（从 1 开始）
     * @param pageSize 每页数量
     * @return Result<Pair<List<Law>, Int>> 列表和总数
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        pageSize: Int = LawConstants.PAGE_SIZE
    ): Result<Pair<List<Law>, Int>> = withContext(Dispatchers.Main) {
        if (!ensureInitialized()) {
            return@withContext Result.Error("网络初始化失败，请检查网络连接")
        }

        val escapedKeyword = keyword.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        val script = """
            (function() {
                window.__searchResult = null;
                window.__searchError = null;
                fetch('/law-search/search/list', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'application/json'
                    },
                    body: JSON.stringify({
                        searchRange: 1,
                        searchType: 2,
                        searchContent: "$escapedKeyword",
                        pageNum: $page,
                        pageSize: $pageSize
                    })
                })
                .then(function(response) {
                    if (!response.ok) {
                        throw new Error('HTTP ' + response.status);
                    }
                    return response.json();
                })
                .then(function(data) {
                    window.__searchResult = JSON.stringify(data);
                })
                .catch(function(error) {
                    window.__searchError = error.message || 'Unknown error';
                });
                return 'started';
            })()
        """.trimIndent()

        webView.evaluateJavascript(script, null)

        val result = pollForResult(timeoutMs = 15000)
        if (result != null) {
            return@withContext parseSearchResult(result)
        }

        val error = getJavascriptValue("window.__searchError")
        if (error != null && error != "null") {
            return@withContext Result.Error("搜索失败: $error")
        }

        Result.Error("搜索超时，请稍后重试")
    }

    /**
     * 轮询等待 JS 执行结果
     */
    private suspend fun pollForResult(timeoutMs: Long): String? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val value = getJavascriptValue("window.__searchResult")
            if (value != null && value != "null" && value.isNotEmpty()) {
                return value
            }
            delay(200)
        }
        return null
    }

    /**
     * 获取 JS 变量值
     */
    private suspend fun getJavascriptValue(expression: String): String? {
        val deferred = CompletableDeferred<String?>()
        webView.evaluateJavascript(expression) { result ->
            deferred.complete(result)
        }
        return withTimeoutOrNull(3000) {
            deferred.await()
        }
    }

    /**
     * 解析搜索结果 JSON
     */
    private fun parseSearchResult(jsonString: String): Result<Pair<List<Law>, Int>> {
        return try {
            val cleanedJson = jsonString.removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\")

            val json = JSONObject(cleanedJson)
            val code = json.optInt("code", -1)
            val msg = json.optString("msg", "")

            if (code != 200) {
                return Result.Error("搜索失败: $msg")
            }

            val total = json.optInt("total", 0)
            val rows = json.optJSONArray("rows")
            val laws = mutableListOf<Law>()

            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val bbbs = row.optString("bbbs", "")
                    val title = row.optString("title", "")
                        .replace(Regex("<[^>]+>"), "") // 移除高亮标签
                        .trim()
                    val gbrq = row.optString("gbrq", "")
                    val sxrq = row.optString("sxrq", "")
                    val sxx = row.optInt("sxx", 0)
                    val zdjgName = row.optString("zdjgName", "")
                    val flxz = row.optString("flxz", "")

                    if (bbbs.isNotEmpty() && title.isNotEmpty()) {
                        laws.add(
                            Law(
                                id = bbbs,
                                title = title,
                                publishDate = gbrq,
                                effectiveDate = sxrq,
                                issuingAuthority = zdjgName,
                                lawLevel = flxz,
                                status = if (sxx > 0) sxx else null,
                                detailUrl = "${LawConstants.OFFICIAL_URL}detail?title=${java.net.URLEncoder.encode(title, "UTF-8")}&id=$bbbs"
                            )
                        )
                    }
                }
            }

            Result.Success(laws to total)
        } catch (e: Exception) {
            Result.Error("解析搜索结果失败: ${e.message}")
        }
    }

    companion object {
        @Volatile
        private var instance: LawWebParser? = null

        fun getInstance(context: Context): LawWebParser {
            return instance ?: synchronized(this) {
                instance ?: LawWebParser(context.applicationContext).also { instance = it }
            }
        }
    }
}
