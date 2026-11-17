package com.wb.mdgw

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import kotlinx.serialization.Serializable

/**
 * 三个 Markdown 编辑器（WORD / PPTX / 公众号）之间的内容中转。
 *
 * 「发送到 XX」把当前 Markdown 文本写入本暂存（指定目标），目标编辑器下次进入时
 * 弹「替换 / 追加 / 忽略」；一次性投递，导入或忽略后清除。暂存落盘，跨页面/重开仍在。
 */
object MarkdownExchange {

    const val WORD = "word"
    const val PPTX = "pptx"
    const val WECHAT = "wechat"

    @Serializable
    data class Payload(
        val text: String = "",
        val source: String = "",   // 发送方：word / pptx / wechat
        val target: String = "",   // 接收方：word / pptx / wechat
        val timestamp: Long = 0L
    )

    /** 每个目标编辑器独立一份暂存，避免「发给 PPTX」被随后「发给公众号」静默覆盖 */
    private class TargetStore(override val fileName: String) : JsonFileStore<Payload>() {
        override fun serializer() = Payload.serializer()
        fun save(c: Context, p: Payload) = write(c, p)
        fun load(c: Context): Payload? = read(c)
        fun reset(c: Context) = clear(c)
    }

    private val stores = mapOf(
        WORD to TargetStore("markdown_exchange_word.json"),
        PPTX to TargetStore("markdown_exchange_pptx.json"),
        WECHAT to TargetStore("markdown_exchange_wechat.json")
    )

    private fun storeFor(target: String): TargetStore = stores[target] ?: stores.getValue(WORD)

    fun sourceName(s: String): String = when (s) {
        WORD -> "WORD"
        PPTX -> "PPTX"
        WECHAT -> "公众号"
        else -> "其他编辑器"
    }

    /**
     * 把 [text] 发送给 [target] 编辑器。
     * @return true 表示目标已有「未导入」的内容被本次覆盖（UI 应提示用户）。
     */
    fun send(context: Context, source: String, target: String, text: String): Boolean {
        if (text.isBlank()) return false
        val st = storeFor(target)
        val overwritten = st.load(context)?.let { it.target == target && it.source != source && it.text.isNotBlank() } ?: false
        st.save(context, Payload(text.trim(), source, target, System.currentTimeMillis()))
        return overwritten
    }

    /** 读取发往 [target] 的暂存（不清除）；无内容返回 null */
    fun peek(context: Context, target: String): Payload? = storeFor(target).load(context)

    /** 清除发往 [target] 的暂存（导入或忽略后调用） */
    fun consume(context: Context, target: String) = storeFor(target).reset(context)

    /**
     * 进入 [self] 编辑器时应自动提示的暂存：目标必须是自己、来源不是自己、文本非空。
     */
    fun pendingFor(context: Context, self: String): Payload? {
        val p = storeFor(self).load(context) ?: return null
        return if (p.target == self && p.source != self && p.text.isNotBlank()) p else null
    }
}

/**
 * 跨编辑器导入 Markdown 的统一确认对话框：替换当前内容 / 追加到末尾 / 忽略。
 */
@androidx.compose.runtime.Composable
fun MarkdownExchangeDialog(
    sourceName: String,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 Markdown") },
        text = { Text("检测到来自【$sourceName】的 Markdown 内容，是否导入？") },
        confirmButton = {
            TextButton(onClick = onReplace) { Text("替换当前") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onAppend) { Text("追加末尾") }
                TextButton(onClick = onDismiss) { Text("忽略") }
            }
        }
    )
}
