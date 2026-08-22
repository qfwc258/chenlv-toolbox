package com.wb.mdgw.pptx

import androidx.compose.ui.graphics.Color

/**
 * PPTX 配色统一由「单一主色调」驱动（tone 化改造，替代原先的三套预设主题）。
 *
 * 三套预设主题（商务深蓝/简约灰白/政务红）本质只是三种主色调，与自定义色板重复，
 * 现已去掉「主题」概念：全部配色由用户所选主色调派生，全局贯穿一致。
 */
object PptThemes {
    /** 默认主色调（商务蓝） */
    const val DEFAULT_TONE: String = "2E5FA3"

    /** 预设主色调色板（点选即设定整套 PPTX 的主色调）。 */
    val CUSTOM_PALETTE: List<String> = listOf(
        "C0392B", "E67E22", "F1C40F", "27AE60",
        "16A085", "2980B9", "2E5FA3", "8E44AD",
        "D81B60", "795548", "607D8B", "2C3E50"
    )

    /**
     * 由主色调生成整套 PPTX 配色：
     *  - accent / 封面 / 章节色块 = 主色
     *  - 标题色 = 主色深色化（白底上保持可读与层次）
     *  - 代码块 / 引用底 = 主色浅色底
     *  - 正文保持深灰，白底可读
     */
    fun fromTone(hex: String): PptTheme {
        val t = normalize(hex) ?: DEFAULT_TONE
        return PptTheme(
            id = "custom",
            name = "自定义主色",
            bg = "FFFFFF",
            titleColor = darken(t, 0.70f),
            bodyColor = "333333",
            accent = t,
            codeBg = mixWhite(t, 0.90f),
            quoteBg = mixWhite(t, 0.90f),
            coverBg = t
        )
    }

    /** 归一化 hex：去掉 # 与非法字符；非法输入返回 null */
    private fun normalize(hex: String): String? {
        val h = hex.trim().removePrefix("#")
        return if (h.length == 6 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) h.uppercase() else null
    }

    /** 主色深色化（各通道按 factor 缩放），返回 RRGGBB */
    fun darken(hex: String, factor: Float): String = channel(hex) { (it * factor).toInt().coerceIn(0, 255) }

    /** 主色与白色按 ratio 混合（ratio 越大越浅），返回 RRGGBB */
    fun mixWhite(hex: String, whiteRatio: Float): String {
        val t = normalize(hex) ?: return "F2F2F2"
        return (0 until 3).joinToString("") { i ->
            val c = t.substring(i * 2, i * 2 + 2).toInt(16)
            val w = (255 * whiteRatio).toInt()
            ((c * (1 - whiteRatio) + w).toInt().coerceIn(0, 255)).toString(16).padStart(2, '0').uppercase()
        }
    }

    private fun channel(hex: String, f: (Int) -> Int): String {
        val t = normalize(hex) ?: return "222222"
        return (0 until 3).joinToString("") { i ->
            val c = t.substring(i * 2, i * 2 + 2).toInt(16)
            f(c).toString(16).padStart(2, '0').uppercase()
        }
    }
}

/** hex RRGGBB → Compose Color */
fun hexToColor(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    return try {
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        Color(r, g, b)
    } catch (_: Exception) {
        Color.Black
    }
}

/** 判断背景色是否偏亮（相对亮度法），用于决定封面/强调背景上的前景文字颜色。 */
fun isLight(hex: String): Boolean {
    val h = hex.trim().removePrefix("#")
    return try {
        val r = h.substring(0, 2).toInt(16) / 255.0
        val g = h.substring(2, 4).toInt(16) / 255.0
        val b = h.substring(4, 6).toInt(16) / 255.0
        // 相对亮度（sRGB 近似）
        val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
        lum > 0.6
    } catch (_: Exception) {
        false
    }
}

/** hex → 0xRRGGBB Int（用于无头导出着色） */
fun hexInt(hex: String): Int {
    val h = hex.trim().removePrefix("#")
    return try {
        h.toInt(16)
    } catch (_: Exception) {
        0xFF000000.toInt()
    }
}
