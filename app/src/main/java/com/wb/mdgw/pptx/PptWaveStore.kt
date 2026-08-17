package com.wb.mdgw.pptx

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PPTX 波浪装饰可调参数的本地持久化（与 PptStyleStore 同机制）。
 *
 * 用户在「波浪参数」弹窗中调整的浪高 / 透明度 / 层次对比会落盘到本文件；
 * 下一次进入 PPTX tab 时自动恢复。未保存 = 使用出厂默认（v1.7.9，保底）。
 * 「恢复默认」即删除本文件（delete）。
 *
 * 纯本地、零网络。
 */
object PptWaveStore {

    private const val WAVE_FILE = "pptx_wave.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    private data class WaveHolder(val p: PptWaveParams = PptWaveParams())

    /** 保存波浪参数（出厂默认即视为「未自定义」）。 */
    fun save(context: Context, p: PptWaveParams) {
        runCatching {
            val txt = json.encodeToString(WaveHolder.serializer(), WaveHolder(p))
            context.openFileOutput(WAVE_FILE, Context.MODE_PRIVATE).use { it.write(txt.toByteArray()) }
        }
    }

    /** 读取已保存的波浪参数；无文件或损坏时返回出厂默认（保底）。 */
    fun load(context: Context): PptWaveParams {
        val file = File(context.filesDir, WAVE_FILE)
        if (!file.exists() || file.length() == 0L) return PptWaveParams()
        return runCatching { json.decodeFromString<WaveHolder>(file.readText()).p }.getOrDefault(PptWaveParams())
    }

    /** 恢复默认：删除参数文件。 */
    fun clear(context: Context) {
        runCatching { context.deleteFile(WAVE_FILE) }
    }
}
