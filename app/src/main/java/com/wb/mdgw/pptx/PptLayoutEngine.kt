package com.wb.mdgw.pptx

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * 流式防溢出布局引擎。
 *
 * 职责：
 * 1. 估算任意块的高度（供自动分页引擎做"剩余高度是否放得下"判断，及本引擎做 Y 坐标排布）；
 * 2. 把分页结果排布成每页的渲染单元（含坐标/字号/对齐），预览与导出共用，保证 1:1。
 *
 * 所有内容严格约束在安全矩形（左 40 / 右 680 / 上 30 / 下 375）内，触底即视为溢出单块。
 */
/**
 * 波浪装饰的主要可调参数（供 UI 滑块调整）。
 *
 * 默认值 = v1.7.9 出厂效果（保底）：UI 调高/调低后再「恢复默认」即回到此值。
 * 三个参数分别对应用户最关心的视觉维度：浪高、通透度、层次对比。
 *
 * @param heightScale 波浪整体高度倍率（等比缩放三层波高 38/28/19），1.0=出厂
 * @param opacityScale 波浪整体透明度倍率（等比缩放三层 alpha 0.45/0.70/0.95），1.0=出厂，调低更通透、调高更实
 * @param contrast 层次对比强度（缩放后层提亮 / 中层次暗幅度），1.0=出厂，调高更分明、调低更柔和
 */
@Serializable
data class PptWaveParams(
    val heightScale: Float = 1.0f,
    val opacityScale: Float = 1.0f,
    val contrast: Float = 1.0f
)

object PptLayoutEngine {
    /** 当前生效样式表（由 UI 注入；默认=默认样式=v1.7.x，保证「不填 CSS = 原样」）。 */
    var style: PptStyleSheet = PptStyleSheet()

    /** 当前生效波浪参数（由 UI 注入；默认=出厂效果=v1.7.9，保证「未调 = 原样」）。 */
    var waveParams: PptWaveParams = PptWaveParams()

    /** 中文/英文标点字符集合，用于表格智能列宽权重判断。 */
    private val PUNCTUATION_CHARS: Set<Char> = setOf(
        '，', '。', '、', '；', '：', '！', '？',
        '.', ',', ';', ':', '!', '?'
    )

    /** Logo 装饰参数（由 UI 注入；控制大小与位置）。 */
    var logoScale: Float = 0.20f       // 占画布宽比例，默认 20%
    var logoHAlign: String = "right"   // "left" / "right"
    var logoVAlign: String = "bottom"  // "top" / "bottom"

    enum class Align { LEFT, CENTER, RIGHT }

    /** 单页中的一个渲染单元（预览与导出都消费它）。坐标单位 pt。 */
    data class LaidOutUnit(
        val type: BlockType,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val fontSize: Int,
        val align: Align,
        val fragments: List<InlineFragment> = emptyList(),
        val listItems: List<MdBlock.ListItemData> = emptyList(),
        val ordered: Boolean = false,
        /** 有序列表起始编号偏移量（0-based）。0 表示从 1 开始；拆分后的续接列表应设为原始索引以保持编号连续。 */
        val listStart: Int = 0,
        val overflow: Boolean = false,
        val table: TableRender? = null,
        val bold: Boolean = false,
        /** 文字颜色覆盖（hex RRGGBB）。非 null 时强制使用该色（如目录标题反白），忽略主题色。 */
        val color: String? = null,
        /** 东亚（中文）字体（OOXML <a:ea> typeface 名），由 CSS 的 font-family 决定；空串表示使用导出端默认映射。 */
        val fontFamily: String = "",
        /** 西文字体（OOXML <a:latin>/<a:cs> typeface 名）；空串表示使用导出端默认映射（Arial）。 */
        val latinFont: String = "",
        /** 段后距（pt），导出时写入 <a:spcAft>；0 表示不加。 */
        val gapAfter: Int = 0
    )

    /** 表格渲染数据（引擎已算好列宽/行高/对齐，预览与导出同源消费）。 */
    data class TableRender(
        val header: List<List<InlineFragment>>,
        val rows: List<List<List<InlineFragment>>>,
        val colW: List<Int>,
        val colAlign: List<TableAlign>,
        val headerH: Int,
        val rowHs: List<Int>,
        val cellFs: Int,
        val headerFs: Int
    ) {
        val totalH: Int get() = headerH + rowHs.sum()
    }

    data class LaidOutSlide(
        val units: List<LaidOutUnit>,
        val cover: Boolean,
        val layout: SlideLayout = SlideLayout.STANDARD,
        val deco: SlideDeco? = null,
        val footer: SlideFooter? = null,
        /** 该页的组合描述（阶段一新增，不影响渲染；预览/导出可按需读取）。 */
        val composition: SlideComposition? = null
    )

    // ────────────────────────────────────────────────
    // 文本宽度 / 行数估算
    // ────────────────────────────────────────────────

    /**
     * 单个字符的近似宽度（pt）。
     * 中文按 1.0em、西文约 0.55em——采用与 PowerPoint/Android 真实字形度量一致的字宽，
     * 使「引擎估算行数 == 实际渲染行数」，块高估算 ≥ 实际渲染高。
     *
     * 关键：行数估算必须与预览/导出真实渲染一致，否则多行文本的实际渲染高度会高于估算，
     * 下一块按布局 y 落在「预留段距」处时，本块内容向下溢出、吞掉段前/段后距
     *（预览「间距消失」），且预览与导出结果不一致。历史曾用 0.92em 偏紧估算，正是该问题根因。
     * 现改回真实 1.0em，段距稳定显示，且预览与导出对齐；块更高会触发分页更早断页（兜底，不超下边距）。
     */
    private fun charWidthPt(c: Char, fontSize: Int): Double {
        val code = c.code
        val cjk = code in 0x4E00..0x9FFF || code in 0x3000..0x303F ||
                code in 0xFF00..0xFFEF || code in 0x3400..0x4DBF
        return fontSize * if (cjk) 1.0 else 0.55
    }

    /** 一段文本的像素宽度（pt）。 */
    private fun textWidthPt(text: String, fontSize: Int): Double =
        text.sumOf { charWidthPt(it, fontSize) }

    /** 按可用宽度贪婪折行，返回行数。 */
    private fun lineCount(text: String, fontSize: Int, availWidth: Int): Int {
        if (text.isEmpty()) return 1
        var lines = 1
        var cur = 0.0
        for (c in text) {
            cur += charWidthPt(c, fontSize)
            if (cur > availWidth) {
                lines++
                cur = charWidthPt(c, fontSize)
            }
        }
        return lines
    }

    // ────────────────────────────────────────────────
    // 块高度估算
    // ────────────────────────────────────────────────

    private fun fontSizeOf(type: BlockType): Int = when (type) {
        BlockType.H1 -> style.fsH1
        BlockType.H2 -> style.fsH2
        BlockType.H3 -> style.fsH3
        BlockType.H4 -> style.fsH4
        BlockType.H5 -> style.fsH5
        BlockType.H6 -> style.fsH6
        BlockType.QUOTE -> style.fsQuote
        BlockType.CODE -> style.fsCode
        else -> style.fsBody
    }

    private fun gapOf(type: BlockType): Int = when (type) {
        BlockType.H1, BlockType.H2, BlockType.H3, BlockType.H4, BlockType.H5, BlockType.H6 -> style.headGap
        else -> style.paraGap
    }

    /** 多行文本片段拼接后的渲染高度（不含段后距）。 */
    private fun textContentHeight(fragments: List<InlineFragment>, fontSize: Int, width: Int): Int {
        val full = fragments.joinToString("") { it.text }
        // 按硬换行分段
        val paras = full.split("\n")
        val lines = paras.sumOf { lineCount(it, fontSize, width) }
        // 行高乘 1.04 安全缓冲：补偿 Compose/PowerPoint 实际字形度量（ascent+descent）略超出
        // lineHeight 设定值的偏差，确保「引擎块高 ≥ 实际渲染高」，段前/段后距不被内容溢出吞掉。
        return maxOf(1, lines) * (fontSize * style.lineMult * 1.04).toInt()
    }

    /** 单块"内容渲染高"（不含段后距）。使用块的默认字号与整页内容宽。 */
    fun contentHeight(block: MdBlock): Int = contentHeight(block, fontSizeOf(blockTypeOf(block)), style.contentW)

    /** 单块"内容渲染高"（不含段后距），使用[指定的字号]。特殊版式必须用此重载传入实际渲染字号。 */
    fun contentHeight(block: MdBlock, fontSize: Int): Int = contentHeight(block, fontSize, style.contentW)

    /**
     * 单块"内容渲染高"（不含段后距），使用[指定的字号]与[实际可用宽度 width]。
     * 左右/三栏布局列宽小于整页宽，必须用此重载传实际列宽，否则行数估算偏小、文本框被截断。
     */
    fun contentHeight(block: MdBlock, fontSize: Int, width: Int): Int = when (block) {
        is MdBlock.TextBlock -> when (block.type) {
            BlockType.CODE -> {
                val lines = block.raw.split("\n").size.coerceAtLeast(1)
                val innerW = width - style.codePad * 2
                val wrapped = block.raw.split("\n").sumOf { lineCount(it, fontSize, innerW) }
                lines.coerceAtLeast(wrapped) * (fontSize * style.lineMult * 1.04).toInt() + style.codePad * 2
            }
            else -> textContentHeight(block.fragments, fontSize, width)
        }
        is MdBlock.ListBlock -> {
            val innerW = width - style.listIndent
            // 列表每项前有前缀（"1. " 或 "•  "），占约 3 个西文字宽，需从可用宽度中扣除
            // 否则 lineCount 少算折行 → 高度估算偏低 → 预览文字溢出/截断
            val prefixW = fontSize * 0.55 * 3  // 前缀近似宽度（有序 "1. " / 无序 "•  "）
            val effectiveW = (innerW - prefixW.toInt()).coerceAtLeast((width * 0.3).toInt())
            block.items.sumOf { item ->
                val text = item.fragments.joinToString("") { it.text }
                // 嵌套项缩进越深，可用宽度越小（每层缩进约 2em）
                val indentDeduction = (item.indent * fontSize * 2).coerceAtMost(effectiveW / 2)
                val itemW = (effectiveW - indentDeduction).coerceAtLeast((width * 0.2).toInt())
                lineCount(text, fontSize, itemW) * (fontSize * style.lineMult * 1.04).toInt()
            } + block.items.size * 2
        }
        is MdBlock.TableBlock -> buildTableRender(block, width).totalH
        is MdBlock.ForcedBreak -> 14
    }

    /** 单块"总高"（含段后距），供分页与 Y 轴步进。 */
    fun blockHeight(block: MdBlock): Int = when (block) {
        is MdBlock.ForcedBreak -> 14
        else -> contentHeight(block) + gapOf(blockTypeOf(block))
    }

