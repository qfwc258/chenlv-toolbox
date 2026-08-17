package com.wb.mdgw.pptx

import kotlin.math.roundToInt

/**
 * 波浪装饰配色纯函数：
 * 基于主色调派生三种波浪颜色，供 [PptLayoutEngine.generateWaveLayers] 等调用。
 * 与 PptLayoutEngine 解耦，方便独立单测（无 Android/引擎状态依赖）。
 *
 * 设计原则（由后到前 / 由浅入深）：
 * - 顶层（前层 / 最小面积）= 主色调原色，不提亮不压暗，最醒目
 * - 中间层 = 在主色调基础上压暗（×0.70），比顶层更深，制造深色纵深
 * - 最下层（后层 / 最大面积）= 在主色调基础上大幅提亮，比顶层更浅，如远处薄浪
 *
 * @param hex 主色调 hex（如 "2E5FA3"）
 * @param contrast 层次对比倍率（默认 1.0；调高=明暗更极端，调低=更接近主色）
 * @return [浅色(最下层/最大面积), 深色(中间层), 主色(顶层/最小面积)]
 */
internal fun deriveWaveColors(hex: String, contrast: Float = 1.0f): List<String> {
    val r = hex.substring(0, 2).toInt(16)
    val g = hex.substring(2, 4).toInt(16)
    val b = hex.substring(4, 6).toInt(16)

    // 顶层（最小面积 / 前层）：直接使用主色调，不提亮不压暗 → 最醒目、压底收尾
    val main = listOf(r, g, b)

    // 中间层（中等面积）：在主色调基础上压暗（出厂 ×0.70，即压暗 0.30），
    // 再按 UI「层次对比」倍率缩放压暗幅度（对比调高=更深，调低=更接近主色）
    val darkMult = (1f - 0.30f * contrast).coerceIn(0f, 1f)
    val d = listOf(
        (r * darkMult).roundToInt(),
        (g * darkMult).roundToInt(),
        (b * darkMult).roundToInt(),
    ).map { it.coerceIn(0, 255) }

    // 最下层（最大面积 / 后层）：在主色调基础上提亮（出厂 r/g/b 提亮 0.78/0.78/0.76），
    // 再按 UI「层次对比」倍率缩放提亮幅度（对比调高=更浅更通透，调低=更接近主色）
    val lf = listOf(0.78f, 0.78f, 0.76f).map { (it * contrast).coerceIn(0f, 1f) }
    val l = listOf(
        r + ((255 - r) * lf[0]).roundToInt(),
        g + ((255 - g) * lf[1]).roundToInt(),
        b + ((255 - b) * lf[2]).roundToInt(),
    ).map { it.coerceIn(0, 255) }

    return listOf(
        "%02X%02X%02X".format(l[0], l[1], l[2]),        // idx0 最下层：浅
        "%02X%02X%02X".format(d[0], d[1], d[2]),        // idx1 中间层：深
        "%02X%02X%02X".format(main[0], main[1], main[2]), // idx2 顶层：主色调
    )
}