/**
 * PPTX 模块数据模型与全局排版常量（由 Android 版 PptModels.kt 移植）。
 *
 * 统一规范（对齐法律/办公场景）：16:9 画布 720×405pt，安全边距上下 30pt、左右 40pt，
 * 标题层级 / 正文 / 引用 / 代码字号固定，正文行距 1.2 倍，列表逐级缩进，全部左对齐。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node 专有 API。
 */
import {
  BlockType,
  TableAlign,
  InlineFragment,
  TextBlock,
  ListBlock,
  ListItemData,
  TableBlock,
  ForcedBreak,
  MdBlock,
  ParseResult,
} from '../mdBlocks';

// 复用共用 Markdown 解析器的块类型
export { BlockType, TableAlign };
export type {
  InlineFragment,
  TextBlock,
  ListBlock,
  ListItemData,
  TableBlock,
  ForcedBreak,
  MdBlock,
  ParseResult,
};

// ────────────────────────────────────────────────
// 全局尺寸常量（pt，行业标准 16:9）
// ────────────────────────────────────────────────

export const PptSpec = {
  CANVAS_W: 720, // 画布宽
  CANVAS_H: 405, // 画布高
  MARGIN_X: 40, // 左右安全边距
  MARGIN_TOP: 30, // 上边距
  MARGIN_BOTTOM: 30, // 下边距
  CONTENT_W: 720 - 40 * 2, // 内容区宽 = 640
  CONTENT_TOP: 30,
  CONTENT_BOTTOM: 405 - 30,
  // 字号（pt）
  FS_H1: 28,
  FS_H2: 24,
  FS_H3: 20,
  FS_H4: 18,
  FS_H5: 16,
  FS_H6: 14,
  FS_BODY: 16,
  FS_QUOTE: 15,
  FS_CODE: 13,
  // 行距倍数
  LINE_MULT: 1.2,
  // 段后距（pt）
  PARA_GAP: 8,
  HEAD_GAP: 12, // 标题后额外距
  // 列表缩进（每层级 pt）
  LIST_INDENT: 18,
  // 引用左缩进
  QUOTE_INDENT: 24,
  // 代码块内边距
  CODE_PAD: 8,
  // 表格单元格内边距（pt，四边）
  TABLE_PAD: 6,
  // 左右双栏布局
  SPLIT_GAP: 24, // 两栏间距
} as const;

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
export enum SlideLayout {
  COVER = 'cover', // 全色：整页满屏主色块，大标题居中，作首页
  TOC = 'toc', // 上色：顶部满宽主色带 + 章节目录列表
  ENDING = 'ending', // 下色：底部满宽主色带 + 致谢结束页
  STANDARD = 'standard', // 上下：标题 + 正文纵向排列（默认内容模板）
  TWO_COL = 'two_col', // 左右：标题 + 内容左右分栏
  THREE_COL = 'three_col', // 三栏：内容三等分
  SECTION = 'section', // 左色：左侧满高主色条 + 标题，分隔过渡页
  QUOTE = 'quote', // 居中：引用突出展示，整体水平垂直居中
  LIST = 'list', // 左中：左侧强调竖条 + 要点列表
}

export interface SlideLayoutMeta {
  key: string;
  label: string;
  desc: string;
  isSpecial: boolean;
}

export const SLIDE_LAYOUT_META: Record<SlideLayout, SlideLayoutMeta> = {
  [SlideLayout.COVER]: { key: 'cover', label: '全色', desc: '整页满屏主色，作首页', isSpecial: true },
  [SlideLayout.TOC]: { key: 'toc', label: '上色', desc: '顶部色带+目录列表', isSpecial: true },
  [SlideLayout.ENDING]: { key: 'ending', label: '下色', desc: '底部色带+致谢页', isSpecial: true },
  [SlideLayout.STANDARD]: { key: 'standard', label: '上下', desc: '标题+正文，纵向排列', isSpecial: false },
  [SlideLayout.TWO_COL]: { key: 'two_col', label: '左右', desc: '标题+内容，左右分栏', isSpecial: false },
  [SlideLayout.THREE_COL]: { key: 'three_col', label: '三栏', desc: '内容三等分', isSpecial: false },
  [SlideLayout.SECTION]: { key: 'section', label: '左色', desc: '左色条分隔过渡页', isSpecial: true },
  [SlideLayout.QUOTE]: { key: 'quote', label: '居中', desc: '引用居中突出展示', isSpecial: false },
  [SlideLayout.LIST]: { key: 'list', label: '左中', desc: '左竖条+要点列表', isSpecial: false },
};

/** 全部版式（声明顺序，含特殊页）。 */
export const ALL_SLIDE_LAYOUTS: SlideLayout[] = [
  SlideLayout.COVER,
  SlideLayout.TOC,
  SlideLayout.ENDING,
  SlideLayout.STANDARD,
  SlideLayout.TWO_COL,
  SlideLayout.THREE_COL,
  SlideLayout.SECTION,
  SlideLayout.QUOTE,
  SlideLayout.LIST,
];

