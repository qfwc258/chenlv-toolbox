package com.wb.mdgw.pptx

import kotlin.math.roundToInt

/**
 * PPTX 模块数据模型与全局排版常量。
 *
 * 统一规范（对齐法律/办公场景）：16:9 画布 720×405pt，安全边距上下 30pt、左右 40pt，
 * 标题层级 / 正文 / 引用 / 代码字号固定，正文行距 1.2 倍，列表逐级缩进，全部左对齐。
 */

// ────────────────────────────────────────────────
// 全局尺寸常量（pt，行业标准 16:9）
// ────────────────────────────────────────────────

object PptSpec {
    const val CANVAS_W = 720         // 画布宽
    const val CANVAS_H = 405         // 画布高
    const val MARGIN_X = 40           // 左右安全边距
    const val MARGIN_TOP = 30         // 上边距
    const val MARGIN_BOTTOM = 30      // 下边距
    const val CONTENT_W = CANVAS_W - MARGIN_X * 2      // 内容区宽 = 640
    const val CONTENT_TOP = MARGIN_TOP
    const val CONTENT_BOTTOM = CANVAS_H - MARGIN_BOTTOM

    // 字号（pt）
    const val FS_H1 = 28
    const val FS_H2 = 24
    const val FS_H3 = 20
    const val FS_H4 = 18
    const val FS_H5 = 16
    const val FS_H6 = 14
    const val FS_BODY = 16
    const val FS_QUOTE = 15
    const val FS_CODE = 13

    // 行距倍数
    const val LINE_MULT = 1.2

    // 段后距（pt）
    const val PARA_GAP = 8
    const val HEAD_GAP = 12          // 标题后额外距

    // 列表缩进（每层级 pt）
    const val LIST_INDENT = 18
    // 引用左缩进
    const val QUOTE_INDENT = 24
    // 代码块内边距
    const val CODE_PAD = 8

    // 表格单元格内边距（pt，四边）
    const val TABLE_PAD = 6

    // 左右双栏布局
    const val SPLIT_GAP = 24          // 两栏间距
}

// ────────────────────────────────────────────────
// 块类型
// ────────────────────────────────────────────────

enum class BlockType {
    H1, H2, H3, H4, H5, H6,
    PARAGRAPH,
    BULLET_LIST, ORDERED_LIST,
    QUOTE,
    CODE,
    DIVIDER,
    TABLE
}

/** 表格列对齐方式（来自 GFM 表头分隔行 :--- / :---: / ---:）。 */
enum class TableAlign { LEFT, CENTER, RIGHT }

// ────────────────────────────────────────────────
// 每页布局模板
// ────────────────────────────────────────────────

/**
 * 固定常用布局模板（用户可在预览区逐页选择版式，默认采用上下布局）。
 * 每个模板含 key（持久化）/ label（展示）/ desc（说明）。
 *
 * 版式分类：
 * - 特殊页：全色 / 上色 / 下色
 * - 内容页：上下 / 左右 / 三栏 / 左色 / 居中 / 左中
 */
enum class SlideLayout(val key: String, val label: String, val desc: String) {
    // ── 特殊页 ──
    /** 全色：整页满屏主色块，大标题居中，作首页 */
    COVER("cover", "全色", "整页满屏主色，作首页"),
    /** 上色：顶部满宽主色带 + 章节目录列表 */
    TOC("toc", "上色", "顶部色带+目录列表"),
    /** 下色：底部满宽主色带 + 致谢结束页 */
    ENDING("ending", "下色", "底部色带+致谢页"),

    // ── 内容页 ──
    /** 上下：标题 + 正文纵向排列（默认内容模板） */
    STANDARD("standard", "上下", "标题+正文，纵向排列"),
    /** 左右：标题 + 内容左右分栏 */
    TWO_COL("two_col", "左右", "标题+内容，左右分栏"),
    /** 三栏：内容三等分 */
    THREE_COL("three_col", "三栏", "内容三等分"),
    /** 左色：左侧满高主色条 + 标题，分隔过渡页 */
    SECTION("section", "左色", "左色条分隔过渡页"),
    /** 居中：引用突出展示，整体水平垂直居中 */
    QUOTE("quote", "居中", "引用居中突出展示"),
    /** 左中：左侧强调竖条 + 要点列表 */
    LIST("list", "左中", "左竖条+要点列表");

    /** 是否为特殊页面版式（封面/目录/章节/结尾通常不需要随"套用全部页"变更） */
    val isSpecial: Boolean get() = this in setOf(COVER, TOC, SECTION, ENDING)

    companion object {
        /** 常用内容版式（排除特殊页，用于快速切换面板） */
        val contentLayouts: List<SlideLayout> = values().filter { !it.isSpecial }
        /** 全部版式（含特殊页） */
        val allLayouts: List<SlideLayout> = values().toList()
    }
}

