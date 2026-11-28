package com.wb.mdgw.notes

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONTokener

/**
 * 便签富文本编辑器：WebView contentEditable + execCommand 工具栏，完全离线可用。
 * 通过 JS Bridge 传递初始 HTML 与实时变更，避免把 HTML 拼进 JS 字符串导致的转义问题。
 */
internal class NoteJsBridge(
    private val initialHtml: String,
    private val onChange: (String) -> Unit
) {
    var pendingImage: String = ""

    @JavascriptInterface
    fun initialHtml(): String = initialHtml

    @JavascriptInterface
    fun contentChanged(html: String) = onChange(html)

    @JavascriptInterface
    fun takeImage(): String {
        val value = pendingImage
        pendingImage = ""
        return value
    }
}

/** 富文本编辑器句柄：供父级插入图片、读取当前 HTML */
class RichTextController internal constructor() {
    internal var webView: WebView? = null
    internal var bridge: NoteJsBridge? = null

    /** 插入 base64 图片到光标处 */
    fun insertImage(dataUri: String) {
        bridge?.pendingImage = dataUri
        webView?.evaluateJavascript("insertPendingImage()", null)
    }

    /** 读取当前 HTML（回调在主线程） */
    fun currentHtml(onResult: (String) -> Unit) {
        webView?.evaluateJavascript("getHtml()") { raw ->
            val html = runCatching { JSONTokener(raw).nextValue() as String }.getOrDefault("")
            onResult(html)
        } ?: onResult("")
    }

    fun focus() {
        webView?.evaluateJavascript("focusEditor()", null)
    }
}

@Composable
fun rememberRichTextController(): RichTextController = remember { RichTextController() }

private val EDIT_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
html,body{margin:0;padding:0;}
body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;font-size:15px;line-height:1.7;color:#222;}
#editor{min-height:260px;outline:none;padding:4px 6px;}
img{max-width:100%;height:auto;border-radius:6px;margin:6px 0;}
blockquote{border-left:3px solid #B3261E;margin:6px 0;padding-left:10px;color:#666;}
</style></head><body>
<div id="editor" contenteditable="true"></div>
<script>
var editor=document.getElementById('editor');
editor.innerHTML=window.NoteBridge.initialHtml();
editor.addEventListener('input',function(){window.NoteBridge.contentChanged(editor.innerHTML);});
function getHtml(){return editor.innerHTML;}
function focusEditor(){editor.focus();}
function exec(cmd,val){document.execCommand(cmd,false,val||null);editor.focus();window.NoteBridge.contentChanged(editor.innerHTML);}
function insertPendingImage(){var d=window.NoteBridge.takeImage();if(d){document.execCommand('insertImage',false,d);window.NoteBridge.contentChanged(editor.innerHTML);}editor.focus();}
</script></body></html>"""

private val VIEW_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;padding:0;}
body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;font-size:15px;line-height:1.75;color:#222;padding:4px 6px;}
img{max-width:100%;height:auto;border-radius:6px;margin:8px 0;}
h1,h2,h3{color:#8B2E2E;}
blockquote{border-left:3px solid #B3261E;margin:6px 0;padding-left:10px;color:#666;}
</style></head><body>__CONTENT__</body></html>"""

/** 富文本编辑区（WebView），配合 [RichTextToolbar] 使用 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichTextEditor(
    initialHtml: String,
    controller: RichTextController,
    onHtmlChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                val bridge = NoteJsBridge(initialHtml, onHtmlChange)
                addJavascriptInterface(bridge, "NoteBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("focusEditor()", null)
                    }
                }
                controller.bridge = bridge
                controller.webView = this
                loadDataWithBaseURL(null, EDIT_HTML, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
        update = { view ->
            // 便签切换时重建内容（同一 WebView 复用）
            if (controller.webView !== view) {
                val bridge = NoteJsBridge(initialHtml, onHtmlChange)
                view.addJavascriptInterface(bridge, "NoteBridge")
                controller.bridge = bridge
                controller.webView = view
                view.loadDataWithBaseURL(null, EDIT_HTML, "text/html", "utf-8", null)
            }
        }
    )
}

/** 富文本工具栏：加粗/斜体/下划线/标题/列表/对齐/红字/插图 */
@Composable
fun RichTextToolbar(
    onPickImage: () -> Unit,
    onCommand: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val tools = listOf(
        "加粗" to "bold", "斜体" to "italic", "下划线" to "underline",
        "标题" to "formatBlock:h2", "正文" to "formatBlock:p",
        "列表" to "insertUnorderedList", "编号" to "insertOrderedList",
        "引用" to "formatBlock:blockquote",
        "左" to "justifyLeft", "中" to "justifyCenter", "右" to "justifyRight"
    )
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        tools.forEach { (label, spec) ->
            FilterChip(
                selected = false,
                onClick = {
                    val parts = spec.split(":", limit = 2)
                    if (parts.size == 2) onCommand(parts[0], parts[1]) else onCommand(spec, "")
                },
                label = { Text(label, fontSize = 10.sp) },
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        FilterChip(
            selected = false,
            onClick = { onCommand("foreColor", "#B3261E") },
            label = { Text("红字", fontSize = 10.sp, color = ComposeColor(0xFFB3261E)) },
            modifier = Modifier.padding(end = 2.dp)
        )
        FilterChip(
            selected = false,
            onClick = onPickImage,
            label = { Text("插图", fontSize = 10.sp, color = primary) }
        )
    }
}

/** 只读富文本视图 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichTextView(html: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                loadDataWithBaseURL(null, VIEW_HTML.replace("__CONTENT__", html), "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
        update = { view ->
            view.loadDataWithBaseURL(null, VIEW_HTML.replace("__CONTENT__", html), "text/html", "utf-8", null)
        }
    )
}