/** 常用内容版式（排除特殊页，用于快速切换面板）。 */
export const CONTENT_SLIDE_LAYOUTS: SlideLayout[] = [
  SlideLayout.STANDARD,
  SlideLayout.TWO_COL,
  SlideLayout.THREE_COL,
  SlideLayout.QUOTE,
  SlideLayout.LIST,
];

/** 是否为特殊页面版式（封面/目录/章节/结尾通常不需要随"套用全部页"变更）。 */
export function slideLayoutIsSpecial(layout: SlideLayout): boolean {
  return SLIDE_LAYOUT_META[layout].isSpecial;
}

// ────────────────────────────────────────────────
// 行内片段
// ────────────────────────────────────────────────

/** 空片段列表工具（与 Kotlin fragmentsOf 等价）。 */
export function fragmentsOf(text: string): InlineFragment[] {
  return [{ text, bold: false, italic: false, strike: false, link: null, code: false }];
}

// ────────────────────────────────────────────────
// 分页结果与单页
// ────────────────────────────────────────────────

/** 一页幻灯片。title 取该页首个标题文本，用于预览提示；isCover 表示封面页（居中）。 */
export interface SlidePage {
  blocks: MdBlock[];
  title: string;
  isCover: boolean;
}

/** 分页结果。overflowPages 为触发"单块超长"警告的页码集合（1-based）。 */
export interface PaginationResult {
  pages: SlidePage[];
  overflowPages: Set<number>;
}

// ────────────────────────────────────────────────
// 主题
// ────────────────────────────────────────────────

/** 全局统一主题：配色 + 封面背景。字体/字号由 PptSpec 全局锁定。 */
export interface PptTheme {
  id: string;
  name: string;
  bg: string; // 幻灯片背景（hex RRGGBB）
  titleColor: string; // 标题文字
  bodyColor: string; // 正文文字
  accent: string; // 强调（标题左侧竖条、链接）
  codeBg: string; // 代码块背景
  quoteBg: string; // 引用块浅色圆角背景底色
  coverBg: string; // 封面背景
}

// ────────────────────────────────────────────────
// 装饰几何（坐标单位 pt，颜色由渲染层取主题）
// ────────────────────────────────────────────────

/** 单页装饰矩形。 */
export class Rect {
  constructor(
    public x: number = 0,
    public y: number = 0,
    public w: number = 0,
    public h: number = 0
  ) {}
}

/**
 * 单页装饰：accentBg=章节页强调背景（文字转白）；bars=装饰矩形（标题竖条/封面色条/强调线等）。
 * barColor=色块着色（默认用主题强调色，章节页应传封面主色调以保持统一）。
 * wave=底部波浪装饰开关。waveColor=波浪主色（null 用主题 accent）。
 * bottomBar=底部直线色块装饰开关（与波浪并列、可独立开关）。bottomBarH=直线色块高度（pt）。
 * logo=右下角 logo 开关。颜色固定跟随主题主色调。
 */
export class SlideDeco {
  accentBg: boolean;
  bars: Rect[];
  barColor: string | null;
  quoteBg: Rect[];
  wave: boolean;
  waveColor: string | null;
  bottomBar: boolean;
  bottomBarH: number;
  logo: boolean;

  constructor(init?: Partial<SlideDeco>) {
    this.accentBg = false;
    this.bars = [];
    this.barColor = null;
    this.quoteBg = [];
    this.wave = false;
    this.waveColor = null;
    this.bottomBar = false;
    this.bottomBarH = 0;
    this.logo = false;
    if (init) Object.assign(this, init);
  }

  /** 兼容旧调用：取首个装饰矩形（标题竖条）。 */
  get bar(): Rect | null {
    return this.bars.length > 0 ? this.bars[0] : null;
  }

  copy(props: Partial<SlideDeco>): SlideDeco {
    const c = new SlideDeco();
    c.accentBg = props.accentBg ?? this.accentBg;
    c.bars = props.bars ? props.bars.slice() : this.bars.slice();
    c.barColor = props.barColor !== undefined ? props.barColor : this.barColor;
    c.quoteBg = props.quoteBg ? props.quoteBg.slice() : this.quoteBg.slice();
    c.wave = props.wave ?? this.wave;
    c.waveColor = props.waveColor !== undefined ? props.waveColor : this.waveColor;
    c.bottomBar = props.bottomBar ?? this.bottomBar;
    c.bottomBarH = props.bottomBarH ?? this.bottomBarH;
    c.logo = props.logo ?? this.logo;
    return c;
  }
}