    /**
     * 超长代码块（```）按物理行（\n）拆分为多个子块，每页一个，自动分页不截断。
     *
     * 拆分规则：
     * - 严格按 \n（段落/物理行）边界拆分，绝不从文字中间切开
     * - 用 0.85 安全系数预留余量：Compose 实际行高 > 引擎估算值（LINE_MULT=1.2），
     *   避免子块"差一点点"溢出导致预览底部裁剪
     * - 单行文本即使折行后很长也完整保留在同一子块
     */
    fun splitLongCode(block: MdBlock, availH: Int = PAGE_CONTENT_H): List<MdBlock> {
        if (block !is MdBlock.TextBlock || block.type != BlockType.CODE) return listOf(block)
        if (contentHeight(block) <= availH) return listOf(block)
        val fs = style.fsCode
        val lineH = (fs * style.lineMult).toInt()
        val innerW = style.contentW - style.codePad * 2
        // 安全系数 0.85：Compose 实际行高通常比估算高 15~20%，预留空间防止溢出裁剪
        val usable = ((availH - style.codePad * 2) * 0.85f).toInt().coerceAtLeast(lineH * 2)
        val lines = block.raw.split("\n")
        val chunks = mutableListOf<StringBuilder>()
        var buf = StringBuilder()
        var bufH = 0
        for (line in lines) {
            val wrapped = lineCount(line, fs, innerW).coerceAtLeast(1)
            val lh = wrapped * lineH
            // 当前缓冲已满且再加本行会超页 → 先截断为一块（保证每行完整不拆）
            if (buf.isNotEmpty() && bufH + lh > usable) {
                chunks.add(buf)
                buf = StringBuilder()
                bufH = 0
            }
            if (buf.isNotEmpty()) buf.append("\n")
            buf.append(line)
            bufH += lh
        }
        if (buf.isNotEmpty()) chunks.add(buf)
        return chunks.map { MdBlock.TextBlock(BlockType.CODE, raw = it.toString()) }
    }

    /**
     * 超长表格按数据行拆分为多个子表（每页一个），自动分页不截断。
     * 每个子表都保留原表头（header），方便跨页阅读。
     * 用 buildTableRender 精算每页能容纳的行数，确保子表高度 ≤ PAGE_CONTENT_H。
     */
    fun splitLongTable(block: MdBlock.TableBlock, availH: Int = PAGE_CONTENT_H): List<MdBlock.TableBlock> {
        if (block.rows.isEmpty()) return listOf(block)
        val render = buildTableRender(block, style.contentW)
        if (render.totalH <= availH) return listOf(block)

        val usableH = availH - render.headerH
        if (usableH <= 0) return listOf(block) // 表头本身就超页，不拆

        val subTables = mutableListOf<MdBlock.TableBlock>()
        var startIdx = 0
        while (startIdx < block.rows.size) {
            var h = 0
            var endIdx = startIdx
            while (endIdx < block.rows.size) {
                val rowH = render.rowHs[endIdx]
                if (h > 0 && h + rowH > usableH) break
                h += rowH
                endIdx++
            }
            if (endIdx == startIdx) endIdx++ // 单行也超限，强制至少放一行
            subTables.add(MdBlock.TableBlock(
                header = block.header,
                rows = block.rows.subList(startIdx, endIdx),
                colAlign = block.colAlign
            ))
            startIdx = endIdx
        }
        return subTables.ifEmpty { listOf(block) }
    }

    private fun blockTypeOf(block: MdBlock): BlockType = when (block) {
        is MdBlock.TextBlock -> block.type
        is MdBlock.ListBlock -> block.type
        is MdBlock.TableBlock -> BlockType.TABLE
        is MdBlock.ForcedBreak -> BlockType.DIVIDER
    }

    /** 整页内容可用高度 */
    val PAGE_CONTENT_H: Int get() = style.contentBottom - style.contentTop

    /**
     * 按页布局。封面页强制单列居中；其余按 [layoutOf] 选择模板。
     * 返回的 [LaidOutSlide] 携带该页 layout 与装饰信息，预览与导出共用，保证 1:1。
     *
     * 底部装饰（波浪/直线色块）由每页组合的 [SlideComposition.decoration] 决定，
     * 仅对「自身不带大色块」的页面生效（hasBigBlock 为 false）。
     * 波浪参数 [waveParams] 和直线色块高度 [barHeightDenom] 为全局视觉参数，由 UI 设置面板控制。
     *
     * @param barHeightDenom 直线色块高度分母 N（高度 = 画布高 / N，默认 60；范围建议 30~100，越小越厚）
     */
    fun layout(result: PaginationResult, theme: PptTheme, layoutOf: (Int) -> SlideLayout, compOf: (Int) -> SlideComposition? = { null }, barHeightDenom: Int = 60): List<LaidOutSlide> {
        var sectionSeq = 0   // 章节页序号计数（用于超大序号 01/02...）
        // 底部装饰仅用于「自身不带大色块」的版式。大色块版式集合统一由母版模板注册表驱动（需求 3），
        // 凡版式自绘大色块（封面整页底色、章节左侧满高色条、目录顶部满宽色带、结尾底部满宽色带，
        // 或组合中的任意色块），都不再叠加波浪/直线色块，避免与既有色块冲突或重叠。
        // 判定：使用 SlideComposition.hasBigBlock（role!=NONE 或 colorBlock!=NONE）。
        // 全文标题（H1/H2）：用于目录页自动补条目、避免空白页
        val docHeadings = result.pages.flatMap { it.blocks }
            .filterIsInstance<MdBlock.TextBlock>()
            .filter { it.type == BlockType.H1 || it.type == BlockType.H2 }
        // 开启波浪时，内容区下边界需上移「波浪占用高度 + 安全间隙」，避免文字框与波浪/页底重叠。
        // layout() 内临时改写 style 的 contentBottomOverride，逐页设置并在结束后还原，布局函数统一读 style.contentBottom。
        val originalStyle = style
        try {
            return result.pages.mapIndexed { idx, slide ->
                // 封面页：如果用户显式选了 COVER 版式或页面本身 isCover，都用封面布局
                val requested = layoutOf(idx)
                val comp = compOf(idx) ?: CompositionResolver.compositionOf(requested)
                // 特殊页角色或任意色块 → 自身带大色块，不叠加底部装饰
                val hasBigBlock = comp.hasBigBlock
                if (comp.role == PageRole.COVER) {
                    val (units, deco) = layoutCover(slide.blocks, comp)
                    return@mapIndexed LaidOutSlide(units, true, requested, deco, null, comp)
                }
                // 该页是否叠加波浪 / 直线色块 / logo：从 composition.decoration 读取，且仅当无大色块时生效
                val bottomDeco = comp.decoration
                val waveOn = !hasBigBlock && bottomDeco == BottomDecoration.WAVE
                val barOn = !hasBigBlock && bottomDeco == BottomDecoration.BAR
                val logoOn = !hasBigBlock && bottomDeco == BottomDecoration.LOGO
                val barH = (style.canvasH / barHeightDenom).coerceAtLeast(1)
                val effBottom = if (waveOn) {
                    val baseBottom = style.canvasH - style.marginBottom
                    (baseBottom - waveClearance()).coerceAtLeast(style.contentTop + 40)
                } else -1
                style = style.copy(contentBottomOverride = effBottom)
                // 按组合角色分发：特殊页走专属渲染（自动目录 / 默认致谢语 / 章节色条），
                // NONE 角色走统一的「结构 × 色块 × 对齐 × 间距」组合渲染（layoutComposition）。
                val (units, deco, cover) = when (comp.role) {
                    PageRole.TOC -> { val (u, d) = layoutToc(slide.blocks, theme, docHeadings); Triple(u, d, false) }
                    PageRole.ENDING -> { val (u, d) = layoutEnding(slide.blocks, theme); Triple(u, d, false) }
                    PageRole.SECTION -> { sectionSeq++; val (u, d) = layoutSection(slide.blocks, sectionSeq, theme); Triple(u, d, false) }
                    PageRole.NONE -> { val (u, d) = layoutComposition(comp, slide.blocks, theme); Triple(u, d, comp.colorBlock == ColorBlock.COVER) }
                    else -> { val (u, d) = layoutCover(slide.blocks, comp); Triple(u, d, true) } // COVER 已在上方处理，兜底
                }
                // 底部装饰：波浪 / 直线色块 / logo（互斥，仅当页底装饰开关打开时添加）
                // 直线色块颜色固定跟随主题主色调（theme.accent），高度由 barH 决定（画布高 1/N）
                val finalDeco = when {
                    waveOn -> deco?.copy(wave = true, waveColor = theme.accent)
                        ?: SlideDeco(wave = true, waveColor = theme.accent)
                    barOn -> deco?.copy(bottomBar = true, bottomBarH = barH)
                        ?: SlideDeco(bottomBar = true, bottomBarH = barH)
                    logoOn -> deco?.copy(logo = true)
                        ?: SlideDeco(logo = true)
                    else -> deco
                }
                LaidOutSlide(units, cover, requested, finalDeco, null, comp)
            }
        } finally {
            style = originalStyle
        }
    }

    /**
     * 波浪装饰在页面底部占用的垂直高度（pt）：取三层中最高的波高（出厂 38pt）按 UI 倍率缩放，
     * 再加一段安全间隙，确保文字框整体位于波浪之上、与页底保持清晰边距。
     */
    private fun waveClearance(): Int {
        val maxWaveH = 38f * waveParams.heightScale   // 底层波最高（出厂 38pt）
        return (maxWaveH + 14f).roundToInt().coerceAtLeast(20)
    }

    /**
     * 开启波浪时内容区下边界（pt）：在 canvasH−marginBottom 基础上上移「波浪占用高度 + 安全间隙」，
     * 供分页与布局共用，确保文字框整体位于波浪之上、与页底保持清晰边距。无波浪时返回 −1（不覆盖）。
     */
    fun waveAwareContentBottom(enableWave: Boolean): Int =
        if (enableWave) {
            val base = style.canvasH - style.marginBottom
            (base - waveClearance()).coerceAtLeast(style.contentTop + 40)
        } else -1

    // ────────────────────────────────────────────────
    // 波浪装饰（底部流动弧线，三层颜色层次）
    // ────────────────────────────────────────────────