// ────────────────────────────────────────────────
// 行内片段
// ────────────────────────────────────────────────

/** 一段带样式的文本。code/link 可与 bold/italic 并存。 */
data class InlineFragment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
    val code: Boolean = false
) {
    val isEmpty: Boolean get() = text.isEmpty()
}

/** 空片段列表工具 */
fun fragmentsOf(text: String) = listOf(InlineFragment(text))

// ────────────────────────────────────────────────
// 解析后的块
// ────────────────────────────────────────────────

sealed class MdBlock {
    /** 文本型块：标题 / 段落 / 引用 / 代码（代码用 raw 保留原样） */
    data class TextBlock(
        val type: BlockType,
        val fragments: List<InlineFragment> = emptyList(),
        val raw: String = ""
    ) : MdBlock() {
        val text: String get() = if (raw.isNotEmpty()) raw else fragments.joinToString("") { it.text }
    }

    /** 列表项（携带缩进层级，支持嵌套列表扁平化）。 */
    data class ListItemData(
        val fragments: List<InlineFragment>,
        /** 缩进层级：0=顶层，1=第一层嵌套，2=第二层嵌套…用于渲染时左缩进/前缀切换。 */
        val indent: Int = 0,
        /** MD 原文中的有序编号（1-based）。null 表示无序列表或未设置；渲染时优先使用此值。 */
        val number: Int? = null
    )

    /** 列表块：每个 item 携带片段与缩进层级（嵌套子列表被扁平化到父级 items 中，indent>0 标记嵌套深度）。 */
    data class ListBlock(
        val type: BlockType,             // BULLET_LIST / ORDERED_LIST
        val items: List<ListItemData>,
        /** 有序列表起始编号偏移量（0-based）。默认 0 表示从 1 开始编号；
         *  当列表被跨栏/跨页拆分时，右半部分应设为原始列表中的起始索引，以保持编号连续。 */
        val listStart: Int = 0
    ) : MdBlock()

    /** 手动强制分页 --- */
    class ForcedBreak : MdBlock()

    /** GFM 管道表格。header 可为空；colAlign 为各列对齐（取表头单元格对齐）。 */
    data class TableBlock(
        val header: List<List<InlineFragment>>,
        val rows: List<List<List<InlineFragment>>>,
        val colAlign: List<TableAlign>
    ) : MdBlock()
}

// ────────────────────────────────────────────────
// 分页结果与单页
// ────────────────────────────────────────────────

/** 一页幻灯片。title 取该页首个标题文本，用于预览提示；isCover 表示封面页（居中）。 */
data class SlidePage(
    val blocks: List<MdBlock>,
    val title: String = "",
    val isCover: Boolean = false
) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/** 分页结果。overflowPages 为触发"单块超长"警告的页码集合（1-based）。 */
data class PaginationResult(
    val pages: List<SlidePage>,
    val overflowPages: Set<Int> = emptySet()
)

// ────────────────────────────────────────────────
// 主题
// ────────────────────────────────────────────────

/** 全局统一主题：配色 + 封面背景。字体/字号由 PptSpec 全局锁定。 */
data class PptTheme(
    val id: String,
    val name: String,
    val bg: String,            // 幻灯片背景（hex RRGGBB）
    val titleColor: String,    // 标题文字
    val bodyColor: String,     // 正文文字
    val accent: String,        // 强调（标题左侧竖条、链接）
    val codeBg: String,        // 代码块背景
    val quoteBg: String,       // 引用块浅色圆角背景底色
    val coverBg: String = bg   // 封面背景
)

// ────────────────────────────────────────────────
// 装饰几何（坐标单位 pt，颜色由渲染层取主题）
// ────────────────────────────────────────────────

/** 单页装饰几何。 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

/** 单页装饰：accentBg=章节页强调背景（文字转白）；bars=装饰矩形（标题竖条/封面色条/强调线等）。barColor=色块着色（默认用主题强调色，章节页应传封面主色调以保持统一）。wave=底部波浪装饰开关。waveColor=波浪主色（null 用主题 accent）。bottomBar=底部直线色块装饰开关（与波浪并列、可独立开关）。bottomBarH=直线色块高度（pt，由布局按"画布高 1/N"计算，默认 1/60 页高、可在设置中调）。颜色固定跟随主题主色调（theme.accent），不再提供单独取色。 */
data class SlideDeco(
    val accentBg: Boolean = false,
    val bars: List<Rect> = emptyList(),
    val barColor: String? = null,
    val quoteBg: List<Rect> = emptyList(),
    val wave: Boolean = false,
    val waveColor: String? = null,
    val bottomBar: Boolean = false,
    val bottomBarH: Int = 0,
    val logo: Boolean = false
) {
    /** 兼容旧调用：取首个装饰矩形（标题竖条）。 */
    val bar: Rect? get() = bars.firstOrNull()
}