/** 波浪装饰的单层数据：用于预览（Path）和导出（OOXML custGeom）同源消费。 */
export interface WaveLayer {
  /** 该层波浪的贝塞尔控制点列表（相对坐标 0~1）。每 6 个值为一组 cubicBezier，末尾 4 值为闭合矩形。 */
  controlPoints: number[];
  /** 填充颜色（hex RRGGBB）。 */
  color: string;
  /** 透明度（0~1），导出时转为 srgbClr 的透明度。 */
  alpha: number;
}

/** 页脚（页码）。 */
export interface SlideFooter {
  text: string;
}

// ────────────────────────────────────────────────
// 组合式页面模型（结构 × 色块 × 对齐 × 间距）
// ────────────────────────────────────────────────

/** 结构轴：上下 / 左右 / 三栏 / 四栏 / 上窄下宽 */
export enum Structure {
  VERTICAL = 'VERTICAL',
  TWO_COL = 'TWO_COL',
  THREE_COL = 'THREE_COL',
  FOUR_COL = 'FOUR_COL',
  TOP_NARROW = 'TOP_NARROW',
}

/** 色块轴：无 / 全色 / 左色 / 上色 / 下色 / 右色 */
export enum ColorBlock {
  NONE = 'NONE',
  COVER = 'COVER',
  LEFT = 'LEFT',
  TOP = 'TOP',
  BOTTOM = 'BOTTOM',
  RIGHT = 'RIGHT',
}

/** 竖直对齐：上 / 居中 */
export enum VAlign {
  TOP = 'TOP',
  CENTER = 'CENTER',
}

/** 水平对齐：左 / 居中 */
export enum HAlign {
  LEFT = 'LEFT',
  CENTER = 'CENTER',
}

/** 页面角色：叠加在组合之上，承载特殊页的专属内容行为。 */
export enum PageRole {
  NONE = 'NONE',
  COVER = 'COVER',
  TOC = 'TOC',
  ENDING = 'ENDING',
  SECTION = 'SECTION',
}

/** 底部装饰：波浪 / 直线 / logo / 无。仅对无色块页面生效。 */
export enum BottomDecoration {
  NONE = 'none',
  WAVE = 'wave',
  BAR = 'bar',
  LOGO = 'logo',
}

/** 结构轴中文标签（UI 展示）。 */
export function structureLabel(s: Structure): string {
  switch (s) {
    case Structure.VERTICAL: return '上下';
    case Structure.TWO_COL: return '左右';
    case Structure.THREE_COL: return '三栏';
    case Structure.FOUR_COL: return '四栏';
    case Structure.TOP_NARROW: return '上窄下宽';
  }
}

/** 色块轴中文标签（UI 展示）。 */
export function colorBlockLabel(c: ColorBlock): string {
  switch (c) {
    case ColorBlock.NONE: return '无';
    case ColorBlock.COVER: return '全';
    case ColorBlock.LEFT: return '左';
    case ColorBlock.TOP: return '上';
    case ColorBlock.BOTTOM: return '下';
    case ColorBlock.RIGHT: return '右';
  }
}

/** 底部装饰中文标签。 */
export function bottomDecorationLabel(d: BottomDecoration): string {
  switch (d) {
    case BottomDecoration.NONE: return '无';
    case BottomDecoration.WAVE: return '波浪';
    case BottomDecoration.BAR: return '直线';
    case BottomDecoration.LOGO: return 'logo';
  }
}

/** 从持久化 key 解析底部装饰；非法返回 NONE。 */
export function bottomDecorationFromKey(k: string): BottomDecoration {
  switch (k) {
    case 'wave': return BottomDecoration.WAVE;
    case 'bar': return BottomDecoration.BAR;
    case 'logo': return BottomDecoration.LOGO;
    default: return BottomDecoration.NONE;
  }
}

const STRUCTURES: Structure[] = [
  Structure.VERTICAL,
  Structure.TWO_COL,
  Structure.THREE_COL,
  Structure.FOUR_COL,
  Structure.TOP_NARROW,
];
const COLOR_BLOCKS: ColorBlock[] = [
  ColorBlock.NONE,
  ColorBlock.COVER,
  ColorBlock.LEFT,
  ColorBlock.TOP,
  ColorBlock.BOTTOM,
  ColorBlock.RIGHT,
];
const VALIGNS: VAlign[] = [VAlign.TOP, VAlign.CENTER];
const HALIGNS: HAlign[] = [HAlign.LEFT, HAlign.CENTER];
const PAGE_ROLES: PageRole[] = [
  PageRole.NONE,
  PageRole.COVER,
  PageRole.TOC,
  PageRole.ENDING,
  PageRole.SECTION,
];

