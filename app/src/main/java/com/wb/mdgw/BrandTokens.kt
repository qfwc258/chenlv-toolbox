package com.wb.mdgw

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 陈律工具箱 · 品牌设计令牌
 *
 * 设计意图：
 *  - 调色：朱砂红 + 暖纸 + 金棕 = 中国法律人视觉语言，已在 MdGwTheme 内通过
 *    MaterialTheme.colorScheme 暴露给所有屏；额外在 [BrandTokens] 里集中「语义色」，
 *    避免散落硬编码。
 *  - 字版：默认 Material3 用的是 Roboto / Noto Sans CJK SC，对正文够用但「专业感」
 *    不够。本文件显式覆盖 titleLarge / titleMedium / titleSmall / bodyLarge / labelLarge，
 *    强化标题的字重、字距、行高；中文长文案用稍紧的字距显书卷气。
 *  - 形状：法律文书卡片以「纸页」为意象 — 直角微圆 (8dp)，比 Material3 默认 12dp
 *    略小，显得更稳重。
 *
 * 不引入新字体文件（不增加包体积），仅靠 FontFamily.Serif / SansSerif 切换；
 * 在系统含 Noto Serif CJK SC 的设备上会自动落到衬线字体，达到「印刷品」质感。
 */
object BrandTokens {

    // ----------------------------------------------------------------
    // 颜色：语义色（与 MaterialTheme.colorScheme 互补，针对品牌场景）
    // ----------------------------------------------------------------

    /** 主品牌色（朱砂印泥） — 用于标题、关键 CTA、品牌徽标 */
    val BrandRed       = androidx.compose.ui.graphics.Color(0xFFB03A2E)
    /** 副品牌色（铜金） — 用于点缀、二级标签、分割线 */
    val BrandBronze    = androidx.compose.ui.graphics.Color(0xFF8C6D3F)
    /** 强调色（深墨） — 用于版心文字 */
    val BrandInk       = androidx.compose.ui.graphics.Color(0xFF2A2622)
    /** 纸张暖白 — 用于 Hero 区背景，比 background 再亮一档 */
    val BrandPaper     = androidx.compose.ui.graphics.Color(0xFFFDFBF8)
    /** 宣纸底色 — 用于卡片背景，比 surfaceVariant 暖一档 */
    val BrandParchment = androidx.compose.ui.graphics.Color(0xFFF1ECE0)
    /** 印章泥红（半透明） — 用于选中态、品牌水印 */
    val BrandSealRed   = androidx.compose.ui.graphics.Color(0x1FB03A2E)

    // ----------------------------------------------------------------
    // 语义状态色 + 中性灰：全 App 统一，替代各屏重复的硬编码
    // ----------------------------------------------------------------
    /** 成功（完成、校验通过） */
    val StatusSuccess = androidx.compose.ui.graphics.Color(0xFF2E7D32)
    /** 警告（需注意） */
    val StatusWarning = androidx.compose.ui.graphics.Color(0xFFE65100)
    /** 信息（提示） */
    val StatusInfo    = androidx.compose.ui.graphics.Color(0xFF1976D2)
    /** 中性描边灰（边框、分隔） */
    val Hairline      = androidx.compose.ui.graphics.Color(0xFFBDBDBD)
    /** 浅灰填充（占位、印章底） */
    val SubtleGray    = androidx.compose.ui.graphics.Color(0xFFE0E0E0)

    // ----------------------------------------------------------------
    // TopAppBar 配色（品牌版）
    // ----------------------------------------------------------------
    /**
     * 给所有屏的 TopAppBar 套上品牌色：
     *  - 容器 = BrandPaper 暖纸色，与卡片同色系
     *  - 标题 / 返回按钮 = onBackground（深墨色，非默认纯黑）
     *  - actions = onSurfaceVariant（次级灰色，避免主操作与次操作同色）
     *
     * 各屏统一调用 `TopAppBar(..., colors = BrandTokens.BrandTopAppBarColors)`
     * 即可，省去在每个屏重复写 TopAppBarDefaults 配置。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    val BrandTopAppBarColors: TopAppBarColors
        @Composable
        get() = TopAppBarDefaults.topAppBarColors(
            containerColor = BrandPaper,
            scrolledContainerColor = BrandPaper,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

    /** 品牌 TopAppBar 标题样式：Serif + SemiBold + 字距 ——
     *  给所有屏的 `Text(title, ...)` 直接套用 */
    val BrandTopBarTitleStyle: TextStyle
        @Composable
        get() = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )

    // ----------------------------------------------------------------
    // 字版：自定义 Typography，叠加到 MaterialTheme
    // ----------------------------------------------------------------

    /** 衬线字体族：标题 / Hero / 法律正文 — 系统含 Noto Serif CJK SC 时自动渲染为衬线 */
    private val BrandSerif = FontFamily.Serif
    /** 无衬线字体族：UI 标签、按钮 */
    private val BrandSans  = FontFamily.SansSerif

    /**
     * 自定义字版 — 重点强化标题层级：
     *  - title 系列用 Serif + 较重字重，传达「印刷品 / 公文标题」意象
     *  - body 系列用 SansSerif 但加重 lineHeight，中文长段落更舒展
     *  - label 系列收紧字距 + Medium，提升按钮/标签的精致度
     */
    val BrandTypography = Typography(
        // —— 标题：Serif，传达印刷品质感 ——
        displaySmall = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.5).sp
        ),
        headlineLarge = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.25).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 30.sp
        ),
        titleLarge = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.1.sp
        ),
        titleSmall = TextStyle(
            fontFamily = BrandSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp
        ),

        // —— 正文：SansSerif，加 lineHeight ——
        bodyLarge = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 26.sp,        // 中文正文 1.6x 行高，更舒展
            letterSpacing = 0.15.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.15.sp
        ),
        bodySmall = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.2.sp
        ),

        // —— 标签/按钮：收紧字距 + Medium ——
        labelLarge = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.5.sp
        ),
        labelMedium = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        ),
        labelSmall = TextStyle(
            fontFamily = BrandSans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp
        )
    )
}