/** 波浪装饰的单层数据：用于预览（Compose Path）和导出（OOXML custGeom）同源消费。 */
data class WaveLayer(
    /** 该层波浪的贝塞尔控制点列表（相对坐标 0~1，x 从左到右，y 从上到下）。每 4 个点为一组 cubicBezier。 */
    val controlPoints: List<Float>,
    /** 填充颜色（hex RRGGBB）。 */
    val color: String,
    /** 透明度（0~1），预览用 alpha，导出时转为 srgbClr 的透明度。 */
    val alpha: Float
)

/** 页脚（页码）。仅内容页显示，特殊页（封面/目录/章节/结尾）不显示。 */
data class SlideFooter(val text: String)

// ────────────────────────────────────────────────
// 组合式页面模型（结构 × 色块 × 对齐 × 间距）
// 阶段一：仅引入模型与「版式→组合」解析器，渲染仍走既有 layoutXxx，输出零变化。
// 预览与导出已与版式名解耦（只消费 cover + deco），故组合仅作为统一描述，后续阶段再驱动渲染。
// ────────────────────────────────────────────────

/** 结构轴：上下 / 左右 / 三栏 / 四栏 / 上窄下宽 */
enum class Structure { VERTICAL, TWO_COL, THREE_COL, FOUR_COL, TOP_NARROW }

/** 色块轴：无 / 全色 / 左色 / 上色 / 下色 / 右色 */
enum class ColorBlock { NONE, COVER, LEFT, TOP, BOTTOM, RIGHT }

/** 竖直对齐：上 / 居中 */
enum class VAlign { TOP, CENTER }

/** 水平对齐：左 / 居中（与用户原话映射：上左对齐=TOP+LEFT，居中左对齐=CENTER+LEFT，居中对齐=CENTER+CENTER） */
enum class HAlign { LEFT, CENTER }

/** 页面角色：叠加在组合之上，承载特殊页的专属内容行为（自动目录 / 默认致谢语 / 章节序号等）。 */
enum class PageRole { NONE, COVER, TOC, ENDING, SECTION }

/** 底部装饰：波浪 / 直线 / logo / 无。仅对无色块页面生效。 */
enum class BottomDecoration(val key: String, val label: String) {
    NONE("none", "无"),
    WAVE("wave", "波浪"),
    BAR("bar", "直线"),
    LOGO("logo", "logo");

    companion object {
        fun fromKey(k: String) = values().firstOrNull { it.key == k } ?: NONE
    }
}

/** 结构轴中文标签（UI 展示）。 */
val Structure.label: String get() = when (this) {
    Structure.VERTICAL -> "上下"
    Structure.TWO_COL -> "左右"
    Structure.THREE_COL -> "三栏"
    Structure.FOUR_COL -> "四栏"
    Structure.TOP_NARROW -> "上窄下宽"
}
/** 色块轴中文标签（UI 展示）。顺序即用户选择顺序：无 / 全色 / 左色 / 上色 / 下色 / 右色。 */
val ColorBlock.label: String get() = when (this) {
    ColorBlock.NONE -> "无"
    ColorBlock.COVER -> "全"
    ColorBlock.LEFT -> "左"
    ColorBlock.TOP -> "上"
    ColorBlock.BOTTOM -> "下"
    ColorBlock.RIGHT -> "右"
}

/**
 * 一页的组合 = 4 轴 + 色块间距。
 * - [bandGap]：色块与文本框的固定间距，仅对 LEFT/TOP/BOTTOM 生效；COVER/NONE 为 0。
 *   早期为元数据，现已驱动渲染（见 PptLayoutEngine.layoutComposition）：色块决定装饰条几何，
 *   文本框按 bandGap 内缩避让；NONE/COVER 文本框占整页内容区。
 * - [role]：旧 isSpecial 集合（COVER/TOC/SECTION/ENDING）的一一对应，按角色叠加专属内容行为
 *   （封面反色整页 / 目录自动补条目 / 结尾默认致谢语 / 章节左侧满高色条）。role=NONE 时纯按轴组合渲染。
 *
 * 阶段二起：9 个旧版式均可通过 [CompositionResolver.compositionOf] 还原为等价组合，
 * 且任意「结构 × 色块 × 对齐 × 间距」自由组合（共 3×5×3×间距 量级）都可被渲染。
 */