/**
 * 一页的组合 = 4 轴 + 色块间距。
 * - bandGap：色块与文本框的固定间距，仅对 LEFT/TOP/BOTTOM 生效；COVER/NONE 为 0。
 * - role：封面反色整页 / 目录自动补条目 / 结尾默认致谢语 / 章节左侧满高色条。
 * - decoration：底部装饰（波浪/直线/logo），仅对无色块页面生效。
 * - colRatio：多栏主栏宽度占比（15~85），null = 智能按内容高度配比。
 */
export class SlideComposition {
  constructor(
    public structure: Structure,
    public colorBlock: ColorBlock,
    public valign: VAlign,
    public halign: HAlign,
    public bandGap: number = 24,
    public role: PageRole = PageRole.NONE,
    public decoration: BottomDecoration = BottomDecoration.NONE,
    public colRatio: number | null = null
  ) {}

  /** 编码为可持久化的字符串键。 */
  get key(): string {
    return `${this.structure}|${this.colorBlock}|${this.valign}|${this.halign}|${this.bandGap}|${this.role}|${this.decoration}|${this.colRatio ?? -1}`;
  }

  get isSpecial(): boolean {
    return this.role !== PageRole.NONE;
  }

  /** 是否自身带大色块（整页底色 / 满高色条 / 顶部底部满宽色带等），此时不应叠加底部装饰。 */
  get hasBigBlock(): boolean {
    return this.role !== PageRole.NONE || this.colorBlock !== ColorBlock.NONE;
  }

  /** 从 [key] 反向解析；非法返回 null。 */
  static fromKey(key: string): SlideComposition | null {
    const p = key.split('|');
    if (p.length < 6) return null;
    const st = STRUCTURES.find((s) => s === p[0]);
    if (st === undefined) return null;
    const cb = COLOR_BLOCKS.find((c) => c === p[1]);
    if (cb === undefined) return null;
    const va = VALIGNS.find((v) => v === p[2]);
    if (va === undefined) return null;
    const ha = HALIGNS.find((h) => h === p[3]);
    if (ha === undefined) return null;
    const gap = parseInt(p[4], 10);
    const gapVal = isNaN(gap) ? 0 : gap;
    const role = PAGE_ROLES.find((r) => r === p[5]) ?? PageRole.NONE;
    const deco = p.length > 6 ? bottomDecorationFromKey(p[6]) : BottomDecoration.NONE;
    let cr: number | null = null;
    if (p.length > 7) {
      const v = parseInt(p[7], 10);
      if (!isNaN(v) && v >= 15 && v <= 85 && v !== -1) cr = v;
    }
    return new SlideComposition(st, cb, va, ha, gapVal, role, deco, cr);
  }
}

/**
 * 组合解析器：把既有 9 个固定版式映射为「结构 × 色块 × 对齐 × 间距 + 角色」组合。
 * 映射值严格对齐当前母版常量（SECTION_GAP=28 / TOC_TEXT_GAP=24 / 结尾色带高≈40）。
 */
export const CompositionResolver = {
  compositionOf(layout: SlideLayout): SlideComposition {
    switch (layout) {
      case SlideLayout.STANDARD:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.NONE, VAlign.TOP, HAlign.LEFT, 0);
      case SlideLayout.TWO_COL:
        return new SlideComposition(Structure.TWO_COL, ColorBlock.NONE, VAlign.CENTER, HAlign.LEFT, 0);
      case SlideLayout.THREE_COL:
        return new SlideComposition(Structure.THREE_COL, ColorBlock.NONE, VAlign.TOP, HAlign.LEFT, 0);
      case SlideLayout.LIST:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.NONE, VAlign.CENTER, HAlign.LEFT, 0);
      case SlideLayout.QUOTE:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.NONE, VAlign.CENTER, HAlign.CENTER, 0);
      case SlideLayout.COVER:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.COVER, VAlign.CENTER, HAlign.CENTER, 0, PageRole.COVER);
      case SlideLayout.TOC:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.TOP, VAlign.TOP, HAlign.LEFT, 24, PageRole.TOC);
      case SlideLayout.ENDING:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.BOTTOM, VAlign.CENTER, HAlign.CENTER, 40, PageRole.ENDING);
      case SlideLayout.SECTION:
        return new SlideComposition(Structure.VERTICAL, ColorBlock.LEFT, VAlign.CENTER, HAlign.LEFT, 28, PageRole.SECTION);
    }
  },

  /** 4 个特殊页预设（封面 / 目录 / 结尾 / 章节）。 */
  specialPresets: [
    SlideLayout.COVER,
    SlideLayout.TOC,
    SlideLayout.ENDING,
    SlideLayout.SECTION,
  ],

  /** 内容页预设（上下 / 左右 / 三栏 / 居中 / 左中）。 */
  contentPresets: [
    SlideLayout.STANDARD,
    SlideLayout.TWO_COL,
    SlideLayout.THREE_COL,
    SlideLayout.QUOTE,
    SlideLayout.LIST,
  ],
};
