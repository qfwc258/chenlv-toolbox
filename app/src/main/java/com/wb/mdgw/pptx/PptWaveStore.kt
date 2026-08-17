package com.wb.mdgw.pptx

import android.content.Context
import com.wb.mdgw.JsonFileStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * PPTX 波浪装饰可调参数的本地持久化（与 PptStyleStore 同一套机制）。
 *
 * 用户在「波浪参数」弹窗中调整的浪高 / 透明度 / 层次对比会落盘到本文件；
 * 下一次进入 PPTX tab 时自动恢复。未保存 = 使用出厂默认（v1.7.9，保底）。
 * 「恢复默认」即删除本文件（delete）。
 *
 * 纯本地、零网络。复用 [JsonFileStore]。
 */
object PptWaveStore : JsonFileStore<PptWaveStore.WaveHolder>() {

    override val fileName: String = "pptx_wave.json"

    override fun serializer(): KSerializer<WaveHolder> = WaveHolder.serializer()

    @Serializable
    data class WaveHolder(val p: PptWaveParams = PptWaveParams())

    /** 保存波浪参数（出厂默认即视为「未自定义」）。 */
    fun save(context: Context, p: PptWaveParams) = write(context, WaveHolder(p))

    /** 读取已保存的波浪参数；无文件或损坏时返回出厂默认（保底）。 */
    fun load(context: Context): PptWaveParams = read(context)?.p ?: PptWaveParams()
}