    /**
     * 生成三层流动弧线波浪装饰的路径数据。
     *
     * 设计参照附件图效果（流动海浪风格），目标：层次感 + 柔和 + 置于底层。
     * - 平滑流动的曲线（像海浪截面，非尖锐山峰）
     * - 底部对齐：每层波浪底部贴紧页面底边；宽度满屏（CANVAS_W = 720pt）
     * - 三层透明度叠加的渐层：底层=主色调/最高/最透明（大面积不抢视觉），中层=适度提亮，顶层=最浅/最低/近实色
     * - 渲染顺序：索引0先画=底层/主色，索引2后画=顶层/浅色（导出与预览中均置于内容之下）
     *
     * @param baseColor 主色调 hex（如 "2E5FA3"），用于派生三色
     * @return 三层 WaveLayer 列表（按渲染顺序：索引0先画=底层/最后面，索引2后画=顶层/最前面）
     */
    fun generateWaveLayers(baseColor: String): List<WaveLayer> {
        val colors = deriveWaveColors(baseColor, waveParams.contrast)

        // ── 三层参数：(最大波高pt, 波峰配置列表) ──
        // 每个波峰：(水平位置 0~1, 相对高度 0~1)
        // 渲染顺序：最下层(后/浅) → 中间层(深) → 顶层(前/主色调)
        // 设计目标：层次感更强 + 主色醒目
        //  - 顶层（前/最小面积）= 主色调原色，最低、近实色，压住底部收尾
        //  - 中间层 = 比主色压暗一档，半透明，制造深色纵深
        //  - 最下层（后/最大面积）= 比主色大幅提亮，最透明，如远处薄浪
        //  - 三层透明度：底透 → 中半透 → 顶近实，叠加出由浅入深的强层次
        val layerConfigs = listOf(
            // 底层（浅色、透明、最高）：4 个均匀波峰，左缘首峰右移软化尖角
            38f to listOf(
                0.20f to 0.45f,  // 左缓起（右移+降低，软化最左尖角）
                0.40f to 0.78f,  // 第一主峰
                0.63f to 0.50f,  // 波谷
                0.90f to 0.80f,  // 第二主峰
            ),
            // 中层（中色、半透明、中高）：相位右移 ~0.17
            28f to listOf(
                0.24f to 0.52f,  // 相位错开（右移软化）
                0.46f to 0.86f,  // 主峰
                0.72f to 0.55f,  // 波谷
                0.95f to 0.84f,  // 收尾峰
            ),
            // 顶层（主色调、近实色、最低）：主浪左移、首峰右移软化左缘、整体压低
            19f to listOf(
                0.22f to 0.55f,  // 左峰（右移+降低，软化最左尖角）
                0.34f to 1.00f,  // 主浪（左移，主导视觉）
                0.64f to 0.68f,  // 波谷
                0.90f to 0.95f,  // 收尾浪
            ),
        )

        // 出厂透明度基准（底透 → 中半透 → 顶近实），整体再乘以 UI 透明度倍率
        val baseAlpha = listOf(0.45f, 0.70f, 0.95f)

        return layerConfigs.mapIndexed { idx, (maxH, peaks) ->
            val points = buildFlowingWavePath(maxH * waveParams.heightScale, peaks)
            WaveLayer(
                controlPoints = points,
                color = colors[idx],
                // 透明度递增：底层(主色)最透 → 中层半透 → 顶层(浅色)近实
                // 再叠加 UI 透明度倍率（调低=更通透，调高=更实）
                alpha = (baseAlpha[idx] * waveParams.opacityScale).coerceIn(0f, 1f)
            )
        }
    }

    /**
     * 构建单层流动波浪路径的控制点序列（归一化坐标 0~1）。
     *
     * 算法：以各波峰（含左右边缘）为控制点，用 **Catmull-Rom 样条转三次贝塞尔**
     * 生成穿过所有波峰、且 **C1 连续（无折角）** 的平滑曲线；相比原先的二次贝塞尔，
     * 波峰/波谷过渡更圆润柔和，整体更像流动海浪。
     *
     * 坐标约定：x∈[0,1] 左→右；y∈[0,1]，y=1 贴页面底边，y 越小越高。
     * 路径走向：`(0,底) ──moveTo──> 平滑三次贝塞尔弧(左→右) ──> (1,底) ──lnTo──> (1,1) ──lnTo──> (0,1) ──close`
     *
     * 输出格式（扁平列表，供导出/预览共用）：
     *  - [0..1] = moveTo 起点 (左下角)
     *  - 之后每 6 个值 = 1 段三次贝塞尔：(c1x,c1y, c2x,c2y, ex,ey)
     *  - 末尾 4 个值 = 闭合矩形两点：(1,1) 右下、(0,1) 左下
     *
     * @param maxHeight 最大波高（pt），距页面底边
     * @param peaks 波峰列表：(水平位置 0~1, 相对高度 0~1)
     */
    private fun buildFlowingWavePath(maxHeight: Float, peaks: List<Pair<Float, Float>>): List<Float> {
        val h = style.canvasH.toFloat()       // 405
        val baseFrac = maxHeight / h             // 波浪最高处占画布高度比例（归一化）

        // 控制点（归一化 x, 归一化 y）；y=1 贴底，波峰越高 y 越小
        val ctrl = mutableListOf<Pair<Float, Float>>()
        ctrl.add(0f to 1f)                       // 左边缘（贴底）
        for ((px, ph) in peaks) ctrl.add(px to (1f - ph * baseFrac))
        ctrl.add(1f to 1f)                       // 右边缘（贴底）

        val pts = mutableListOf<Float>()
        pts.add(ctrl[0].first); pts.add(ctrl[0].second)   // moveTo 起点

        // Catmull-Rom → 三次贝塞尔：每段 Cubic 控制点 = 相邻切线 × 1/6，C1 连续 → 无折角
        for (i in 0 until ctrl.size - 1) {
            val p0 = ctrl.getOrElse(i - 1) { ctrl[i] }
            val p1 = ctrl[i]
            val p2 = ctrl[i + 1]
            val p3 = ctrl.getOrElse(i + 2) { ctrl[i + 1] }
            val c1x = p1.first + (p2.first - p0.first) / 6f
            val c1y = p1.second + (p2.second - p0.second) / 6f
            val c2x = p2.first - (p3.first - p1.first) / 6f
            val c2y = p2.second - (p3.second - p1.second) / 6f
            pts.add(c1x); pts.add(c1y)
            pts.add(c2x); pts.add(c2y)
            pts.add(p2.first); pts.add(p2.second)
        }

        // 闭合底边（右下 → 左下）
        pts.add(1f); pts.add(1f)
        pts.add(0f); pts.add(1f)
        return pts
    }

    /**
     * 将 WaveLayer 归一化控制点转为 Compose Path。
     *
     * 重要：此方法接受**实际绘制尺寸**参数（而非硬编码 CANVAS_W/H），
     * 确保波浪在预览（dp）和导出（pt）两种坐标系下都能正确对齐到底部。
     *
     * @param layer 波浪层数据（controlPoints 为 0~1 归一化坐标）
     * @param drawW 实际绘制宽度（预览=Canvas dp宽，导出=CANVAS_W pt）
     * @param drawH 实际绘制高度（预览=Canvas dp高，导出=CANVAS_H pt）
     */
    fun waveLayerToPath(layer: WaveLayer, drawW: Float, drawH: Float): android.graphics.Path {
        val path = android.graphics.Path()
        val pts = layer.controlPoints
        if (pts.size < 6) return path

        // moveTo 起点：左下角（归一化 → 实际尺寸）
        path.moveTo(pts[0] * drawW, pts[1] * drawH)

        // 三次贝塞尔弧线段（C1 连续，更平滑）
        val cubicCount = (pts.size - 6) / 6
        var i = 2
        for (q in 0 until cubicCount) {
            val c1x = pts[i] * drawW;       val c1y = pts[i + 1] * drawH   // 控制点 1
            val c2x = pts[i + 2] * drawW;   val c2y = pts[i + 3] * drawH   // 控制点 2
            val ex = pts[i + 4] * drawW;    val ey = pts[i + 5] * drawH    // 终点
            path.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
            i += 6
        }

        // 闭合矩形部分（右边 → 左边）
        if (i + 3 < pts.size) {
            path.lineTo(pts[i] * drawW, pts[i + 1] * drawH)        // 右下角
            path.lineTo(pts[i + 2] * drawW, pts[i + 3] * drawH)    // 左下角
        }
        path.close()

        return path
    }

    // ── 封面（专业版式：左侧强调色条 + 顶部导语 + 大标题 + 强调下划线 + 副标题 + 底部元信息）──
    // ── 封面（文字加粗，整体按 SlideComposition 的对齐设置排布）──
    private fun layoutCover(blocks: List<MdBlock>, comp: SlideComposition): Pair<List<LaidOutUnit>, SlideDeco?> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to null
        val units = mutableListOf<LaidOutUnit>()
        val cx = style.marginX
        val cw = style.contentW       // 居中覆盖内容区

        // 水平对齐：由组合 halign 决定（默认 CENTER 保持旧行为）
        val hAlign = when (comp.halign) {
            HAlign.CENTER -> Align.CENTER
            HAlign.LEFT -> Align.LEFT
        }

        // 取主要元素：导语(kicker) / 大标题 / 副标题 / 其余作为底部元信息
        val mainTitle = blocks.firstOrNull { it is MdBlock.TextBlock && it.type == BlockType.H1 }
            ?: blocks.firstOrNull()
        val kicker = blocks.firstOrNull { it !== mainTitle && it is MdBlock.TextBlock && it.type in setOf(BlockType.H2) }
            ?: blocks.firstOrNull { it !== mainTitle && it is MdBlock.TextBlock && it.type == BlockType.PARAGRAPH }
        val sub = blocks.firstOrNull { it !== mainTitle && it !== kicker && it is MdBlock.TextBlock && it.type == BlockType.PARAGRAPH }
        val meta = blocks.filter { it !== mainTitle && it !== kicker && it !== sub }

        // 构建渲染序列（kicker → 大标题 → 副标题 → 元信息）
        // 样式统一：每个块的字号/字体/加粗均按其 MD 标签取标准值（与所有布局一致），
        // 布局只决定位置（对齐、间距）；遇整页色块(cover)时文字由渲染端反色。
        val items = mutableListOf<Triple<MdBlock, Int, Int>>()   // block, fontSize, gapAfter
        if (kicker != null) items.add(Triple(kicker, fontSizeOf(blockTypeOf(kicker)), 12))
        if (mainTitle != null) items.add(Triple(mainTitle, fontSizeOf(blockTypeOf(mainTitle)), 26))
        if (sub != null) items.add(Triple(sub, fontSizeOf(blockTypeOf(sub)), 22))
        // 元信息块：字号同样按 MD 标签取标准值（与所有布局一致），不额外放大/加粗
        meta.forEach { b ->
            items.add(Triple(b, fontSizeOf(blockTypeOf(b)), 8))
        }

