package com.wb.mdgw.pptx

/**
 * 布局母版模板注册表（需求 3：将布局结构与其文本框纳入母版模板，便于后续快速更改）。
 *
 * 把 9 个版式的「结构」集中为声明式数据，后续调整版式只需改这一个文件，不必翻找散落的 layoutXxx 函数：
 *  - bands：该版式固定绘制的装饰色块（如章节左侧满高色条、目录顶部色带、结尾底部色带），
 *    几何以画布归一化坐标表达，渲染时由样式表解析为 pt 矩形、颜色由主题解析。
 *  - bodyFrame：该版式正文文本框区域（相对画布归一化）；默认 = 整页内容区，带色块版式按色块推导。
 *  - bodyGap：装饰色块与正文文本框之间的间隙（pt）。
 *  - titleBar：标题竖条（仅 H3）规格（宽、相对标题左缘偏移、颜色角色）。
 *  - align：版式默认对齐。
 *  - bigColorBlock：自身带大色块 → 不叠加底部波浪/直线色块（需求 2）。
 *
 * 几何解析算术刻意与旧版 layoutXxx 的硬编码完全一致（仅把魔法数字收拢到此处），
 * 以保证「预览=导出」像素级对齐不变、既有坐标断言测试不受影响。
 */
object PptLayoutTemplates {

    /** 装饰色块的着色来源（渲染时由主题解析为具体 hex）。 */
    enum class ColorRole { COVER_BG, ACCENT, TITLE_COLOR }

    /** 模板装饰带（结构色块）。几何以画布归一化坐标表达；anchorBottom=true 时贴底（top=画布高−高）。 */
    data class Band(
        val xFrac: Float,
        val yFrac: Float,
        val wFrac: Float,
        val hFrac: Float,
        val role: ColorRole,
        val minH: Int = 0,
        val minW: Int = 0,
        val anchorBottom: Boolean = false
    )

    /** 标题竖条（H3）规格。 */
    data class TitleBar(
        val width: Int = 4,
        val offset: Int = 12,
        val role: ColorRole = ColorRole.ACCENT
    )

    /** 文本框区域（相对画布归一化）。默认 = 整页内容区（左右 40 / 上下 30）。 */
    data class TextFrame(
        val xFrac: Float = 40f / 720f,
        val yFrac: Float = 30f / 405f,
        val wFrac: Float = 640f / 720f,
        val hFrac: Float = 345f / 405f
    )

    /** 单布局母版。 */
    data class Template(
        val layout: SlideLayout,
        val name: String,
        val bands: List<Band> = emptyList(),
        val bodyFrame: TextFrame = TextFrame(),
        val titleBar: TitleBar = TitleBar(),
        val align: PptLayoutEngine.Align = PptLayoutEngine.Align.LEFT,
        val bigColorBlock: Boolean = false,
        val bodyGap: Int = 0
    )

    // ── 结构常量（与旧版 layoutXxx 完全一致）──
    private const val SIDE_BAND_RATIO = 0.20f   // 章节左侧色条宽占画布宽
    private const val BAND_RATIO = 0.10f        // 目录顶部/结尾底部色带高占画布高
    private const val BAND_MIN_H = 36           // 色带高度下限
    private const val SECTION_GAP = 28          // 章节色条与文字间隙
    private const val TOC_TEXT_GAP = 24          // 目录色带与条目间隙

    val ALL: Map<SlideLayout, Template> = mapOf(
        SlideLayout.COVER to Template(
            SlideLayout.COVER, "全色", bigColorBlock = true, align = PptLayoutEngine.Align.CENTER
        ),
        SlideLayout.TOC to Template(
            SlideLayout.TOC, "上色", bigColorBlock = true,
            bands = listOf(Band(0f, 0f, 1f, BAND_RATIO, ColorRole.COVER_BG, minH = BAND_MIN_H)),
            bodyGap = TOC_TEXT_GAP
        ),
        SlideLayout.ENDING to Template(
            SlideLayout.ENDING, "下色", bigColorBlock = true,
            bands = listOf(Band(0f, 0f, 1f, BAND_RATIO, ColorRole.COVER_BG, minH = BAND_MIN_H, anchorBottom = true))
        ),
        SlideLayout.STANDARD to Template(SlideLayout.STANDARD, "上下"),
        SlideLayout.TWO_COL to Template(SlideLayout.TWO_COL, "左右"),
        SlideLayout.THREE_COL to Template(SlideLayout.THREE_COL, "三栏"),
        SlideLayout.SECTION to Template(
            SlideLayout.SECTION, "左色", bigColorBlock = true,
            bands = listOf(Band(0f, 0f, SIDE_BAND_RATIO, 1f, ColorRole.COVER_BG)),
            bodyGap = SECTION_GAP
        ),
        SlideLayout.QUOTE to Template(SlideLayout.QUOTE, "居中", align = PptLayoutEngine.Align.CENTER),
        SlideLayout.LIST to Template(SlideLayout.LIST, "左中", align = PptLayoutEngine.Align.CENTER)
    )

    /** 大色块版式集合（自身带大色块→不叠加底部装饰，需求 2）。 */
    val bigColorBlockLayouts: Set<SlideLayout> get() = ALL.filter { it.value.bigColorBlock }.keys

    /** 取某版式母版（不存在则回退上下布局）。 */
    fun get(layout: SlideLayout): Template = ALL[layout] ?: ALL.getValue(SlideLayout.STANDARD)

    /** 全局默认标题竖条规格（所有布局统一）。 */
    val titleBar: TitleBar get() = TitleBar()

    /** 将模板装饰带解析为实际 pt 矩形 + 着色（由主题着色）。 */
    fun resolveBands(t: Template, style: PptStyleSheet, theme: PptTheme): List<Pair<Rect, String>> {
        val colorOf: (ColorRole) -> String = { role ->
            when (role) {
                ColorRole.COVER_BG -> theme.coverBg
                ColorRole.ACCENT -> theme.accent
                ColorRole.TITLE_COLOR -> theme.titleColor
            }
        }
        return t.bands.map { b ->
            // 用 toInt() 截断（与旧版 layoutXxx 的 (canvasH * 0.10).toInt() 完全一致），保证像素级对齐
            val w = (b.wFrac * style.canvasW).toInt().coerceAtLeast(b.minW)
            val h = (b.hFrac * style.canvasH).toInt().coerceAtLeast(b.minH)
            val x = (b.xFrac * style.canvasW).toInt()
            val y = if (b.anchorBottom) (style.canvasH - h) else (b.yFrac * style.canvasH).toInt()
            Rect(x, y, w, h) to colorOf(b.role)
        }
    }

    /** 将文本框区域解析为实际 pt 矩形。 */
    fun resolveFrame(t: Template, style: PptStyleSheet): Rect {
        val f = t.bodyFrame
        return Rect(
            (f.xFrac * style.canvasW).toInt(),
            (f.yFrac * style.canvasH).toInt(),
            (f.wFrac * style.canvasW).toInt(),
            (f.hFrac * style.canvasH).toInt()
        )
    }

    /** H3 标题竖条矩形（宽/偏移取自母版，颜色由主题解析）。block 仅保留以兼容布局层调用签名。 */
    fun titleBarRect(@Suppress("UNUSED_PARAMETER") block: MdBlock, x: Int, y: Int, h: Int): Rect {
        val t = titleBar
        return Rect(x - t.offset, y, t.width, h)
    }
}
