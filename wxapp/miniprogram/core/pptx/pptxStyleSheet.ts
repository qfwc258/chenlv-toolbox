/**
 * PPTX 可编辑样式表（对标公众号「自定义 CSS 样式」）。
 * 由 Android 版 PptStyleSheet.kt 移植。
 *
 * 把原本散落在 PptSpec / PptThemes / PptExportEngine 里的硬编码排版常量，
 * 统一收敛为一个类。配合 pptxCssParser，即可用一段 CSS 文本随时覆盖任意字段。
 *
 * 默认值 = 当前线上版本（v1.7.x）的硬编码配置，保证「不填 CSS = 原样」。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node 专有 API。
 */
import { PptTheme } from './pptxModels';

/** 可被 CSS 覆盖的全部标量字段名（用于 copy 时保留当前值）。 */
const SCALAR_KEYS = [
  'canvasW', 'canvasH', 'marginX', 'marginTop', 'marginBottom', 'contentBottomOverride',
  'fsH1', 'fsH2', 'fsH3', 'fsH4', 'fsH5', 'fsH6', 'fsBody', 'fsQuote', 'fsCode',
  'lineMult', 'paraGap', 'headGap', 'quoteGapBefore',
  'bg', 'titleColor', 'bodyColor', 'accent', 'codeBg', 'quoteBg', 'coverBg',
  'latinFont', 'bodyFont', 'titleFont', 'codeFont',
  'listIndent', 'quoteIndent', 'codePad', 'tablePad', 'splitGap',
] as const;

export class PptStyleSheet {
  // ── 画布与边距（pt）──
  canvasW = 720;
  canvasH = 405;
  marginX = 40;
  marginTop = 30;
  marginBottom = 30;
  /**
   * 内容区下边界覆盖值（pt）。>=0 时强制使用此值（代替 canvasH−marginBottom），
   * 用于「开启波浪装饰」时把文字框整体上移，避开底部波浪、保持与页底的安全边距。
   * 默认 −1 表示不覆盖（使用 canvasH−marginBottom）。
   */
  contentBottomOverride = -1;

  // ── 字号（pt）──
  fsH1 = 28;
  fsH2 = 24;
  fsH3 = 20;
  fsH4 = 18;
  fsH5 = 16;
  fsH6 = 14;
  fsBody = 16;
  fsQuote = 15;
  fsCode = 13;

  // ── 行距倍数 ──
  lineMult = 1.2;

  // ── 段距（pt）──
  paraGap = 8;
  headGap = 12;
  /** 引用块（md >）整体段前距：与上方文本保持适度间距（布局层统一施加，预览/导出一致）。 */
  quoteGapBefore = 18;

  // ── 颜色（hex RRGGBB，不带 #）──
  bg = 'FFFFFF';
  titleColor = '9E2A2B';
  bodyColor = '333333';
  accent = 'C0392B';
  codeBg = 'F7ECEC';
  quoteBg = 'F7ECEC';
  coverBg = '9E2A2B';

  // ── 字体（OOXML typeface 名）──
  // 西文字体（<a:latin>/<a:cs>）：默认 Arial（无衬线）。与东亚字体分开，
  // 避免把 CJK 字体名写入拉丁槽导致英文/数字回落到衬线默认字体。
  latinFont = 'Arial';
  // 东亚（中文）字体（<a:ea>）：默认微软雅黑（无衬线）。
  bodyFont = '微软雅黑';
  titleFont = '微软雅黑';
  // 代码块东亚字体（拉丁槽固定 Consolas 等宽）。
  codeFont = 'Consolas';

  // ── 其他排版（pt）──
  listIndent = 18;
  quoteIndent = 24;
  codePad = 8;
  tablePad = 6;
  splitGap = 24;

  /** 被 CSS 显式覆盖的字段名集合（用于「仅显式声明的颜色才覆盖主题」）。 */
  overrides: Set<string> = new Set();

  constructor(init?: Partial<PptStyleSheet>) {
    if (init) this.copy(init as PptStyleSheet);
  }

  /** 内容区宽 = 画布宽 − 左右边距×2（pt）。 */
  get contentW(): number {
    return this.canvasW - this.marginX * 2;
  }

  /** 内容区上边界（pt）。 */
  get contentTop(): number {
    return this.marginTop;
  }

  /** 内容区下边界（pt）。若设置了 contentBottomOverride 则优先使用，否则 canvasH−marginBottom。 */
  get contentBottom(): number {
    return this.contentBottomOverride >= 0 ? this.contentBottomOverride : this.canvasH - this.marginBottom;
  }

  /** 数据类 copy：保留当前所有字段值，仅覆盖传入字段。 */
  copy(props: Partial<PptStyleSheet>): PptStyleSheet {
    const c = new PptStyleSheet();
    for (const k of SCALAR_KEYS) {
      (c as unknown as Record<string, unknown>)[k] = (this as unknown as Record<string, unknown>)[k];
    }
    c.overrides = new Set(this.overrides);
    const p = props as Record<string, unknown>;
    for (const k of Object.keys(p)) {
      if (k === 'overrides') {
        c.overrides = new Set(p[k] as Iterable<string>);
      } else if ((SCALAR_KEYS as readonly string[]).includes(k)) {
        (c as unknown as Record<string, unknown>)[k] = p[k];
      }
    }
    return c;
  }

  /** 转为 PptTheme（供现有渲染/导出链路的配色部分复用）。 */
  toTheme(id = 'css', name = '自定义样式'): PptTheme {
    return {
      id,
      name,
      bg: this.bg,
      titleColor: this.titleColor,
      bodyColor: this.bodyColor,
      accent: this.accent,
      codeBg: this.codeBg,
      quoteBg: this.quoteBg,
      coverBg: this.coverBg,
    };
  }
}

/** 默认样式表（等同「不填 CSS」）。 */
export function defaultStyle(): PptStyleSheet {
  return new PptStyleSheet();
}