data class SlideComposition(
    val structure: Structure,
    val colorBlock: ColorBlock,
    val valign: VAlign,
    val halign: HAlign,
    val bandGap: Int = 24,
    val role: PageRole = PageRole.NONE,
    val decoration: BottomDecoration = BottomDecoration.NONE
) {
    /** 编码为可持久化的字符串键（轴按固定顺序，便于 UI / 草稿 round-trip）。 */
    val key: String get() = "${structure.name}|${colorBlock.name}|${valign.name}|${halign.name}|$bandGap|${role.name}|${decoration.key}"

    val isSpecial: Boolean get() = role != PageRole.NONE

    /** 是否自身带大色块（整页底色 / 满高色条 / 顶部底部满宽色带等），此时不应叠加底部装饰。 */
    val hasBigBlock: Boolean get() = role != PageRole.NONE || colorBlock != ColorBlock.NONE

    companion object {
        /** 从 [key] 反向解析；非法返回 null（兼容旧草稿 / 未知键）。 */
        fun fromKey(key: String): SlideComposition? {
            val p = key.split("|")
            if (p.size < 6) return null
            val st = Structure.values().firstOrNull { it.name == p[0] } ?: return null
            val cb = ColorBlock.values().firstOrNull { it.name == p[1] } ?: return null
            val va = VAlign.values().firstOrNull { it.name == p[2] } ?: return null
            val ha = HAlign.values().firstOrNull { it.name == p[3] } ?: return null
            val gap = p[4].toIntOrNull() ?: 0
            val role = PageRole.values().firstOrNull { it.name == p[5] } ?: PageRole.NONE
            val deco = if (p.size > 6) BottomDecoration.fromKey(p[6]) else BottomDecoration.NONE
            return SlideComposition(st, cb, va, ha, gap, role, deco)
        }
    }
}

/**
 * 组合解析器：把既有 9 个固定版式映射为「结构 × 色块 × 对齐 × 间距 + 角色」组合。
 * 映射值严格对齐当前母版常量（SECTION_GAP=28 / TOC_TEXT_GAP=24 / 结尾色带高≈40），
 * 因此解析出的组合与原版式行为完全一致，渲染层零改动即可过渡。
 *
 * 注：左右(TWO_COL) 原实现即把左右两栏垂直居中，故映射为 VAlign.CENTER（保证复现一致）；
 * 左右/三栏/上下 默认左对齐。要点(左中) 收敛为纯 (CENTER, LEFT)：整组垂直居中 + 文字左对齐，
 * 与「左中」标签语义一致（旧实现标题左、其余居中的混合态已不顺，此处统一）。
 */
object CompositionResolver {
    fun compositionOf(layout: SlideLayout): SlideComposition = when (layout) {
        SlideLayout.STANDARD  -> SlideComposition(Structure.VERTICAL, ColorBlock.NONE,   VAlign.TOP,    HAlign.LEFT,   0)
        SlideLayout.TWO_COL   -> SlideComposition(Structure.TWO_COL,  ColorBlock.NONE,   VAlign.CENTER, HAlign.LEFT,   0)
        SlideLayout.THREE_COL -> SlideComposition(Structure.THREE_COL, ColorBlock.NONE,  VAlign.TOP,    HAlign.LEFT,   0)
        SlideLayout.LIST      -> SlideComposition(Structure.VERTICAL, ColorBlock.NONE,   VAlign.CENTER, HAlign.LEFT,   0)
        SlideLayout.QUOTE     -> SlideComposition(Structure.VERTICAL, ColorBlock.NONE,   VAlign.CENTER, HAlign.CENTER, 0)
        SlideLayout.COVER     -> SlideComposition(Structure.VERTICAL, ColorBlock.COVER,  VAlign.CENTER, HAlign.CENTER, 0,  PageRole.COVER)
        SlideLayout.TOC       -> SlideComposition(Structure.VERTICAL, ColorBlock.TOP,    VAlign.TOP,    HAlign.LEFT,   24, PageRole.TOC)
        SlideLayout.ENDING    -> SlideComposition(Structure.VERTICAL, ColorBlock.BOTTOM, VAlign.CENTER, HAlign.CENTER, 40, PageRole.ENDING)
        SlideLayout.SECTION   -> SlideComposition(Structure.VERTICAL, ColorBlock.LEFT,   VAlign.CENTER, HAlign.LEFT,   28, PageRole.SECTION)
    }

    /** 4 个特殊页预设（封面 / 目录 / 结尾 / 章节）对应的角色，供 UI 预设按钮使用。 */
    val specialPresets: List<SlideLayout> = SlideLayout.values().filter { it.isSpecial }
    /** 内容页预设（上下 / 左右 / 三栏 / 居中 / 左中）。 */
    val contentPresets: List<SlideLayout> = SlideLayout.values().filter { !it.isSpecial }
}