        // 计算总高后按组合 valign 决定垂直位置
        var totalH = 0
        val heights = items.map { (b, fs, gap) ->
            val ch = contentHeight(b, fs, cw)
            totalH += ch + gap
            ch
        }
        totalH = (totalH - (items.lastOrNull()?.third ?: 0)).coerceAtLeast(0)
        val contentH = style.contentBottom - style.contentTop
        var y = when (comp.valign) {
            VAlign.CENTER -> style.contentTop + ((contentH - totalH).coerceAtLeast(0) / 2)
            VAlign.TOP -> style.contentTop
        }
        items.forEachIndexed { idx, (b, fs, gap) ->
            val ch = heights[idx]
            // 所有块均不自动加粗（仅 **加粗标签** 生效），与所有布局一致
            units.add(makeUnit(b, cx, y, cw, ch,
                cover = true, overflow = false, fontSizeOverride = fs, alignOverride = hAlign))
            y += ch + gap
        }
        return units to null
    }

    // ── 标准（标题左侧竖条 + 单列，顶级标题左对齐）──
    private fun layoutStandard(blocks: List<MdBlock>): Pair<List<LaidOutUnit>, SlideDeco?> {
        val (units, deco) = layoutColumn(blocks, style.marginX, style.contentW)
        return units to deco
    }

    // ── 两栏（左右，三七分栏：标题/要点占左 30%，正文占右 70%，各列垂直居中）──
    // 重要：H3 竖线+缩进规则以整页为单位统一执行，不被分栏割裂。
    //   - 若左栏含 H3 → 右栏起始 x 与左栏 H3 缩进位对齐，跨栏体现层级；
    //   - 若右栏含 H3 → 右栏内 H3 正常带竖线+缩进；
    //   - 无 H3 则该页无竖线（与上下/三栏等布局完全一致）。
    /** 整页内容区矩形（左右/三栏带色块时据此内缩）。 */
    private fun fullRect(): Rect = Rect(style.marginX, style.contentTop, style.contentW, style.contentBottom - style.contentTop)

    /**
     * 左右分栏（结构=左右）。可传入 [frame] 与 [align]/[vCenter] 以适配「带色块内缩」或「组合对齐」。
     * 默认参数（整页内容区 / 垂直居中 / 左对齐）严格复现旧 layoutSplit 行为。
     */
    private fun layoutSplitInFrame(blocks: List<MdBlock>, frame: Rect, vCenter: Boolean, align: Align): Pair<List<LaidOutUnit>, SlideDeco> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to SlideDeco()
        val gap = style.splitGap
        // 三七分：左窄(30%) 右宽(70%)
        val leftW = ((frame.w - gap) * 0.30).toInt().coerceAtLeast(120)
        val rightW = frame.w - leftW - gap
        // 首个标题作为左栏（标题/要点），其余进右栏（正文）
        val first = blocks.firstOrNull { isHeading(it) }
        val (left, right) = if (first != null) {
            listOf(first) to blocks.filter { it !== first }
        } else {
            partitionSplit(blocks, leftW)
        }
        val lx = frame.x
        // ── 全页级三级及以下标题检测：任一栏有子级标题则统一缩进基准 ──
        val pageHasH3 = (left + right).any {
            it is MdBlock.TextBlock && it.type.ordinal >= BlockType.H3.ordinal
        }
        val LEVEL_INDENT = 24
        // 若页面含 H3，右栏起始 x 右移与左栏 H3 缩进位对齐（跨栏层级）
        val rx = frame.x + leftW + gap + if (pageHasH3) LEVEL_INDENT else 0
        // 左栏标题/要点：标题层级(H1/H2/H3)均不自动加粗（仅 **标签** 生效）；竖条统一由 layoutColumn 生成（仅 H3）
        val (leftU, leftDeco) = layoutColumn(left, lx, leftW, topStart = frame.y, availBottom = frame.y + frame.h, vCenter = vCenter, alignOverride = align)
        // 右栏宽度相应缩减（若右移了 rx）
        val actualRightW = if (pageHasH3) rightW - LEVEL_INDENT else rightW
        val (rightU, rightDeco) = layoutColumn(right, rx, actualRightW.coerceAtLeast(120), topStart = frame.y, availBottom = frame.y + frame.h, vCenter = vCenter, alignOverride = align)
        return (leftU + rightU) to SlideDeco(
            bars = leftDeco.bars + rightDeco.bars,
            quoteBg = leftDeco.quoteBg + rightDeco.quoteBg
        )
    }

    /** 旧签名兼容封装（默认复现旧 左右 版式）。 */
    private fun layoutSplit(blocks: List<MdBlock>): Pair<List<LaidOutUnit>, SlideDeco?> {
        val (u, d) = layoutSplitInFrame(blocks, fullRect(), vCenter = true, align = Align.LEFT)
        return u to d
    }

    // ── 三栏（标题置顶 + 其下左右两栏连续流分栏，顶端对齐）──
    // H3 竖线+缩进规则以整页为单位统一执行：
    //   - 顶部主标题(H1/H2)不带竖线、靠左；
    //   - 子列内首个 H3 带竖线+缩进；若任一子列含 H3，另一子列起始 x 同步右移对齐。
    /**
     * 三栏（结构=三栏）。标题置顶 + 其下左右两栏连续流分栏，顶端对齐。
     * 可传入 [frame] 与 [align] 以适配「带色块内缩」或「组合对齐」；默认参数严格复现旧 layoutThreeCol。
     */
    private fun layoutThreeColInFrame(blocks: List<MdBlock>, frame: Rect, align: Align): Pair<List<LaidOutUnit>, SlideDeco> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to SlideDeco()
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        val LEVEL_INDENT = 24   // H3 竖线缩进量（与 layoutColumn 统一）
        // 标题（首个标题）置顶
        // H1/H2 不带竖线、靠左；H3 及以下带竖线+缩进（与 layoutColumn 统一规则）
        val title = blocks.firstOrNull { isHeading(it) }
        var topY = frame.y + 4
        if (title != null) {
            val tFs = fontSizeOf(blockTypeOf(title))
            val ch = contentHeight(title, tFs)
            val isH3OrLower = title is MdBlock.TextBlock && title.type.ordinal >= BlockType.H3.ordinal
            // H3 及以下：应用缩进 + 竖线（与 layoutColumn 中 barCandidate/titleBarRect 逻辑一致）
            val tx = if (isH3OrLower) frame.x + LEVEL_INDENT else frame.x
            units.add(makeUnit(title, tx, topY, frame.w - (if (isH3OrLower) LEVEL_INDENT else 0), ch,
                cover = false, overflow = false, fontSizeOverride = tFs, alignOverride = align))
            if (isH3OrLower) {
                bars.add(titleBarRect(title, tx, topY, ch))
            }
            topY += ch + 30   // 标题到两栏的加大间距
        }
        // 其下左右两栏（剩余块均衡分配，顶端对齐不垂直居中）
        val rest = if (title != null) blocks.filter { it !== title } else blocks
        if (rest.isEmpty()) return units to SlideDeco(bars = bars)
        val gap = style.splitGap
        val colW = (frame.w - gap) / 2
        // 连续分栏流：左栏放满后，放不下的段落自动流到右栏（按段落截断）
        val (left, right) = flowSplit(rest, colW, topY, frame.y + frame.h)
        // ── 全页级 H3 检测 ──
        val restHasH3 = (left + right).any { it is MdBlock.TextBlock && it.type.ordinal >= BlockType.H3.ordinal }
        val lx = frame.x
        // 若子列含 H3，右子列 x 右移与左子列 H3 缩进位对齐
        val rx = frame.x + colW + gap + if (restHasH3) LEVEL_INDENT else 0
        val actualColW = if (restHasH3) colW - LEVEL_INDENT else colW   // 右列 x 已右移 LEVEL_INDENT，宽度同减以贴齐页面右边
        val (leftU, leftDeco) = layoutColumn(left, lx, actualColW.coerceAtLeast(120), topStart = topY, availBottom = frame.y + frame.h, alignOverride = align)
        val (rightU, rightDeco) = layoutColumn(right, rx, actualColW.coerceAtLeast(120), topStart = topY, availBottom = frame.y + frame.h, alignOverride = align)
        units.addAll(leftU); units.addAll(rightU)
        bars.addAll(leftDeco.bars); bars.addAll(rightDeco.bars)
        return units to SlideDeco(bars = bars, quoteBg = leftDeco.quoteBg + rightDeco.quoteBg)
    }

    /** 旧签名兼容封装（默认复现旧 三栏 版式）。 */
    private fun layoutThreeCol(blocks: List<MdBlock>): Pair<List<LaidOutUnit>, SlideDeco?> {
        val (u, d) = layoutThreeColInFrame(blocks, fullRect(), Align.LEFT)
        return u to d
    }

    // ── 四栏（标题置顶 + 其下四栏连续流分栏，顶端对齐）──
    /**
     * 四栏（结构=四栏）。标题置顶 + 其下四栏连续流分栏，顶端对齐。
     * 可传入 [frame] 与 [align] 以适配「带色块内缩」或「组合对齐」。
     */
    private fun layoutFourColInFrame(blocks: List<MdBlock>, frame: Rect, align: Align): Pair<List<LaidOutUnit>, SlideDeco> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to SlideDeco()
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        val LEVEL_INDENT = 24
        // 标题（首个标题）置顶
        val title = blocks.firstOrNull { isHeading(it) }
        var topY = frame.y + 4
        if (title != null) {
            val tFs = fontSizeOf(blockTypeOf(title))
            val ch = contentHeight(title, tFs)
            val isH3OrLower = title is MdBlock.TextBlock && title.type.ordinal >= BlockType.H3.ordinal
            val tx = if (isH3OrLower) frame.x + LEVEL_INDENT else frame.x
            units.add(makeUnit(title, tx, topY, frame.w - (if (isH3OrLower) LEVEL_INDENT else 0), ch,
                cover = false, overflow = false, fontSizeOverride = tFs, alignOverride = align))
            if (isH3OrLower) bars.add(titleBarRect(title, tx, topY, ch))
            topY += ch + 30
        }
        // 其下四栏（剩余块均衡分配）
        val rest = if (title != null) blocks.filter { it !== title } else blocks
        if (rest.isEmpty()) return units to SlideDeco(bars = bars)
        val gap = style.splitGap
        val colW = (frame.w - gap * 3) / 4
        // 按段落均衡分配到四栏
        val columns = splitToNColumns(rest, 4, colW, topY, frame.y + frame.h)
        val restHasH3 = columns.flatten().any { it is MdBlock.TextBlock && it.type.ordinal >= BlockType.H3.ordinal }
        columns.forEachIndexed { ci, col ->
            val cx = frame.x + ci * (colW + gap) + if (restHasH3 && ci > 0) LEVEL_INDENT else 0
            val actualColW = if (restHasH3 && ci > 0) colW - LEVEL_INDENT else colW
            val (colU, colDeco) = layoutColumn(col, cx, actualColW.coerceAtLeast(80), topStart = topY, availBottom = frame.y + frame.h, alignOverride = align)
            units.addAll(colU); bars.addAll(colDeco.bars)
        }
        return units to SlideDeco(bars = bars)
    }

    // ── 上窄下宽（上区窄幅居中 + 下区全宽展开）──
    /**
     * 上窄下宽（结构=上窄下宽）。上区内容窄幅（60% 宽）居中 + 下区内容全宽展开。
     * 上区放首个标题/要点，下区放其余块。
     */
    private fun layoutTopNarrowInFrame(blocks: List<MdBlock>, frame: Rect, vCenter: Boolean, align: Align): Pair<List<LaidOutUnit>, SlideDeco> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to SlideDeco()
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        val narrowW = (frame.w * 0.60).toInt().coerceAtLeast(200)
        val narrowX = frame.x + (frame.w - narrowW) / 2
        // 上区：取首个标题块（窄幅居中）
        val title = blocks.firstOrNull { isHeading(it) }
        val topBlocks = if (title != null) listOf(title) else blocks.take(1)
        val restBlocks = if (title != null) blocks.filter { it !== title } else blocks.drop(1)
        var topY = frame.y + 4
        // 渲染上区窄幅内容
        topBlocks.forEach { b ->
            val fs = fontSizeOf(blockTypeOf(b))
            val ch = contentHeight(b, fs, narrowW)
            val isH3OrLower = b is MdBlock.TextBlock && b.type.ordinal >= BlockType.H3.ordinal
            val tx = if (isH3OrLower) narrowX + 24 else narrowX
            val tw = if (isH3OrLower) narrowW - 24 else narrowW
            units.add(makeUnit(b, tx, topY, tw, ch, cover = false, overflow = false, fontSizeOverride = fs, alignOverride = Align.CENTER))
            if (isH3OrLower) bars.add(titleBarRect(b, tx, topY, ch))
            topY += ch + 20
        }
        // 下区：全宽展开剩余内容
        if (restBlocks.isNotEmpty()) {
            val bottomY = topY + 12
            val (restU, restDeco) = layoutColumn(restBlocks, frame.x, frame.w, topStart = bottomY, availBottom = frame.y + frame.h, vCenter = vCenter, alignOverride = align)
            units.addAll(restU); bars.addAll(restDeco.bars)
        }
        return units to SlideDeco(bars = bars)
    }

    /** 将块列表均衡分配到 N 栏（按段落截断、连续流）。 */
    private fun splitToNColumns(blocks: List<MdBlock>, n: Int, colW: Int, topY: Int, availBottom: Int): List<List<MdBlock>> {
        val columns = List(n) { mutableListOf<MdBlock>() }
        val colHeights = IntArray(n) { topY }
        for (block in blocks) {
            val fs = fontSizeOf(blockTypeOf(block))
            val bh = contentHeight(block, fs, colW)
            // 找当前最矮的栏
            val ci = colHeights.indices.minByOrNull { colHeights[it] } ?: 0
            if (colHeights[ci] + bh > availBottom) {
                // 所有栏都放不下，放最后一栏
                columns.last().add(block)
                colHeights[columns.lastIndex] += bh
            } else {
                columns[ci].add(block)
                colHeights[ci] += bh
            }
        }
        return columns
    }

    // ────────────────────────────────────────────────
    // 组合驱动渲染（阶段二）：结构 × 色块 × 对齐 × 间距
    // ────────────────────────────────────────────────

    /** 色块对应母版（几何与着色由 PptLayoutTemplates 驱动，与特殊页一致）。 */
    private fun bandTemplateOf(cb: ColorBlock): SlideLayout? = when (cb) {
        ColorBlock.LEFT -> SlideLayout.SECTION
        ColorBlock.TOP -> SlideLayout.TOC
        ColorBlock.BOTTOM -> SlideLayout.ENDING
        else -> null
    }

    /** 取某色块母版的装饰条几何（仅坐标，颜色由主题解析）。 */
    private fun bandRectFor(layout: SlideLayout): Rect {
        val b = PptLayoutTemplates.get(layout).bands.first()
        val w = (b.wFrac * style.canvasW).toInt().coerceAtLeast(b.minW)
        val h = (b.hFrac * style.canvasH).toInt().coerceAtLeast(b.minH)
        val x = (b.xFrac * style.canvasW).toInt()
        val y = if (b.anchorBottom) style.canvasH - h else (b.yFrac * style.canvasH).toInt()
        return Rect(x, y, w, h)
    }

    /** 色块装饰（左/右/上/下色条）。全色(NONE)与无色块返回空。 */
    private fun decoFor(comp: SlideComposition, theme: PptTheme): SlideDeco {
        when (comp.colorBlock) {
            ColorBlock.RIGHT -> {
                val bandW = (PptLayoutTemplates.SIDE_BAND_RATIO * style.canvasW).toInt()
                val x = style.canvasW - bandW
                return SlideDeco(bars = listOf(Rect(x, 0, bandW, style.canvasH)), barColor = theme.coverBg)
            }
            else -> {
                val tpl = bandTemplateOf(comp.colorBlock) ?: return SlideDeco()
                val resolved = PptLayoutTemplates.resolveBands(PptLayoutTemplates.get(tpl), style, theme)
                return SlideDeco(bars = resolved.map { it.first }, barColor = resolved.first().second)
            }
        }
    }

    /** 文本框区域：有色块时按 bandGap 内缩避让（取 max(页边距, 色条+间距) 保证不重叠且至少留间距）；全色/无色块占整页内容区。 */
    private fun frameFor(comp: SlideComposition): Rect {
        val mx = style.marginX
        val cTop = style.contentTop
        val cw = style.contentW
        val cBottom = style.contentBottom
        val bandW = bandRectFor(SlideLayout.SECTION).w   // 左色条宽 144
        val bandH = bandRectFor(SlideLayout.TOC).h       // 上/下色带高 40
        return when (comp.colorBlock) {
            ColorBlock.NONE, ColorBlock.COVER -> Rect(mx, cTop, cw, cBottom - cTop)
            ColorBlock.LEFT -> {
                // 文本框左缘 = max(页左边距, 左色条右缘 + 间距)，避免与色条重叠
                val left = maxOf(mx, bandW + comp.bandGap)
                Rect(left, cTop, (mx + cw) - left, cBottom - cTop)
            }
            ColorBlock.TOP -> {
                // 文本框上缘 = max(页上边距, 上色带下缘 + 间距)
                val top = maxOf(cTop, bandH + comp.bandGap)
                Rect(mx, top, cw, cBottom - top)
            }
            ColorBlock.BOTTOM -> {
                // 文本框下缘 = 下色带上缘 − 间距；上缘仍为页上边距
                val bottom = style.canvasH - bandH - comp.bandGap
                Rect(mx, cTop, cw, (bottom - cTop).coerceAtLeast(40))
            }
            ColorBlock.RIGHT -> {
                // 文本框右缘 = 右色条左缘 − 间距；左缘仍为页左边距
                val right = style.canvasW - bandW - comp.bandGap
                Rect(mx, cTop, (right - mx).coerceAtLeast(40), cBottom - cTop)
            }
        }
    }

    /**
     * 统一组合渲染（role=NONE 的内容页）。
     * 把「结构 × 色块 × 对齐 × 间距」分解为：色块装饰(bars) + 文本框(frame) + 结构渲染器。
     * - 无色块的三类对齐精确复用既有 上下/左中/居中 函数，保证输出零变化；
     * - 带色块或非常规对齐走通用 layoutColumn / 分栏，文本框按 frame 内缩避让色块；
     * - 全色底色页隐藏 H3 竖线（整页同色不可见），文字反色由 slide.cover 处理。
     */
    private fun layoutComposition(comp: SlideComposition, blocks: List<MdBlock>, theme: PptTheme): Pair<List<LaidOutUnit>, SlideDeco?> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to null
        val baseDeco = decoFor(comp, theme)
        val frame = frameFor(comp)
        val align = if (comp.halign == HAlign.CENTER) Align.CENTER else Align.LEFT
        val vCenter = comp.valign == VAlign.CENTER
        // 结构 × 色块 × 对齐 渲染（显式赋值，规避嵌套 when 的 LUB 类型推断退化）
        val units: List<LaidOutUnit>
        var contentDeco: SlideDeco?
        when (comp.structure) {
            Structure.VERTICAL -> if (comp.colorBlock == ColorBlock.NONE) {
                when {
                    !vCenter && comp.halign == HAlign.LEFT -> { val (u, d) = layoutStandard(blocks); units = u; contentDeco = d }        // 上下（上左对齐）
                    vCenter && comp.halign == HAlign.LEFT -> { val (u, d) = layoutList(blocks); units = u; contentDeco = d }             // 左中（居中左对齐）
                    vCenter && comp.halign == HAlign.CENTER -> { val (u, d) = layoutQuote(blocks); units = u; contentDeco = d }          // 居中（居中对齐）
                    else -> { val (u, d) = layoutColumn(blocks, frame.x, frame.w, topStart = frame.y, availBottom = frame.y + frame.h, vCenter = vCenter, alignOverride = align); units = u; contentDeco = d }
                }
            } else {
                val (u, d) = layoutColumn(blocks, frame.x, frame.w, topStart = frame.y, availBottom = frame.y + frame.h, vCenter = vCenter, alignOverride = align)
                units = u; contentDeco = d
            }
            Structure.TWO_COL -> { val (u, d) = layoutSplitInFrame(blocks, frame, vCenter = vCenter, align = align); units = u; contentDeco = d }
            Structure.THREE_COL -> { val (u, d) = layoutThreeColInFrame(blocks, frame, align = align); units = u; contentDeco = d }
            Structure.FOUR_COL -> { val (u, d) = layoutFourColInFrame(blocks, frame, align = align); units = u; contentDeco = d }
            Structure.TOP_NARROW -> { val (u, d) = layoutTopNarrowInFrame(blocks, frame, vCenter = vCenter, align = align); units = u; contentDeco = d }
        }
        // 合并色块装饰（band）与内容装饰（H3 竖线 / 引用背景）
        val merged = SlideDeco(
            bars = baseDeco.bars + (contentDeco?.bars ?: emptyList()),
            barColor = baseDeco.barColor ?: contentDeco?.barColor,
            quoteBg = baseDeco.quoteBg + (contentDeco?.quoteBg ?: emptyList()),
            accentBg = contentDeco?.accentBg ?: false
        )
        // 全色底色页：整页同色，H3 竖线不可见，统一不画（决策：全色隐藏 H3 竖线）
        val finalDeco = if (comp.colorBlock == ColorBlock.COVER) merged.copy(bars = emptyList()) else merged
        return units to finalDeco
    }

    // ── 章节页（左右二八分：左侧 20% 整高强调色块，右侧 80% 文字垂直居中、加粗，黑字，不自动加序号）──
    private fun layoutSection(blocks: List<MdBlock>, sectionNum: Int, theme: PptTheme): Pair<List<LaidOutUnit>, SlideDeco?> {
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        // 左侧满高色条（从画布顶部到底部，不留边距）：几何与着色由母版模板驱动（需求 3）
        val tpl = PptLayoutTemplates.get(SlideLayout.SECTION)
        val (sideBand, sideColor) = PptLayoutTemplates.resolveBands(tpl, style, theme).first()
        bars.add(sideBand)
        val leftW = sideBand.w
        // 右侧文字区（占剩余约 80%）：文本框区域由母版模板推导
        val frame = PptLayoutTemplates.resolveFrame(tpl, style)
        val gap = tpl.bodyGap
        val rx = frame.x + leftW + gap
        val rw = frame.w - leftW - gap
        // 右侧内容：标题 + 标题之后的全部块（不自动加序号，且不删减文本）
        val title = blocks.firstOrNull { isHeading(it) }
        val items = mutableListOf<Pair<MdBlock, Int>>()
        // 章节页标题与上下布局同级标题保持一致（去掉原 +6 放大）
        val titleFs = if (title != null) fontSizeOf(blockTypeOf(title)) else style.fsH1
        if (title != null) items.add(title to titleFs)
        // 渲染标题之后的全部块（不再只取首个段落），避免删减文本
        blocks.filter { it !== title }.forEach { items.add(it to fontSizeOf(blockTypeOf(it))) }
        if (items.isEmpty()) {
            // 兜底：原样渲染所有块
            blocks.forEach { items.add(it to fontSizeOf(blockTypeOf(it))) }
        }
        // 计算总高（标题与描述间距 18pt），右侧整体垂直居中
        val itemGap = 18
        var totalH = 0
        val heights = items.map { (b, fs) ->
            val ch = contentHeight(b, fs, rw)
            totalH += ch + itemGap
            ch
        }
        totalH = (totalH - itemGap).coerceAtLeast(0)
        var y = style.contentTop + ((style.contentBottom - style.contentTop - totalH).coerceAtLeast(0) / 2)
        items.forEachIndexed { idx, (b, fs) ->
            val ch = heights[idx]
            // 所有块均不自动加粗（仅 **加粗标签** 生效），与所有布局一致
            units.add(makeUnit(b, rx, y, rw, ch,
                cover = false, overflow = false, fontSizeOverride = fs, alignOverride = Align.LEFT))
            y += ch + itemGap
        }
        // 黑字（无强调背景）：文字使用主题 titleColor/bodyColor
        // 左侧色块用封面主色调 coverBg，与封面/整套主题主色保持一致（而非 accent）
        return units to SlideDeco(bars = bars, accentBg = false, barColor = sideColor)
    }

    // ── 居中页（金句/引用突出展示：水平垂直居中，渲染全部块，不删减文本）──
    // 样式统一：所有块字号/字体/加粗由 MD 标签决定（与所有布局一致），仅对齐居中（位置不同）。
    private fun layoutQuote(blocks: List<MdBlock>): Pair<List<LaidOutUnit>, SlideDeco?> {
        if (blocks.isEmpty()) return emptyList<LaidOutUnit>() to null
        val units = mutableListOf<LaidOutUnit>()
        val gap = 16
        val items = blocks.map { b ->
            val fs = fontSizeOf(blockTypeOf(b))
            b to contentHeight(b, fs, style.contentW)
        }
        val totalH = items.sumOf { it.second } + gap * (items.size - 1)
        var y = style.contentTop + ((style.contentBottom - style.contentTop - totalH).coerceAtLeast(0) / 2)
        for ((b, ch) in items) {
            val fs = fontSizeOf(blockTypeOf(b))
            // 不加粗（仅 **加粗标签** 生效），与所有布局一致
            units.add(makeUnit(b, style.marginX, y, style.contentW, ch,
                cover = false, overflow = false, fontSizeOverride = fs, alignOverride = Align.CENTER))
            y += ch + gap
        }
        return units to null
    }

    // ── 要点页（标题居中 + 强调下划线 + 放大要点列表，区别于"上下"的标题左竖条）──
    // ── 要点页（标题居中置顶，其下内容垂直居中放大显示，无下划线）──
    private fun layoutList(blocks: List<MdBlock>): Pair<List<LaidOutUnit>, SlideDeco?> {
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        // 标题 + 其下内容作为一个整体垂直居中；标题下移（不再贴顶），标题与文本间距收紧
        val title = blocks.firstOrNull { isHeading(it) }
        val rest = if (title != null) blocks.filter { it !== title } else blocks
        // 列表页标题与上下布局同级标题保持一致
        val tFs = if (title != null) fontSizeOf(blockTypeOf(title)) else style.fsH1
        // 标题及之后内容：字号均按 MD 标签取标准值（与所有布局一致），不统一为 fsBody
        val fsOf = { b: MdBlock -> fontSizeOf(blockTypeOf(b)) }
        val titleCh = if (title != null) contentHeight(title, tFs) else 0
        val titleGap = 8   // 标题与文本之间间距（收紧）
        val contentH = if (rest.isEmpty()) 0
            else rest.sumOf { contentHeight(it, fsOf(it), style.contentW) + gapOf(blockTypeOf(it)) + 8 }
        val totalH = titleCh + (if (title != null && rest.isNotEmpty()) titleGap else 0) + contentH
        // 整体在内容区内垂直居中
        var y = style.contentTop + ((style.contentBottom - style.contentTop - totalH).coerceAtLeast(0) / 2)
        if (title != null) {
            // 要点页主标题（H1/H2）左对齐，按规则不带竖线（仅 H3 带竖线）
            units.add(makeUnit(title, style.marginX, y, style.contentW, titleCh,
                cover = false, overflow = false, fontSizeOverride = tFs))
            y += titleCh + titleGap
        }
        for (block in rest) {
            val fs = fsOf(block)
            val ch = contentHeight(block, fs, style.contentW)
            units.add(makeUnit(block, style.marginX, y, style.contentW, ch,
                cover = false, overflow = false, fontSizeOverride = fs, alignOverride = Align.CENTER))
            y += ch + gapOf(blockTypeOf(block)) + 8
        }
        return units to SlideDeco(bars = bars)
    }

    // ── 目录页（整页上下结构，上 1/10 满屏主色块 + 标题反白，下 9/10 条目）──
    // 本页若未手动写条目，则从全文 H1/H2 标题自动生成目录，避免空白页。
    private fun layoutToc(blocks: List<MdBlock>, theme: PptTheme, autoEntries: List<MdBlock> = emptyList()): Pair<List<LaidOutUnit>, SlideDeco?> {
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        // 上部分：满屏主色调色块（与封面主色一致），约占整页 10%——几何与着色由母版模板驱动（需求 3）
        val tpl = PptLayoutTemplates.get(SlideLayout.TOC)
        val (band, bandColor) = PptLayoutTemplates.resolveBands(tpl, style, theme).first()
        val bandH = band.h
        bars.add(band)

        // 标题：优先本页首个标题；本页无标题且需自动目录时补一个"目录"
        var title = blocks.firstOrNull { isHeading(it) }
        var rest = if (title != null) blocks.filter { it !== title } else blocks.toList()
        // 自动目录：本页无条目时，用全文标题填充（用户手动写的条目优先，不会被覆盖）
        if (rest.isEmpty() && autoEntries.isNotEmpty()) {
            rest = autoEntries
            if (title == null) title = MdBlock.TextBlock(BlockType.H1, fragmentsOf("目录"))
        }

        // 标题渲染（色块内反白、左对齐、垂直居中）
        if (title != null) {
            val tFs = fontSizeOf(blockTypeOf(title))
            val ch = contentHeight(title, tFs, style.contentW)
            val ty = ((bandH - ch) / 2).coerceAtLeast(0)
            units.add(makeUnit(title, style.marginX, ty, style.contentW, ch,
                cover = false, overflow = false, fontSizeOverride = tFs,
                alignOverride = Align.LEFT, colorOverride = "FFFFFF"))
        }

        // 下部分（约 90%）：目录条目正常渲染（H3 带竖线、层级缩进规则一致生效），间距加大 60% 提升可读性
        val topY = bandH + tpl.bodyGap
        val (restUnits, restDeco) = layoutColumn(rest, style.marginX, style.contentW, topStart = topY, gapMultiplier = 1.6f)
        units.addAll(restUnits)
        bars.addAll(restDeco.bars)
        // 色块着色用封面主色调 coverBg，与整套主题主色保持一致
        return units to SlideDeco(bars = bars, barColor = bandColor, quoteBg = restDeco.quoteBg)
    }

    // ── 结尾页（极简留白：页面正中居中总结语/感谢语，底部小字落款，无多余元素）──
    // 本页若未写任何内容，默认致谢语"感谢聆听"，避免导出空白页。
    private fun layoutEnding(blocks: List<MdBlock>, theme: PptTheme): Pair<List<LaidOutUnit>, SlideDeco?> {
        // 结尾页永不留白：无内容时给默认致谢语
        val src = if (blocks.isEmpty()) listOf(MdBlock.TextBlock(BlockType.QUOTE, fragmentsOf("感谢聆听"))) else blocks
        val units = mutableListOf<LaidOutUnit>()
        val cx = style.marginX
        val cw = style.contentW

        // 底部色块：页面最下端 1/10 页高、宽度满屏，与主色调一致——几何与着色由母版模板驱动（需求 3）
        val tpl = PptLayoutTemplates.get(SlideLayout.ENDING)
        val (band, bandColor) = PptLayoutTemplates.resolveBands(tpl, style, theme).first()
        val bandH = band.h
        val bandTop = band.y
        val bars = mutableListOf(band)

        // 主文字：总结语 / 感谢语（取首个非空块），居中
        val main = src.firstOrNull { it is MdBlock.TextBlock && it.type in setOf(BlockType.QUOTE, BlockType.PARAGRAPH) }
            ?: src.first()
        // 字号统一：主文字按 MD 标签取标准值（与所有布局一致），不额外放大
        val mainFs = fontSizeOf(blockTypeOf(main))
        val mainCh = contentHeight(main, mainFs)

        // 底部落款（剩余块作为落款/单位/日期，全部渲染，不删减文本）
        val meta = src.filter { it !== main }
        // 落款中的块：字号按 MD 标签取标准值（与所有布局一致），不统一为 fsBody
        val metaFsOf = { b: MdBlock -> fontSizeOf(blockTypeOf(b)) }
        val metaGap = 6
        var metaH = 0
        val metaHeights = meta.map { b ->
            val h = contentHeight(b, metaFsOf(b), cw)
            metaH += h + metaGap
            h
        }
        metaH = (metaH - metaGap).coerceAtLeast(0)

        // 标题与文本框间距 = 1/10 页高（与底部色块同高，统一由母版推导）；整体垂直居中于上方区域，水平居中
        val gap10 = bandH
        val blockH = mainCh + gap10 + metaH
        val areaBottom = bandTop            // 内容居中区域下界：避让底部色块
        val startY = ((areaBottom - blockH) / 2).coerceAtLeast(style.contentTop)
        val mainY = startY
        units.add(makeUnit(main, cx, mainY, cw, mainCh,
            cover = false, overflow = false, fontSizeOverride = mainFs, alignOverride = Align.CENTER))
        var my = mainY + mainCh + gap10
        for (idx in meta.indices) {
            val b = meta[idx]
            val ch = metaHeights[idx]
            units.add(makeUnit(b, cx, my, cw, ch,
                cover = false, overflow = false, fontSizeOverride = metaFsOf(b), alignOverride = Align.CENTER))
            my += ch + metaGap
        }

        // 底部满屏主色块（barColor 取封面主色调）；其余透明
        return units to SlideDeco(bars = bars, barColor = bandColor)
    }

    // ── 在指定列内纵向堆叠，返回 渲染单元 + 标题矩形（用于竖条）──
    // topStart: 起始 Y；availBottom: 可用下边界（用于垂直居中计算）；vCenter: 内容整体垂直居中。
    // gapMultiplier: 段后距倍数（目录页等需要更宽松间距时可 > 1.0）。
    private fun layoutColumn(
        blocks: List<MdBlock>,
        x: Int,
        w: Int,
        cover: Boolean = false,
        listFs: Int = style.fsBody,
        topStart: Int = style.contentTop,
        availBottom: Int = style.contentBottom,
        vCenter: Boolean = false,
        gapMultiplier: Float = 1.0f,
        alignOverride: Align? = null
    ): Pair<List<LaidOutUnit>, SlideDeco> {
        val units = mutableListOf<LaidOutUnit>()
        val bars = mutableListOf<Rect>()
        // 先用实际列宽 w 估算总高，便于垂直居中
        val totalH = run {
            var h = 0
            blocks.forEachIndexed { idx, block ->
                val fs = if (block is MdBlock.ListBlock) listFs else fontSizeOf(blockTypeOf(block))
                h += contentHeight(block, fs, w) + (gapOf(blockTypeOf(block)) * gapMultiplier).toInt()
                // 引用块（非本列首个块）：与上方文本保持适度段前距，计入总高以保证垂直居中正确
                if (block is MdBlock.TextBlock && block.type == BlockType.QUOTE && idx > 0) {
                    h += style.quoteGapBefore
                }
            }
            h
        }
        var y = topStart
        if (vCenter && blocks.isNotEmpty()) {
            val avail = availBottom - topStart
            y = topStart + maxOf(0, (avail - totalH) / 2)
        }
        // ── 竖线归属：仅内容级 H3 标题带竖线 ──
        // 章节大标题（H1）与二级标题（H2）都不加竖线，避免抢占竖线样式；
        // 竖线唯一归属每页第一个 H3（如"4.1 举证"），无 H3 则该页不画竖线。
        val barCandidate = blocks
            .mapNotNull { it as? MdBlock.TextBlock }
            .firstOrNull { it.type == BlockType.H3 }

        // ── 层级缩进：H1/H2 靠左（原始列起始 x），H3 及后续内容右缩进 ──
        // 以竖线为左对齐基准：H3 文字与下方文本/列表同 x，体现 H1→H2→H3 层级结构。
        // 缩进量 24pt：让上级标题(H1/H2)明显比 H3 更靠左，视觉上一目了然。
        // 例外：居中对齐时 H3 不缩进（竖线作为文本框内联元素，随文字一起居中，避免与文字分离）。
        val LEVEL_INDENT = 24
        val isCenterAlign = alignOverride == Align.CENTER
        var useX = x                          // 初始 = 列起始 x（H1/H2 在此）
        var h3IndentApplied = false           // 是否已切换到 H3 缩进
        var firstPlaced = false               // 本列是否已放置过任意块（用于判断引用块是否首块）

        val quoteBg = mutableListOf<Rect>() // 引用块（Markdown `>`）浅色圆角背景矩形

        for (block in blocks) {
            var fs = if (block is MdBlock.ListBlock) listFs else fontSizeOf(blockTypeOf(block))
            // 引用块（非本列首个块）：与上方文本保持适度段前距（块级，预览/导出一致）
            if (block is MdBlock.TextBlock && block.type == BlockType.QUOTE && firstPlaced) {
                y += style.quoteGapBefore
            }
            // 用实际列宽 w 算高（三栏/左右窄栏时行数翻倍，文本框随之增高）
            var ch = contentHeight(block, fs, w)
            // 窄栏（三栏/左右）时增加 15% 高度余量：Compose 实际行高略高于引擎估算值，避免预览截断
            if (w < style.contentW * 0.8) ch = (ch * 1.15).toInt()
            // 单块超高（超过本页剩余可用高度 availBottom−y）：缩小字号使其刚好不溢出下边距，避免截断。
            // 与代码块 splitLongCode 同理，优先保证「所有文本框不超出下边距」，代价是超长块字号略小。
            val availH = availBottom - y
            if (availH > 0 && ch > availH) {
                fs = maxOf(9, (fs * availH.toDouble() / ch).toInt())
                ch = contentHeight(block, fs, w)
            }
            val bh = ch + (gapOf(blockTypeOf(block)) * gapMultiplier).toInt()

            // 首次遇到三级及以下标题(H3/H4/H5/H6)：切换到缩进 x（子级标题及之后内容统一对齐到竖线基准）
            // 居中对齐时不缩进：竖线将以内联方式随文本框一起绘制，避免与文字分离。
            if (!isCenterAlign && !h3IndentApplied && block is MdBlock.TextBlock && block.type.ordinal >= BlockType.H3.ordinal) {
                useX = x + LEVEL_INDENT
                h3IndentApplied = true
            }

            // 居中对齐时：H3 竖线不放入全局 bars，改由 UnitBox 内联绘制（随文本框一起居中）
            if (!isCenterAlign && block === barCandidate) {
                bars.add(titleBarRect(block, useX, y, ch))
            }
            // 引用块（Markdown `>`）：不缩进，与上级文本左对齐；仅用浅色圆角背景底色区分（无左侧竖线）
            val isQuote = block is MdBlock.TextBlock && block.type == BlockType.QUOTE
            // 层级缩进(LEVEL_INDENT)只右移左缘，宽度须同步缩减，保证所有文本框右缘统一停在 x+w
            // （即与页面同一数值右边距），H3 及之后内容不会溢出页面右边界
            val indent = (useX - x).coerceAtLeast(0)
            val qx = useX
            val qw = (w - indent).coerceAtLeast(40)
            if (isQuote) {
                val padX = 8
                val padY = 6
                quoteBg.add(Rect(qx - padX, y - padY, qw + padX * 2, ch + padY * 2))
            }
            units.add(makeUnit(block, qx, y, qw, ch, cover, ch > PAGE_CONTENT_H, fontSizeOverride = fs, alignOverride = alignOverride))
            y += bh
            firstPlaced = true
        }
        return units to SlideDeco(bars = bars, quoteBg = quoteBg)
    }

    /** 是否为标题块（H1/H2/H3）。 */
    private fun isHeading(block: MdBlock): Boolean =
        block is MdBlock.TextBlock && block.type in setOf(
            BlockType.H1, BlockType.H2, BlockType.H3, BlockType.H4, BlockType.H5, BlockType.H6
        )

    /**
     * 标题左侧竖条矩形（仅 H3 使用）。
     * - 宽/偏移取自母版模板（默认宽 4pt、标题文字左侧 12pt、颜色统一主色 accent）；
     * - 几何与规格统一由母版模板驱动（需求 3），改版式只改 PptLayoutTemplates。
     */
    private fun titleBarRect(block: MdBlock, x: Int, y: Int, h: Int): Rect {
        return PptLayoutTemplates.titleBarRect(block, x, y, h)
    }

    // ── 表格：智能列宽分配（按内容加权），按单元格换行估算行高 ──

    /**
     * 根据单元格文本内容智能分配列宽：
     * - 统计每列的内容权重（中文字≈1.0，西文≈0.55）
     * - 短列（如序号、状态）拿最小宽度，长列拿剩余空间
     * - 避免无脑均分导致的「短列浪费、长列挤压」
     */
    private fun smartTableColWidths(
        header: List<List<InlineFragment>>,
        rows: List<List<List<InlineFragment>>>,
        totalW: Int,
        minColW: Int = 40
    ): List<Int> {
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0, 1)
        if (cols <= 1) return listOf(totalW)

        // 计算每列的内容权重
        val weights = DoubleArray(cols)
        for (c in 0 until cols) {
            var weight = 0.0
            val headerCell = header.getOrNull(c)
            if (headerCell != null) {
                weight += cellContentWeight(headerCell) * 1.2  // 表头略加权
            }
            for (row in rows) {
                val cell = row.getOrNull(c) ?: continue
                weight += cellContentWeight(cell)
            }
            weights[c] = weight.coerceAtLeast(1.0)
        }

        // 短列判定：权重低于平均值 30%
        val avgWeight = weights.average()
        val isShortCol = weights.map { it < avgWeight * 0.3 }

        val remaining = (totalW - minColW * cols).coerceAtLeast(0)
        val totalLongWeight = weights.mapIndexed { idx, w ->
            if (isShortCol[idx]) 0.0 else w
        }.sum()
        val shortCount = isShortCol.count { it }
        val longCount = cols - shortCount

        val colW = IntArray(cols)
        for (c in 0 until cols) {
            if (isShortCol[c]) {
                colW[c] = minColW
            } else {
                val share = if (totalLongWeight > 0 && longCount > 0) {
                    (weights[c] / totalLongWeight * remaining).toInt().coerceAtLeast(minColW)
                } else if (longCount > 0) {
                    (remaining / longCount).toInt().coerceAtLeast(minColW)
                } else {
                    minColW
                }
                colW[c] = share
            }
        }

        // 修正总和 = totalW（最后一列吃余数）
        val sum = colW.sum()
        if (sum != totalW) {
            colW[cols - 1] += (totalW - sum)
        }
        return colW.toList()
    }

    /** 计算单元格内容的视觉宽度权重：中文≈1.0，西文≈0.55，标点≈0.3。 */
    private fun cellContentWeight(frags: List<InlineFragment>): Double {
        val text = frags.joinToString("") { it.text }
        if (text.isEmpty()) return 0.5
        var weight = 0.0
        for (c in text) {
            weight += when {
                c in '0'..'9' -> 0.5
                c in PUNCTUATION_CHARS -> 0.3
                c.code in 0x4E00..0x9FFF -> 1.0   // CJK 汉字
                c.code in 0x3000..0x303F -> 0.6   // CJK 兼容
                c.isLetterOrDigit() -> 0.55
                else -> 0.5
            }
        }
        return weight
    }

    private fun buildTableRender(block: MdBlock.TableBlock, w: Int): TableRender {
        val colW = smartTableColWidths(block.header, block.rows, w)
        val headerFs = style.fsBody + 2
        val cellFs = style.fsBody - 1
        val headerH = if (block.header.isNotEmpty()) tableRowH(block.header, colW, headerFs) else 0
        val rowHs = block.rows.map { tableRowH(it, colW, cellFs) }
        return TableRender(block.header, block.rows, colW, block.colAlign, headerH, rowHs, cellFs, headerFs)
    }

    /** 单行最大行数决定的行高（含单元格上下内边距）。 */
    private fun tableRowH(cells: List<List<InlineFragment>>, colW: List<Int>, fs: Int): Int {
        var maxLines = 1
        for (j in colW.indices) {
            val frags = cells.getOrNull(j) ?: emptyList()
            val text = frags.joinToString("") { it.text }
            val avail = (colW[j] - style.tablePad * 2).coerceAtLeast(20)
            val lines = lineCount(text, fs, avail)
            if (lines > maxLines) maxLines = lines
        }
        return maxLines * (fs * style.lineMult).toInt() + style.tablePad * 2
    }

    /** 把一个页面的块拆成左右两栏（用列宽[width]估算高度）。 */
    private fun partitionSplit(blocks: List<MdBlock>, width: Int): Pair<List<MdBlock>, List<MdBlock>> {
        // 首个块是标题（H1–H6）→ 标题单独放左栏，正文在右栏
        val first = blocks.first()
        if (first is MdBlock.TextBlock && first.type.ordinal <= BlockType.H6.ordinal) {
            return listOf(first) to blocks.drop(1)
        }
        // 否则按累计高度贪心均衡（始终填入较矮的一栏）
        val left = mutableListOf<MdBlock>()
        val right = mutableListOf<MdBlock>()
        var lh = 0
        var rh = 0
        for (b in blocks) {
            val h = contentHeight(b, fontSizeOf(blockTypeOf(b)), width) + gapOf(blockTypeOf(b))
            if (lh <= rh) {
                left.add(b); lh += h
            } else {
                right.add(b); rh += h
            }
        }
        return left to right
    }

    /**
     * 三栏连续分栏流（报纸式）：先放满左栏，再放右栏。列表按项边界截断，其他块原子移动。
     *
     * 策略：
     * 1. 按顺序遍历所有块，贪婪地尝试放入左栏
     * 2. 每个块能整块放下就直接加入左栏
     * 3. 放不下时按类型处理：
     *    - **列表 (ListBlock)**：逐项试探，在项边界处拆分，左栏部分加入左栏并更新已用高度，
     *      然后继续循环让后续块也有机会进入左栏（最大化利用左栏空间）
     *    - **标题 (H1~H6)**：标题及之后所有内容移到右栏（避免标题与正文分离）
     *    - **其他块（段落/引用/表格/代码）**：整块移到右栏并标记"已溢出"，
     *      后续所有块都去右栏（保证左右栏内容不交错）
     *
     * 保证左栏不溢出可用高度；右栏接收全部溢出内容。
     */
    private fun flowSplit(blocks: List<MdBlock>, colW: Int, availTop: Int, availBottom: Int): Pair<List<MdBlock>, List<MdBlock>> {
        if (blocks.isEmpty()) return emptyList<MdBlock>() to emptyList()
        val availH = availBottom - availTop
        val left = mutableListOf<MdBlock>()
        val right = mutableListOf<MdBlock>()
        var usedH = 0

        // 窄栏高度余量系数（与 layoutColumn 的 1.15 保持一致，保证估算不低于实际渲染）
        val narrowFactor = if (colW < style.contentW * 0.8) 1.15f else 1.0f

        // 标记是否已开始向右栏溢出非列表内容（一旦为 true，后续所有块都去右栏）
        var overflowing = false

        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            val fs = fontSizeOf(blockTypeOf(block))
            val gap = gapOf(blockTypeOf(block))
            val ch = (contentHeight(block, fs, colW) * narrowFactor).toInt()
            val needed = ch + gap
            val fits = !overflowing && usedH + needed <= availH

            if (fits) {
                // 放得下：直接加入左栏
                left.add(block)
                usedH += needed
                i++
            } else {
                // 放不下 或 已在溢出状态：智能截断
                when (block) {
                    is MdBlock.ListBlock -> {
                        // 列表块：逐项试探，找出左栏能容纳的最大项数
                        val remaining = if (left.isEmpty()) availH else (availH - usedH).coerceAtLeast(0)
                        val (leftPart, rightPart) = splitListItems(block, colW, remaining, narrowFactor)
                        if (leftPart.items.isNotEmpty()) {
                            left.add(leftPart)  // 直接使用 splitListItems 返回的 ListBlock（已携带正确的 listStart）
                            // 用实际估算高度更新 usedH（而非直接标记满），让后续块有机会进入左栏
                            val leftH = (contentHeight(leftPart, fs, colW) * narrowFactor).toInt() + gap
                            usedH += leftH
                        }
                        if (rightPart.items.isNotEmpty()) {
                            right.add(rightPart)  // 直接使用（已携带 listStart = originalStart + splitIdx）
                            overflowing = true  // 列表有溢出，后续非列表块也应去右栏
                        }
                        i++
                    }
                    is MdBlock.TextBlock -> {
                        if (isHeading(block)) {
                            // 标题块：将标题及之后所有内容移到右栏（保持标题-正文关联）
                            right.addAll(blocks.subList(i, blocks.size))
                            break
                        } else {
                            // 普通段落/引用/代码：整块移到右栏，并标记开始溢出
                            right.add(block)
                            overflowing = true
                            i++
                        }
                    }
                    else -> {
                        // 表格/强制分页等：整块移到右栏
                        right.add(block)
                        overflowing = true
                        i++
                    }
                }
            }
        }

        // 边界情况：如果左栏仍为空（所有块都因某种原因去了右边），强制把第一个块放入左栏
        if (left.isEmpty() && right.isNotEmpty()) {
            val first = right.removeAt(0)
            left.add(first)
        }

        return left to right
    }

    /**
     * 将一个 ListBlock 在项边界处拆分为两部分：
     * - 返回 Pair(左栏部分, 右栏溢出部分)，每部分都是 ListItemData 列表
     * - 使用栏宽 colW 和可用高度 availH 进行逐项高度估算
     * - narrowFactor 与 flowSplit/layoutColumn 的窄栏余量一致
     */
    private fun splitListItems(
        list: MdBlock.ListBlock,
        colW: Int,
        availH: Int,
        narrowFactor: Float
    ): Pair<MdBlock.ListBlock, MdBlock.ListBlock> {
        if (list.items.isEmpty()) return list to list

        val fs = style.fsBody
        val prefixW = fs * 0.55 * 3   // 列表前缀宽度
        val effectiveW = (colW - prefixW.toInt()).coerceAtLeast((colW * 0.3).toInt())
        var used = 0
        var splitIdx = list.items.size   // 默认：全部放入左栏

        for (idx in list.items.indices) {
            val item = list.items[idx]
            // 嵌套缩进扣除
            val indentDeduction = (item.indent * fs * 2).coerceAtMost(effectiveW / 2)
            val itemW = (effectiveW - indentDeduction).coerceAtLeast((colW * 0.2).toInt())
            val text = item.fragments.joinToString("") { it.text }
            val lineC = lineCount(text, fs, itemW)
            val itemH = (lineC * (fs * style.lineMult * 1.04)).toInt() + 2  // +2 与 contentHeight 中 items.size*2 一致
            val totalItemH = (itemH * narrowFactor).toInt()

            if (used + totalItemH <= availH) {
                used += totalItemH
            } else {
                splitIdx = idx
                break
            }
        }

        return MdBlock.ListBlock(list.type, list.items.subList(0, splitIdx), listStart = list.listStart) to
               MdBlock.ListBlock(list.type, list.items.subList(splitIdx, list.items.size),
                   listStart = list.listStart + splitIdx)
    }

    private fun makeUnit(
        block: MdBlock,
        x: Int,
        y: Int,
        w: Int,
        ch: Int,
        cover: Boolean,
        overflow: Boolean,
        fontSizeOverride: Int? = null,
        alignOverride: Align? = null,
        bold: Boolean = false,
        colorOverride: String? = null
    ): LaidOutUnit {
        val fam = fontForType(blockTypeOf(block))
        val latin = latinForType(blockTypeOf(block))
        val gap = gapForType(blockTypeOf(block))
        return when (block) {
        is MdBlock.TextBlock -> {
            val blockFrags = if (block.type == BlockType.CODE && block.raw.isNotEmpty())
                listOf(InlineFragment(block.raw, code = true))
            else block.fragments
            val baseFs = fontSizeOverride ?: fontSizeOf(block.type)
            // 溢出自愈：单块内容超整页时按比例缩小字号，避免导出裁切
            val fs = if (overflow && ch > PAGE_CONTENT_H) maxOf(9, (baseFs * PAGE_CONTENT_H.toDouble() / ch).toInt()) else baseFs
            LaidOutUnit(
                type = block.type,
                x = x,
                y = y,
                w = w,
                h = ch,
                fontSize = fs,
                align = alignOverride ?: (if (cover) Align.CENTER else Align.LEFT),
                fragments = blockFrags,
                overflow = overflow,
                bold = bold,
                color = colorOverride,
                fontFamily = fam,
                latinFont = latin,
                gapAfter = gap
            )
        }
        is MdBlock.ListBlock -> LaidOutUnit(
            type = block.type,
            x = x + style.listIndent,
            y = y,
            w = w - style.listIndent,
            h = ch,
            fontSize = fontSizeOverride ?: style.fsBody,
            align = Align.LEFT,
                listItems = block.items,
                ordered = block.type == BlockType.ORDERED_LIST,
                listStart = block.listStart,
                overflow = overflow,
                color = colorOverride,
                fontFamily = fam,
                latinFont = latin,
                gapAfter = gap
            )
        is MdBlock.ForcedBreak -> LaidOutUnit(
            type = BlockType.DIVIDER,
            x = x,
            y = y,
                w = w,
                h = 14,
                fontSize = style.fsBody,
                align = Align.LEFT,
                fontFamily = fam,
                latinFont = latin,
                gapAfter = gap
            )
        is MdBlock.TableBlock -> {
            val tr = buildTableRender(block, w)
            LaidOutUnit(
                type = BlockType.TABLE,
                x = x,
                y = y,
                w = w,
                h = tr.totalH,
                fontSize = tr.cellFs,
                align = Align.LEFT,
                table = tr,
                fontFamily = fam,
                latinFont = latin,
                gapAfter = gap
            )
        }
    }
    }

    /** 块类型 → 东亚字体（OOXML <a:ea> typeface 名），由 CSS 的 font-family 决定。 */
    private fun fontForType(t: BlockType): String = when (t) {
        BlockType.H1, BlockType.H2, BlockType.H3, BlockType.H4, BlockType.H5, BlockType.H6 -> style.titleFont
        BlockType.CODE -> style.codeFont
        else -> style.bodyFont
    }

    /** 块类型 → 西文字体（OOXML <a:latin>/<a:cs> typeface 名）；代码固定 Consolas 等宽。 */
    private fun latinForType(t: BlockType): String = when (t) {
        BlockType.CODE -> "Consolas"
        else -> style.latinFont
    }

    /** 块类型 → 段后距（pt），由 CSS 的 margin-bottom 决定。 */
    private fun gapForType(t: BlockType): Int = when (t) {
        BlockType.H1, BlockType.H2, BlockType.H3, BlockType.H4, BlockType.H5, BlockType.H6 -> style.headGap
        else -> style.paraGap
    }
}
