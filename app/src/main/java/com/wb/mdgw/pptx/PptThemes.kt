package com.wb.mdgw.pptx

import androidx.compose.ui.graphics.Color

/**
 * 三套商用级主题（全局字号/行距/边距由 PptSpec 锁定，主题只管配色）。
 *
 * 1. 商务深蓝 — 正式汇报首选
 * 2. 简约灰白 — 通用办公
 * 3. 政务红   — 公文/汇报
 */
object PptThemes {
    val ALL: List<PptTheme> = listOf(
        PptTheme(
            id = "navy",
            name = "商务深蓝",
            bg = "FFFFFF",
            titleColor = "1A3C6E",
            bodyColor = "333333",
            accent = "2E5FA3",
            codeBg = "EEF2F8",
            quoteBg = "EAF1F8",
            coverBg = "1A3C6E"
        ),
        PptTheme(
            id = "gray",
            name = "简约灰白",
            bg = "FFFFFF",
            titleColor = "222222",
            bodyColor = "444444",
            accent = "777777",
            codeBg = "F2F2F2",
            quoteBg = "F2F2F2",
            coverBg = "7B7B7B"
        ),
        PptTheme(
            id = "gov",
            name = "政务红",
            bg = "FFFFFF",
            titleColor = "9E2A2B",
            bodyColor = "333333",
            accent = "C0392B",
            codeBg = "F7ECEC",
            quoteBg = "F7ECEC",
            coverBg = "9E2A2B"
        )
    )

    fun byId(id: String): PptTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** 自定义主色调主题：accent/封面色块/引用条均用用户所选主色，标题与正文保持深灰确保白底可读。 */
    fun custom(hex: String): PptTheme = PptTheme(
        id = "custom",
        name = "自定义主色",
        bg = "FFFFFF",
        titleColor = "222222",
        bodyColor = "333333",
        accent = hex,
        codeBg = "F2F2F2",
        quoteBg = "F2F2F2",
        coverBg = hex
    )

    /** 自定义主色的预设调色板（点击即可设为专属主色调）。 */
    val CUSTOM_PALETTE: List<String> = listOf(
        "C0392B", "E67E22", "F1C40F", "27AE60",
        "16A085", "2980B9", "2E5FA3", "8E44AD",
        "D81B60", "795548", "607D8B", "2C3E50"
    )
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
