package com.wb.mdgw.pptx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护波浪配色纯函数 [deriveWaveColors]：
 * 抽离自 PptLayoutEngine 后，用不可变参数化样例锁定配色派生行为，
 * 防止后续修改主色派生逻辑时破坏「主色调保留 / 明暗层次」的视觉契约。
 */
class PptWaveColorTest {

    @Test
    fun `返回恰好三种颜色且均为大写 HEX`() {
        val colors = deriveWaveColors("2E5FA3")
        assertEquals(3, colors.size)
        colors.forEach {
            assertTrue(it.matches(Regex("[0-9A-F]{6}")))
        }
    }

    @Test
    fun `顶层始终等于主色`() {
        assertEquals("2E5FA3", deriveWaveColors("2E5FA3")[2])
        assertEquals("000000", deriveWaveColors("000000")[2])
        assertEquals("FFFFFF", deriveWaveColors("FFFFFF")[2])
    }

    @Test
    fun `对比度为 0 时三层全部回到主色`() {
        assertEquals(listOf("2E5FA3", "2E5FA3", "2E5FA3"), deriveWaveColors("2E5FA3", contrast = 0f))
    }

    @Test
    fun `分层满足亮暗次序`() {
        // 给定主色，按通道解析后：最下层(浅) ≥ 主色 ≥ 中间层(深)
        val (light, dark, main) = deriveWaveColors("2E5FA3").map { hex ->
            listOf(hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6)).map { it.toInt(16) }
        }
        for (i in 0..2) {
            assertTrue("浅色第 $i 通道应 ≥ 主色", light[i] >= main[i])
            assertTrue("深色第 $i 通道应 ≤ 主色", dark[i] <= main[i])
        }
    }

    @Test
    fun `纯白主色的派生结果`() {
        // darkMult=0.7 → 255*0.7=178.5 → roundToInt=179=0xB3；提亮后保持 255
        assertEquals(listOf("FFFFFF", "B3B3B3", "FFFFFF"), deriveWaveColors("FFFFFF"))
    }
}