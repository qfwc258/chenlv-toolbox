package com.wb.mdgw.pptx

/**
 * PPTX 可编辑样式表（对标公众号「自定义 CSS 样式」）。
 *
 * 把原本散落在 PptSpec / PptThemes / PptExportEngine 里的硬编码排版常量，
 * 统一收敛为一份数据类。配合 PptCssParser，即可用一段 CSS 文本随时覆盖任意字段，
 * 实时作用于预览与导出，无需改代码。
 *
 * 默认值 = 当前线上版本（v1.7.x）的硬编码配置，保证「不填 CSS = 原样」。
 */
data class PptStyleSheet(
    // ── 画布与边距（pt）──
    val canvasW: Int = 720,
    val canvasH: Int = 405,
    val marginX: Int = 40,
    val marginTop: Int = 30,
    val marginBottom: Int = 30,
    /**
     * 内容区下边界覆盖值（pt）。>=0 时强制使用此值（代替 canvasH−marginBottom），
     * 用于「开启波浪装饰」时把文字框整体上移，避开底部波浪、保持与页底的安全边距。
     * 默认 −1 表示不覆盖（使用 canvasH−marginBottom）。
     */
    val contentBottomOverride: Int = -1,

    // ── 字号（pt）──
    val fsH1: Int = 28,
    val fsH2: Int = 24,
    val fsH3: Int = 20,
    val fsH4: Int = 18,
    val fsH5: Int = 16,
    val fsH6: Int = 14,
    val fsBody: Int = 16,
    val fsQuote: Int = 15,
    val fsCode: Int = 13,

    // ── 行距倍数 ──
    val lineMult: Double = 1.2,

    // ── 段距（pt）──
    val paraGap: Int = 8,
    val headGap: Int = 12,
    /** 引用块（md >）整体段前距：与上方文本保持适度间距（布局层统一施加，预览/导出一致）。 */
    val quoteGapBefore: Int = 12,

    // ── 颜色（hex RRGGBB，不带 #）──
    val bg: String = "FFFFFF",
    val titleColor: String = "9E2A2B",
    val bodyColor: String = "333333",
    val accent: String = "C0392B",
    val codeBg: String = "F7ECEC",
    val quoteBg: String = "F7ECEC",
    val coverBg: String = "9E2A2B",

    // ── 字体（OOXML typeface 名）──
    // 西文字体（<a:latin>/<a:cs>）：默认 Arial（无衬线）。与东亚字体分开，
    // 避免把 CJK 字体名写入拉丁槽导致英文/数字回落到衬线默认字体。
    val latinFont: String = "Arial",
    // 东亚（中文）字体（<a:ea>）：默认微软雅黑（无衬线）。
    val bodyFont: String = "微软雅黑",
    val titleFont: String = "微软雅黑",
    // 代码块东亚字体（拉丁槽固定 Consolas 等宽）。
    val codeFont: String = "Consolas",

    // ── 其他排版（pt）──
    val listIndent: Int = 18,
    val quoteIndent: Int = 24,
    val codePad: Int = 8,
    val tablePad: Int = 6,
    val splitGap: Int = 24,
    /** 被 CSS 显式覆盖的字段名集合（用于「仅显式声明的颜色才覆盖主题」）。 */
    val overrides: Set<String> = emptySet()
) {
    /** 内容区宽 = 画布宽 − 左右边距×2（pt）。 */
    val contentW: Int get() = canvasW - marginX * 2
    /** 内容区上边界（pt）。 */
    val contentTop: Int get() = marginTop
    /** 内容区下边界（pt）。若设置了 contentBottomOverride 则优先使用，否则 canvasH−marginBottom。 */
    val contentBottom: Int get() = if (contentBottomOverride >= 0) contentBottomOverride else canvasH - marginBottom

    /** 转为 PptTheme（供现有渲染/导出链路的配色部分复用）。 */
    fun toTheme(id: String = "css", name: String = "自定义样式"): PptTheme = PptTheme(
        id = id, name = name,
        bg = bg, titleColor = titleColor, bodyColor = bodyColor, accent = accent,
        codeBg = codeBg, quoteBg = quoteBg, coverBg = coverBg
    )
}
