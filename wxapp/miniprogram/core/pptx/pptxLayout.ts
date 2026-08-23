/**
 * PPTX 流式防溢出布局引擎（由 Android 版 PptLayoutEngine.kt 的纯算法部分移植）。
 *
 * 职责：
 * 1. 估算任意块的高度（供自动分页引擎做"剩余高度是否放得下"判断，及本引擎做 Y 坐标排布）；
 * 2. 把分页结果排布成每页的渲染单元（含坐标/字号/对齐），预览与导出共用，保证 1:1。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node/Android API。
 * 与 Kotlin 版不同：style 由调用方注入（构造函数 / 顶层函数参数），而非全局可变状态。
 * 所有内容严格约束在安全矩形（左 40 / 右 680 / 上 30 / 下 contentBottom）内，触底即视为溢出单块。
 */
import { BlockType, TableAlign, InlineFragment } from '../mdBlocks';
import type {
  TextBlock,
  ListBlock,
  TableBlock,
  MdBlock,
  ListItemData,
} from '../mdBlocks';
import {
  Rect,
  SlideDeco,
  SlideComposition,
  CompositionResolver,
  SlideLayout,
  Structure,
  ColorBlock,
  VAlign,
  HAlign,
  PageRole,
  BottomDecoration,
  fragmentsOf,
} from './pptxModels';
import type { PptTheme, WaveLayer, PaginationResult } from './pptxModels';
import { PptStyleSheet } from './pptxStyleSheet';

// ────────────────────────────────────────────────
// 对齐
// ────────────────────────────────────────────────

export enum Align {
  LEFT = 'LEFT',
  CENTER = 'CENTER',
  RIGHT = 'RIGHT',
}

// ────────────────────────────────────────────────
// 渲染单元 / 表格 / 单页
// ────────────────────────────────────────────────

/** 单页中的一个渲染单元（预览与导出都消费它）。坐标单位 pt。 */
export interface LaidOutUnit {
  type: BlockType;
  x: number;
  y: number;
  w: number;
  h: number;
  fontSize: number;
  align: Align;
  fragments: InlineFragment[];
  listItems: ListItemData[];
  ordered: boolean;
  /** 有序列表起始编号偏移量（0-based）。0 表示从 1 开始；拆分后的续接列表应设为原始索引以保持编号连续。 */
  listStart: number;
  overflow: boolean;
  table: TableRender | null;
  bold: boolean;
  /** 文字颜色覆盖（hex RRGGBB）。非 null 时强制使用该色（如目录标题反白），忽略主题色。 */
  color: string | null;
  /** 东亚（中文）字体（OOXML <a:ea> typeface 名），由 CSS 的 font-family 决定；空串表示使用导出端默认映射。 */
  fontFamily: string;
  /** 西文字体（OOXML <a:latin>/<a:cs> typeface 名）；空串表示使用导出端默认映射（Arial）。 */
  latinFont: string;
  /** 段后距（pt），导出时写入 <a:spcAft>；0 表示不加。 */
  gapAfter: number;
}

/** 表格渲染数据（引擎已算好列宽/行高/对齐，预览与导出同源消费）。 */
export interface TableRender {
  header: InlineFragment[][];
  rows: InlineFragment[][][];
  colW: number[];
  colAlign: TableAlign[];
  headerH: number;
  rowHs: number[];
  cellFs: number;
  headerFs: number;
  /** 表格总高（含表头），= headerH + rowHs 之和。 */
  totalH: number;
}

/** 一页的布局结果。 */
export interface LaidOutSlide {
  units: LaidOutUnit[];
  cover: boolean;
  layout: SlideLayout;
  deco: SlideDeco | null;
  footer: SlideFooter | null;
  /** 该页的组合描述（不影响渲染；预览/导出可按需读取）。 */
  composition: SlideComposition | null;
}

import type { SlideFooter } from './pptxModels';

/** 波浪装饰参数（默认 = 出厂效果）。 */
export interface PptWaveParams {
  /** 波浪整体高度倍率（等比缩放三层波高 38/28/19），1.0=出厂 */
  heightScale: number;
  /** 波浪整体透明度倍率（等比缩放三层 alpha 0.45/0.70/0.95），1.0=出厂 */
  opacityScale: number;
  /** 层次对比强度（1.0=出厂） */
  contrast: number;
}

/** 默认波浪参数（v1.7.9 出厂效果）。 */
export const DEFAULT_WAVE_PARAMS: PptWaveParams = { heightScale: 1.0, opacityScale: 1.0, contrast: 1.0 };

/** hex → 两位大写十六进制。 */
function hex2(n: number): string {
  return Math.min(255, Math.max(0, Math.round(n))).toString(16).padStart(2, '0').toUpperCase();
}

/**
 * 波浪装饰配色纯函数：基于主色调派生三种波浪颜色。
 * 返回 [浅色(最下层/最大面积), 深色(中间层), 主色(顶层/最小面积)]。
 */
export function deriveWaveColors(hex: string, contrast: number = 1.0): string[] {
  const r = parseInt(hex.substring(0, 2), 16);
  const g = parseInt(hex.substring(2, 4), 16);
  const b = parseInt(hex.substring(4, 6), 16);
  // 顶层（最小面积 / 前层）：直接使用主色调
  const main = [r, g, b];
  // 中间层（中等面积）：压暗（出厂 ×0.70），按对比倍率缩放
  const darkMult = Math.min(1, Math.max(0, 1 - 0.3 * contrast));
  const d = [r, g, b].map((c) => Math.round(c * darkMult));
  // 最下层（最大面积 / 后层）：大幅提亮
  const lf = [0.78, 0.78, 0.76].map((v) => Math.min(1, Math.max(0, v * contrast)));
  const l = [r, g, b].map((c, i) => Math.round(c + (255 - c) * lf[i]));
  return [
    hex2(l[0]) + hex2(l[1]) + hex2(l[2]),
    hex2(d[0]) + hex2(d[1]) + hex2(d[2]),
    hex2(main[0]) + hex2(main[1]) + hex2(main[2]),
  ];
}

// ────────────────────────────────────────────────
// 布局引擎（style 由构造函数注入）
// ────────────────────────────────────────────────

/** Logo 装饰默认参数（模块级常量，供导出端等无实例场景读取，与引擎实例默认值一致）。 */
export const DEFAULT_LOGO_SCALE = 0.2; // 占画布宽比例，默认 20%
export const DEFAULT_LOGO_HALIGN = 'right'; // "left" / "right"
export const DEFAULT_LOGO_VALIGN = 'bottom'; // "top" / "bottom"

export class PptLayoutEngine {
  style: PptStyleSheet;
  waveParams: PptWaveParams;
  /** Logo 装饰参数（由 UI 注入；此处保留默认值，导出端消费）。 */
  logoScale = DEFAULT_LOGO_SCALE; // 占画布宽比例，默认 20%
  logoHAlign = DEFAULT_LOGO_HALIGN; // "left" / "right"
  logoVAlign = DEFAULT_LOGO_VALIGN; // "top" / "bottom"

  constructor(style: PptStyleSheet, waveParams: PptWaveParams = DEFAULT_WAVE_PARAMS) {
    this.style = style;
    this.waveParams = waveParams;
  }

  /** 整页内容可用高度。 */
  pageContentH(): number {
    return this.style.contentBottom - this.style.contentTop;
  }

  // ────────────────────────────────────────────────
  // 文本宽度 / 行数估算
  // ────────────────────────────────────────────────

  charWidthPt(c: string, fontSize: number): number {
    const code = c.codePointAt(0) ?? 0;
    const cjk =
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0xff00 && code <= 0xffef) ||
      (code >= 0x3400 && code <= 0x4dbf);
    return fontSize * (cjk ? 1.0 : 0.55);
  }

  /** 一段文本的像素宽度（pt）。 */
  textWidthPt(text: string, fontSize: number): number {
    let sum = 0;
    for (const c of text) sum += this.charWidthPt(c, fontSize);
    return sum;
  }

  /** 按可用宽度贪婪折行，返回行数。 */
  lineCount(text: string, fontSize: number, availWidth: number): number {
    if (text.length === 0) return 1;
    let lines = 1;
    let cur = 0;
    for (const c of text) {
      cur += this.charWidthPt(c, fontSize);
      if (cur > availWidth) {
        lines++;
        cur = this.charWidthPt(c, fontSize);
      }
    }
    return lines;
  }

  // ────────────────────────────────────────────────
  // 块高度估算
  // ────────────────────────────────────────────────

  fontSizeOf(type: BlockType): number {
    switch (type) {
      case BlockType.H1: return this.style.fsH1;
      case BlockType.H2: return this.style.fsH2;
      case BlockType.H3: return this.style.fsH3;
      case BlockType.H4: return this.style.fsH4;
      case BlockType.H5: return this.style.fsH5;
      case BlockType.H6: return this.style.fsH6;
      case BlockType.QUOTE: return this.style.fsQuote;
      case BlockType.CODE: return this.style.fsCode;
      default: return this.style.fsBody;
    }
  }

  gapOf(type: BlockType): number {
    if (type >= BlockType.H1 && type <= BlockType.H6) return this.style.headGap;
    return this.style.paraGap;
  }

  /** 多行文本片段拼接后的渲染高度（不含段后距）。 */
  textContentHeight(fragments: InlineFragment[], fontSize: number, width: number): number {
    const full = fragments.map((f) => f.text).join('');
    const paras = full.split('\n');
    const lines = paras.reduce((acc, p) => acc + this.lineCount(p, fontSize, width), 0);
    // 行高乘 1.04 安全缓冲：补偿 PowerPoint 实际字形度量略超出 lineHeight 设定值
    return Math.max(1, lines) * Math.trunc(fontSize * this.style.lineMult * 1.04);
  }

  /** 单块"内容渲染高"（不含段后距）。fontSize/width 缺省用块默认字号与整页内容宽。 */
  contentHeight(block: MdBlock, fontSize?: number, width?: number): number {
    const fs = fontSize ?? this.fontSizeOf(this.blockTypeOf(block));
    const w = width ?? this.style.contentW;
    if (block.kind === 'text') {
      if (block.type === BlockType.CODE) {
        const lines = Math.max(1, block.raw.split('\n').length);
        const innerW = w - this.style.codePad * 2;
        const wrapped = block.raw.split('\n').reduce((acc, line) => acc + this.lineCount(line, fs, innerW), 0);
        return Math.max(lines, wrapped) * Math.trunc(fs * this.style.lineMult * 1.04) + this.style.codePad * 2;
      }
      return this.textContentHeight(block.fragments, fs, w);
    }
    if (block.kind === 'list') {
      const innerW = w - this.style.listIndent;
      const prefixW = fs * 0.55 * 3; // 前缀近似宽度（有序 "1. " / 无序 "•  "）
      const effectiveW = Math.max(Math.round(innerW - prefixW), Math.round(w * 0.3));
      let sum = 0;
      for (const item of block.items) {
        const text = item.fragments.map((f) => f.text).join('');
        // 嵌套项缩进越深，可用宽度越小（每层缩进约 2em）
        const indentDeduction = Math.min(item.indent * fs * 2, Math.floor(effectiveW / 2));
        const itemW = Math.max(effectiveW - indentDeduction, Math.round(w * 0.2));
        sum += this.lineCount(text, fs, itemW) * Math.trunc(fs * this.style.lineMult * 1.04);
      }
      return sum + block.items.length * 2;
    }
    if (block.kind === 'table') {
      return this.buildTableRender(block, w).totalH;
    }
    return 14; // ForcedBreak
  }

  /** 单块"总高"（含段后距），供分页与 Y 轴步进。 */
  blockHeight(block: MdBlock): number {
    if (block.kind === 'break') return 14;
    return this.contentHeight(block) + this.gapOf(this.blockTypeOf(block));
  }

  blockTypeOf(block: MdBlock): BlockType {
    if (block.kind === 'text') return block.type;
    if (block.kind === 'list') return block.type;
    if (block.kind === 'table') return BlockType.TABLE;
    return BlockType.DIVIDER;
  }

  /** 块类型 → 东亚字体（OOXML <a:ea> typeface 名），由 CSS 的 font-family 决定。 */
  fontForType(t: BlockType): string {
    if (t >= BlockType.H1 && t <= BlockType.H6) return this.style.titleFont;
    if (t === BlockType.CODE) return this.style.codeFont;
    return this.style.bodyFont;
  }

  /** 块类型 → 西文字体（OOXML <a:latin>/<a:cs> typeface 名）；代码固定 Consolas 等宽。 */
  latinForType(t: BlockType): string {
    return t === BlockType.CODE ? 'Consolas' : this.style.latinFont;
  }

  /** 块类型 → 段后距（pt），由 CSS 的 margin-bottom 决定。 */
  gapForType(t: BlockType): number {
    if (t >= BlockType.H1 && t <= BlockType.H6) return this.style.headGap;
    return this.style.paraGap;
  }

  // ────────────────────────────────────────────────
  // 超长块拆分
  // ────────────────────────────────────────────────

  /**
   * 超长代码块（```）按物理行（\n）拆分为多个子块，每页一个，自动分页不截断。
   * 用 0.85 安全系数预留余量，避免子块"差一点点"溢出导致底部裁剪。
   */
  splitLongCode(block: MdBlock, availH?: number): MdBlock[] {
    if (!(block.kind === 'text' && block.type === BlockType.CODE)) return [block];
    const avail = availH ?? this.pageContentH();
    if (this.contentHeight(block) <= avail) return [block];
    const fs = this.style.fsCode;
    const lineH = Math.trunc(fs * this.style.lineMult);
    const innerW = this.style.contentW - this.style.codePad * 2;
    const usable = Math.max(Math.trunc((avail - this.style.codePad * 2) * 0.85), lineH * 2);
    const lines = block.raw.split('\n');
    const chunks: string[] = [];
    let buf: string[] = [];
    let bufH = 0;
    for (const line of lines) {
      const wrapped = Math.max(1, this.lineCount(line, fs, innerW));
      const lh = wrapped * lineH;
      if (buf.length > 0 && bufH + lh > usable) {
        chunks.push(buf.join('\n'));
        buf = [];
        bufH = 0;
      }
      buf.push(line);
      bufH += lh;
    }
    if (buf.length > 0) chunks.push(buf.join('\n'));
    return chunks.map((raw) => ({ kind: 'text', type: BlockType.CODE, fragments: [], raw }));
  }

  /**
   * 超长表格按数据行拆分为多个子表（每页一个），自动分页不截断，每页保留表头。
   */
  splitLongTable(block: TableBlock, availH?: number): TableBlock[] {
    const avail = availH ?? this.pageContentH();
    if (block.rows.length === 0) return [block];
    const render = this.buildTableRender(block, this.style.contentW);
    if (render.totalH <= avail) return [block];
    const usableH = avail - render.headerH;
    if (usableH <= 0) return [block]; // 表头本身就超页，不拆
    const subTables: TableBlock[] = [];
    let startIdx = 0;
    while (startIdx < block.rows.length) {
      let h = 0;
      let endIdx = startIdx;
      while (endIdx < block.rows.length) {
        const rowH = render.rowHs[endIdx];
        if (h > 0 && h + rowH > usableH) break;
        h += rowH;
        endIdx++;
      }
      if (endIdx === startIdx) endIdx++; // 单行也超限，强制至少放一行
      subTables.push({
        kind: 'table',
        header: block.header,
        rows: block.rows.slice(startIdx, endIdx),
        colAlign: block.colAlign,
      });
      startIdx = endIdx;
    }
    return subTables.length > 0 ? subTables : [block];
  }

  // ────────────────────────────────────────────────
  // 波浪装饰（几何输出，供导出端 custGeom 消费）
  // ────────────────────────────────────────────────

  /** 波浪装饰在页面底部占用的垂直高度（pt）。 */
  waveClearance(): number {
    const maxWaveH = 38 * this.waveParams.heightScale; // 底层波最高（出厂 38pt）
    return Math.max(20, Math.round(maxWaveH + 14));
  }

  /** 开启波浪时内容区下边界（pt）；无波浪时返回 −1（不覆盖）。 */
  waveAwareContentBottom(enableWave: boolean): number {
    if (!enableWave) return -1;
    const base = this.style.canvasH - this.style.marginBottom;
    return Math.max(this.style.contentTop + 40, base - this.waveClearance());
  }

  /** 生成三层流动弧线波浪装饰的路径数据（按渲染顺序：索引0先画=底层）。 */
  generateWaveLayers(baseColor: string): WaveLayer[] {
    const colors = deriveWaveColors(baseColor, this.waveParams.contrast);
    const layerConfigs: Array<{ maxH: number; peaks: Array<[number, number]> }> = [
      { maxH: 38, peaks: [[0.2, 0.45], [0.4, 0.78], [0.63, 0.5], [0.9, 0.8]] },
      { maxH: 28, peaks: [[0.24, 0.52], [0.46, 0.86], [0.72, 0.55], [0.95, 0.84]] },
      { maxH: 19, peaks: [[0.22, 0.55], [0.34, 1.0], [0.64, 0.68], [0.9, 0.95]] },
    ];
    const baseAlpha = [0.45, 0.7, 0.95];
    return layerConfigs.map((cfg, idx) => ({
      controlPoints: this.buildFlowingWavePath(cfg.maxH * this.waveParams.heightScale, cfg.peaks),
      color: colors[idx],
      alpha: Math.min(1, Math.max(0, baseAlpha[idx] * this.waveParams.opacityScale)),
    }));
  }

  /**
   * 构建单层流动波浪路径的控制点序列（归一化坐标 0~1）。
   * Catmull-Rom 样条转三次贝塞尔，C1 连续（无折角）。
   * 输出：moveTo 起点(左下) + 每 6 值一段 cubicBezier + 末尾 4 值闭合矩形。
   */
  buildFlowingWavePath(maxHeight: number, peaks: Array<[number, number]>): number[] {
    const h = this.style.canvasH;
    const baseFrac = maxHeight / h; // 波浪最高处占画布高度比例（归一化）
    const ctrl: Array<[number, number]> = [];
    ctrl.push([0, 1]); // 左边缘（贴底）
    for (const [px, ph] of peaks) ctrl.push([px, 1 - ph * baseFrac]);
    ctrl.push([1, 1]); // 右边缘（贴底）
    const pts: number[] = [];
    pts.push(ctrl[0][0], ctrl[0][1]); // moveTo 起点
    for (let i = 0; i < ctrl.length - 1; i++) {
      const p0 = ctrl[i - 1] ?? ctrl[i];
      const p1 = ctrl[i];
      const p2 = ctrl[i + 1];
      const p3 = ctrl[i + 2] ?? ctrl[i + 1];
      const c1x = p1[0] + (p2[0] - p0[0]) / 6;
      const c1y = p1[1] + (p2[1] - p0[1]) / 6;
      const c2x = p2[0] - (p3[0] - p1[0]) / 6;
      const c2y = p2[1] - (p3[1] - p1[1]) / 6;
      pts.push(c1x, c1y, c2x, c2y, p2[0], p2[1]);
    }
    // 闭合底边（右下 → 左下）
    pts.push(1, 1, 0, 1);
    return pts;
  }

  // ────────────────────────────────────────────────
  // 模板 / 色块几何（内联 PptLayoutTemplates 常量）
  // ────────────────────────────────────────────────

  /** 装饰色块（无主题着色时给出几何矩形）；按版式返回原始归一化几何。 */
  rawBandsFor(layout: SlideLayout): Rect[] {
    switch (layout) {
      case SlideLayout.TOC: {
        const h = Math.max(Math.round(0.1 * this.style.canvasH), 36);
        return [new Rect(0, 0, this.style.canvasW, h)];
      }
      case SlideLayout.ENDING: {
        const h = Math.max(Math.round(0.1 * this.style.canvasH), 36);
        return [new Rect(0, this.style.canvasH - h, this.style.canvasW, h)];
      }
      case SlideLayout.SECTION: {
        const w = Math.round(0.2 * this.style.canvasW);
        return [new Rect(0, 0, w, this.style.canvasH)];
      }
      default:
        return [];
    }
  }

  /** 色块几何（含着色，角色统一为封面主色调 coverBg）。 */
  resolveBandsFor(layout: SlideLayout, theme: PptTheme): Array<{ rect: Rect; color: string }> {
    return this.rawBandsFor(layout).map((r) => ({ rect: r, color: theme.coverBg }));
  }

  /** 取某色块母版的装饰条几何（仅坐标）。 */
  bandRectFor(layout: SlideLayout): Rect {
    return this.rawBandsFor(layout)[0] ?? new Rect(0, 0, 0, 0);
  }

  /** H3 标题左侧竖条矩形（宽 4pt、标题文字左侧 12pt）。 */
  titleBarRect(x: number, y: number, h: number): Rect {
    return new Rect(x - 12, y, 4, h);
  }

  // ────────────────────────────────────────────────
  // 组合渲染
  // ────────────────────────────────────────────────

  /** 色块对应母版。 */
  bandTemplateOf(cb: ColorBlock): SlideLayout | null {
    switch (cb) {
      case ColorBlock.LEFT: return SlideLayout.SECTION;
      case ColorBlock.TOP: return SlideLayout.TOC;
      case ColorBlock.BOTTOM: return SlideLayout.ENDING;
      default: return null;
    }
  }

  /** 色块装饰（左/右/上/下色条）。 */
  decoFor(comp: SlideComposition, theme: PptTheme): SlideDeco {
    if (comp.colorBlock === ColorBlock.RIGHT) {
      const bandW = Math.round(0.2 * this.style.canvasW);
      const x = this.style.canvasW - bandW;
      return new SlideDeco({ bars: [new Rect(x, 0, bandW, this.style.canvasH)], barColor: theme.coverBg });
    }
    const tpl = this.bandTemplateOf(comp.colorBlock);
    if (tpl === null) return new SlideDeco();
    const resolved = this.resolveBandsFor(tpl, theme);
    return new SlideDeco({ bars: resolved.map((r) => r.rect), barColor: resolved[0] ? resolved[0].color : theme.coverBg });
  }

  /** 文本框区域：有色块时按 bandGap 内缩避让；全色/无色块占整页内容区。 */
  frameFor(comp: SlideComposition): Rect {
    const mx = this.style.marginX;
    const cTop = this.style.contentTop;
    const cw = this.style.contentW;
    const cBottom = this.style.contentBottom;
    const bandW = this.bandRectFor(SlideLayout.SECTION).w; // 左色条宽 144
    const bandH = this.bandRectFor(SlideLayout.TOC).h; // 上/下色带高 40
    switch (comp.colorBlock) {
      case ColorBlock.NONE:
      case ColorBlock.COVER:
        return new Rect(mx, cTop, cw, cBottom - cTop);
      case ColorBlock.LEFT: {
        const left = Math.max(mx, bandW + comp.bandGap);
        return new Rect(left, cTop, mx + cw - left, cBottom - cTop);
      }
      case ColorBlock.TOP: {
        const top = Math.max(cTop, bandH + comp.bandGap);
        return new Rect(mx, top, cw, cBottom - top);
      }
      case ColorBlock.BOTTOM: {
        const bottom = this.style.canvasH - bandH - comp.bandGap;
        return new Rect(mx, cTop, cw, Math.max(40, bottom - cTop));
      }
      case ColorBlock.RIGHT: {
        const right = this.style.canvasW - bandW - comp.bandGap;
        return new Rect(mx, cTop, Math.max(40, right - mx), cBottom - cTop);
      }
    }
  }

  /** 整页内容区矩形。 */
  fullRect(): Rect {
    return new Rect(this.style.marginX, this.style.contentTop, this.style.contentW, this.style.contentBottom - this.style.contentTop);
  }

  /** 是否为标题块（H1~H6）。 */
  isHeading(block: MdBlock): boolean {
    return block.kind === 'text' && block.type >= BlockType.H1 && block.type <= BlockType.H6;
  }

  /** 构造渲染单元。 */
  makeUnit(
    block: MdBlock,
    x: number,
    y: number,
    w: number,
    ch: number,
    cover: boolean,
    overflow: boolean,
    fontSizeOverride?: number,
    alignOverride?: Align,
    bold: boolean = false,
    colorOverride: string | null = null
  ): LaidOutUnit {
    const fam = this.fontForType(this.blockTypeOf(block));
    const latin = this.latinForType(this.blockTypeOf(block));
    const gap = this.gapForType(this.blockTypeOf(block));
    if (block.kind === 'text') {
      const blockFrags =
        block.type === BlockType.CODE && block.raw !== ''
          ? [{ text: block.raw, bold: false, italic: false, strike: false, link: null, code: true }]
          : block.fragments;
      const baseFs = fontSizeOverride ?? this.fontSizeOf(block.type);
      // 溢出自愈：单块内容超整页时按比例缩小字号，避免导出裁切
      const pageH = this.pageContentH();
      const fs = overflow && ch > pageH ? Math.max(9, Math.trunc((baseFs * pageH) / ch)) : baseFs;
      return {
        type: block.type,
        x, y, w, h: ch,
        fontSize: fs,
        align: alignOverride ?? (cover ? Align.CENTER : Align.LEFT),
        fragments: blockFrags,
        listItems: [],
        ordered: false,
        listStart: 0,
        overflow,
        table: null,
        bold,
        color: colorOverride,
        fontFamily: fam,
        latinFont: latin,
        gapAfter: gap,
      };
    }
    if (block.kind === 'list') {
      return {
        type: block.type,
        x: x + this.style.listIndent,
        y,
        w: w - this.style.listIndent,
        h: ch,
        fontSize: fontSizeOverride ?? this.style.fsBody,
        align: Align.LEFT,
        fragments: [],
        listItems: block.items,
        ordered: block.type === BlockType.ORDERED_LIST,
        listStart: block.listStart,
        overflow,
        table: null,
        bold,
        color: colorOverride,
        fontFamily: fam,
        latinFont: latin,
        gapAfter: gap,
      };
    }
    if (block.kind === 'table') {
      const tr = this.buildTableRender(block, w);
      return {
        type: BlockType.TABLE,
        x, y, w,
        h: tr.totalH,
        fontSize: tr.cellFs,
        align: Align.LEFT,
        fragments: [],
        listItems: [],
        ordered: false,
        listStart: 0,
        overflow,
        table: tr,
        bold,
        color: colorOverride,
        fontFamily: fam,
        latinFont: latin,
        gapAfter: gap,
      };
    }
    // ForcedBreak
    return {
      type: BlockType.DIVIDER,
      x, y, w, h: 14,
      fontSize: this.style.fsBody,
      align: Align.LEFT,
      fragments: [],
      listItems: [],
      ordered: false,
      listStart: 0,
      overflow: false,
      table: null,
      bold: false,
      color: null,
      fontFamily: fam,
      latinFont: latin,
      gapAfter: gap,
    };
  }

  // ────────────────────────────────────────────────
  // 表格
  // ────────────────────────────────────────────────

  buildTableRender(block: TableBlock, w: number): TableRender {
    const cols = Math.max(block.header.length, block.rows.reduce((mx, r) => Math.max(mx, r.length), 0), 1);
    const base = Math.floor(w / cols);
    const colW: number[] = [];
    for (let i = 0; i < cols; i++) colW.push(base);
    colW[cols - 1] += w - base * cols;
    const headerFs = this.style.fsBody + 2;
    const cellFs = this.style.fsBody - 1;
    const headerH = block.header.length > 0 ? this.tableRowH(block.header, colW, headerFs) : 0;
    const rowHs = block.rows.map((r) => this.tableRowH(r, colW, cellFs));
    const totalH = headerH + rowHs.reduce((a, b) => a + b, 0);
    return { header: block.header, rows: block.rows, colW, colAlign: block.colAlign, headerH, rowHs, cellFs, headerFs, totalH };
  }

  /** 单行最大行数决定的行高（含单元格上下内边距）。 */
  tableRowH(cells: InlineFragment[][], colW: number[], fs: number): number {
    let maxLines = 1;
    for (let j = 0; j < colW.length; j++) {
      const frags = cells[j] ?? [];
      const text = frags.map((f) => f.text).join('');
      const avail = Math.max(colW[j] - this.style.tablePad * 2, 20);
      const lines = this.lineCount(text, fs, avail);
      if (lines > maxLines) maxLines = lines;
    }
    return maxLines * Math.trunc(fs * this.style.lineMult) + this.style.tablePad * 2;
  }

  // ────────────────────────────────────────────────
  // 各版式渲染
  // ────────────────────────────────────────────────

  /** 封面：左侧强调色条 + 大标题 + 副标题 + 底部元信息，按组合对齐排布。 */
  layoutCover(blocks: MdBlock[], comp: SlideComposition): [LaidOutUnit[], SlideDeco | null] {
    if (blocks.length === 0) return [[], null];
    const units: LaidOutUnit[] = [];
    const cx = this.style.marginX;
    const cw = this.style.contentW;
    const hAlign = comp.halign === HAlign.CENTER ? Align.CENTER : Align.LEFT;
    const mainTitle = blocks.find((b) => this.isHeading(b) && (b as TextBlock).type === BlockType.H1) ?? blocks[0];
    const not = (b: MdBlock): boolean => b !== mainTitle;
    const kicker =
      blocks.find((b) => not(b) && b.kind === 'text' && b.type === BlockType.H2) ??
      blocks.find((b) => not(b) && b.kind === 'text' && b.type === BlockType.PARAGRAPH) ??
      null;
    const sub = blocks.find((b) => not(b) && b !== kicker && b.kind === 'text' && b.type === BlockType.PARAGRAPH) ?? null;
    const meta = blocks.filter((b) => not(b) && b !== kicker && b !== sub);
    const items: Array<{ block: MdBlock; fs: number; gap: number }> = [];
    if (kicker) items.push({ block: kicker, fs: this.fontSizeOf(this.blockTypeOf(kicker)), gap: 12 });
    if (mainTitle) items.push({ block: mainTitle, fs: this.fontSizeOf(this.blockTypeOf(mainTitle)), gap: 26 });
    if (sub) items.push({ block: sub, fs: this.fontSizeOf(this.blockTypeOf(sub)), gap: 22 });
    for (const b of meta) items.push({ block: b, fs: this.fontSizeOf(this.blockTypeOf(b)), gap: 8 });
    let totalH = 0;
    const heights = items.map((it) => {
      const ch = this.contentHeight(it.block, it.fs, cw);
      totalH += ch + it.gap;
      return ch;
    });
    totalH = Math.max(0, totalH - (items.length > 0 ? items[items.length - 1].gap : 0));
    const contentH = this.style.contentBottom - this.style.contentTop;
    let y =
      comp.valign === VAlign.CENTER
        ? this.style.contentTop + Math.trunc(Math.max(0, contentH - totalH) / 2)
        : this.style.contentTop;
    items.forEach((it, idx) => {
      const ch = heights[idx];
      units.push(this.makeUnit(it.block, cx, y, cw, ch, true, false, it.fs, hAlign));
      y += ch + it.gap;
    });
    return [units, null];
  }

  /** 上下：标题 + 正文纵向排列（默认内容模板）。 */
  layoutStandard(blocks: MdBlock[]): [LaidOutUnit[], SlideDeco | null] {
    const [u, d] = this.layoutColumn(blocks, this.style.marginX, this.style.contentW);
    return [u, d];
  }

  /** 左右：标题 + 内容左右分栏。 */
  layoutSplit(blocks: MdBlock[]): [LaidOutUnit[], SlideDeco | null] {
    const [u, d] = this.layoutSplitInFrame(blocks, this.fullRect(), true, Align.LEFT);
    return [u, d];
  }

  layoutSplitInFrame(
    blocks: MdBlock[],
    frame: Rect,
    vCenter: boolean,
    align: Align,
    ratio: number | null = null
  ): [LaidOutUnit[], SlideDeco] {
    if (blocks.length === 0) return [[], new SlideDeco()];
    const gap = this.style.splitGap;
    const nominalW = Math.trunc((frame.w - gap) / 2);
    const first = blocks.find((b) => this.isHeading(b)) ?? null;
    let left: MdBlock[];
    let right: MdBlock[];
    if (first) {
      left = [first];
      right = blocks.filter((b) => b !== first);
    } else {
      [left, right] = this.partitionSplit(blocks, nominalW);
    }
    const widths = this.columnWidths([left, right], frame.w, gap, nominalW, ratio);
    const leftW = Math.max(120, widths[0]);
    const rightW = Math.max(120, frame.w - leftW - gap);
    const lx = frame.x;
    // 全页级三级及以下标题检测：任一栏有子级标题则统一缩进基准
    const pageHasH3 = [...left, ...right].some((b) => b.kind === 'text' && b.type >= BlockType.H3);
    const LEVEL_INDENT = 24;
    const rx = frame.x + leftW + gap + (pageHasH3 ? LEVEL_INDENT : 0);
    const [leftU, leftDeco] = this.layoutColumn(left, lx, leftW, {
      topStart: frame.y, availBottom: frame.y + frame.h, vCenter, alignOverride: align,
    });
    const actualRightW = pageHasH3 ? rightW - LEVEL_INDENT : rightW;
    const [rightU, rightDeco] = this.layoutColumn(right, rx, Math.max(120, actualRightW), {
      topStart: frame.y, availBottom: frame.y + frame.h, vCenter, alignOverride: align,
    });
    return [
      leftU.concat(rightU),
      new SlideDeco({ bars: leftDeco.bars.concat(rightDeco.bars), quoteBg: leftDeco.quoteBg.concat(rightDeco.quoteBg) }),
    ];
  }

  /** 三栏：内容三等分（标题置顶 + 其下左右两栏连续流分栏）。 */
  layoutThreeCol(blocks: MdBlock[]): [LaidOutUnit[], SlideDeco | null] {
    const [u, d] = this.layoutThreeColInFrame(blocks, this.fullRect(), Align.LEFT);
    return [u, d];
  }

  layoutThreeColInFrame(blocks: MdBlock[], frame: Rect, align: Align, ratio: number | null = null): [LaidOutUnit[], SlideDeco] {
    if (blocks.length === 0) return [[], new SlideDeco()];
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const LEVEL_INDENT = 24;
    const title = blocks.find((b) => this.isHeading(b)) ?? null;
    let topY = frame.y + 4;
    if (title) {
      const tFs = this.fontSizeOf(this.blockTypeOf(title));
      const ch = this.contentHeight(title, tFs);
      const isH3OrLower = title.kind === 'text' && title.type >= BlockType.H3;
      const tx = isH3OrLower ? frame.x + LEVEL_INDENT : frame.x;
      units.push(this.makeUnit(title, tx, topY, frame.w - (isH3OrLower ? LEVEL_INDENT : 0), ch, false, false, tFs, align));
      if (title.kind === 'text' && title.type === BlockType.H3) bars.push(this.titleBarRect(tx, topY, ch));
      topY += ch + 30;
    }
    const rest = title ? blocks.filter((b) => b !== title) : blocks;
    if (rest.length === 0) return [units, new SlideDeco({ bars })];
    const gap = this.style.splitGap;
    const colW = Math.trunc((frame.w - gap) / 2);
    const [left, right] = this.flowSplit(rest, colW, topY, frame.y + frame.h);
    const widths = this.columnWidths([left, right], frame.w, gap, colW, ratio);
    const leftW = Math.max(120, widths[0]);
    const rightW = Math.max(120, widths[1]);
    const restHasH3 = [...left, ...right].some((b) => b.kind === 'text' && b.type >= BlockType.H3);
    const lx = frame.x;
    const rx = frame.x + leftW + gap + (restHasH3 ? LEVEL_INDENT : 0);
    const actualRightW = restHasH3 ? rightW - LEVEL_INDENT : rightW;
    const [leftU, leftDeco] = this.layoutColumn(left, lx, Math.max(120, leftW), {
      topStart: topY, availBottom: frame.y + frame.h, alignOverride: align,
    });
    const [rightU, rightDeco] = this.layoutColumn(right, rx, Math.max(120, actualRightW), {
      topStart: topY, availBottom: frame.y + frame.h, alignOverride: align,
    });
    units.push(...leftU, ...rightU);
    bars.push(...leftDeco.bars, ...rightDeco.bars);
    return [units, new SlideDeco({ bars, quoteBg: leftDeco.quoteBg.concat(rightDeco.quoteBg) })];
  }

  /** 四栏（标题置顶 + 其下四栏连续流分栏）。 */
  layoutFourColInFrame(blocks: MdBlock[], frame: Rect, align: Align, ratio: number | null = null): [LaidOutUnit[], SlideDeco] {
    if (blocks.length === 0) return [[], new SlideDeco()];
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const LEVEL_INDENT = 24;
    const title = blocks.find((b) => this.isHeading(b)) ?? null;
    let topY = frame.y + 4;
    if (title) {
      const tFs = this.fontSizeOf(this.blockTypeOf(title));
      const ch = this.contentHeight(title, tFs);
      const isH3OrLower = title.kind === 'text' && title.type >= BlockType.H3;
      const tx = isH3OrLower ? frame.x + LEVEL_INDENT : frame.x;
      units.push(this.makeUnit(title, tx, topY, frame.w - (isH3OrLower ? LEVEL_INDENT : 0), ch, false, false, tFs, align));
      if (title.kind === 'text' && title.type === BlockType.H3) bars.push(this.titleBarRect(tx, topY, ch));
      topY += ch + 30;
    }
    const rest = title ? blocks.filter((b) => b !== title) : blocks;
    if (rest.length === 0) return [units, new SlideDeco({ bars })];
    const gap = this.style.splitGap;
    const colW = Math.trunc((frame.w - gap * 3) / 4);
    const columns = this.splitToNColumns(rest, 4, colW, topY, frame.y + frame.h);
    const widths = this.columnWidths(columns, frame.w, gap, colW, ratio);
    const restHasH3 = columns.flat().some((b) => b.kind === 'text' && b.type >= BlockType.H3);
    columns.forEach((col, ci) => {
      const colW_ = Math.max(80, widths[ci]);
      const cx = frame.x + ci * (colW_ + gap) + (restHasH3 && ci > 0 ? LEVEL_INDENT : 0);
      const actualColW = restHasH3 && ci > 0 ? colW_ - LEVEL_INDENT : colW_;
      const [colU, colDeco] = this.layoutColumn(col, cx, Math.max(80, actualColW), {
        topStart: topY, availBottom: frame.y + frame.h, alignOverride: align,
      });
      units.push(...colU);
      bars.push(...colDeco.bars);
    });
    return [units, new SlideDeco({ bars })];
  }

  /** 上窄下宽：上区窄幅居中 + 下区全宽展开。 */
  layoutTopNarrowInFrame(blocks: MdBlock[], frame: Rect, vCenter: boolean, align: Align): [LaidOutUnit[], SlideDeco] {
    if (blocks.length === 0) return [[], new SlideDeco()];
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const narrowW = Math.max(200, Math.trunc(frame.w * 0.6));
    const narrowX = frame.x + Math.trunc((frame.w - narrowW) / 2);
    const title = blocks.find((b) => this.isHeading(b)) ?? null;
    const topBlocks = title ? [title] : blocks.slice(0, 1);
    const restBlocks = title ? blocks.filter((b) => b !== title) : blocks.slice(1);
    let topY = frame.y + 4;
    for (const b of topBlocks) {
      const fs = this.fontSizeOf(this.blockTypeOf(b));
      const ch = this.contentHeight(b, fs, narrowW);
      const isH3OrLower = b.kind === 'text' && b.type >= BlockType.H3;
      const tx = isH3OrLower ? narrowX + 24 : narrowX;
      const tw = isH3OrLower ? narrowW - 24 : narrowW;
      units.push(this.makeUnit(b, tx, topY, tw, ch, false, false, fs, Align.CENTER));
      if (b.kind === 'text' && b.type === BlockType.H3) bars.push(this.titleBarRect(tx, topY, ch));
      topY += ch + 20;
    }
    if (restBlocks.length > 0) {
      const bottomY = topY + 12;
      const [restU, restDeco] = this.layoutColumn(restBlocks, frame.x, frame.w, {
        topStart: bottomY, availBottom: frame.y + frame.h, vCenter, alignOverride: align,
      });
      units.push(...restU);
      bars.push(...restDeco.bars);
    }
    return [units, new SlideDeco({ bars })];
  }

  /** 将块列表均衡分配到 N 栏（按段落截断、连续流）。 */
  splitToNColumns(blocks: MdBlock[], n: number, colW: number, topY: number, availBottom: number): MdBlock[][] {
    const columns: MdBlock[][] = [];
    for (let i = 0; i < n; i++) columns.push([]);
    const colHeights = new Array<number>(n).fill(topY);
    for (const block of blocks) {
      const fs = this.fontSizeOf(this.blockTypeOf(block));
      const bh = this.contentHeight(block, fs, colW);
      let ci = 0;
      let min = colHeights[0];
      for (let i = 1; i < n; i++) {
        if (colHeights[i] < min) {
          min = colHeights[i];
          ci = i;
        }
      }
      if (colHeights[ci] + bh > availBottom) {
        columns[n - 1].push(block);
        colHeights[n - 1] += bh;
      } else {
        columns[ci].push(block);
        colHeights[ci] += bh;
      }
    }
    return columns;
  }

  /**
   * 计算多栏各栏宽度（宽度之积 + (n-1)*gap = frameW）。
   * ratio != null：手动，第 0 栏固定占 ratio%，其余栏均分剩余宽度；
   * ratio == null：智能，按该栏内容高度加权配比。
   */
  columnWidths(columns: MdBlock[][], frameW: number, gap: number, nominalW: number, ratio: number | null): number[] {
    const n = columns.length;
    if (n <= 0) return [];
    const avail = Math.max(80, frameW - gap * (n - 1));
    if (ratio != null) {
      const first = Math.max(80, Math.trunc((avail * Math.min(85, Math.max(15, ratio))) / 100));
      const rest = avail - first;
      const other = n > 1 ? Math.trunc(rest / (n - 1)) : rest;
      const widths = new Array<number>(n).fill(0).map((_, i) => (i === 0 ? first : other));
      const used = widths[0] + other * (n - 1);
      if (n > 1 && rest > used) widths[n - 1] += rest - used;
      return widths;
    }
    const hPerCol = columns.map((c) => c.reduce((sum, b) => sum + this.contentHeight(b, this.fontSizeOf(this.blockTypeOf(b)), nominalW), 0));
    const sumH = hPerCol.reduce((a, b) => a + b, 0);
    if (sumH <= 0) {
      const w = new Array<number>(n).fill(Math.trunc(avail / n));
      w[n - 1] += avail - Math.trunc(avail / n) * n;
      return w;
    }
    const widths = hPerCol.map((h) => Math.max(80, Math.trunc((avail * h) / sumH)));
    let used = widths.reduce((a, b) => a + b, 0);
    if (used > avail) {
      const over = used - avail;
      const compressible = used - n * 80;
      if (compressible > 0) {
        const scale = Math.max(0, compressible - over) / compressible;
        for (let i = 0; i < widths.length; i++) widths[i] = 80 + Math.trunc((widths[i] - 80) * scale);
        used = widths.reduce((a, b) => a + b, 0);
      }
    }
    if (used < avail) widths[n - 1] += avail - used;
    return widths;
  }

  /** 把一个页面的块拆成左右两栏（用列宽估算高度）。 */
  partitionSplit(blocks: MdBlock[], width: number): [MdBlock[], MdBlock[]] {
    const first = blocks[0];
    if (first.kind === 'text' && first.type <= BlockType.H6) {
      return [[first], blocks.slice(1)];
    }
    const left: MdBlock[] = [];
    const right: MdBlock[] = [];
    let lh = 0;
    let rh = 0;
    for (const b of blocks) {
      const h = this.contentHeight(b, this.fontSizeOf(this.blockTypeOf(b)), width) + this.gapOf(this.blockTypeOf(b));
      if (lh <= rh) {
        left.push(b);
        lh += h;
      } else {
        right.push(b);
        rh += h;
      }
    }
    return [left, right];
  }

  /** 三栏连续分栏流（报纸式）：先放满左栏，再放右栏。列表按项边界截断，其他块原子移动。 */
  flowSplit(blocks: MdBlock[], colW: number, availTop: number, availBottom: number): [MdBlock[], MdBlock[]] {
    if (blocks.length === 0) return [[], []];
    const availH = availBottom - availTop;
    const left: MdBlock[] = [];
    const right: MdBlock[] = [];
    let usedH = 0;
    const narrowFactor = colW < this.style.contentW * 0.8 ? 1.15 : 1.0;
    let overflowing = false;
    let i = 0;
    while (i < blocks.length) {
      const block = blocks[i];
      const fs = this.fontSizeOf(this.blockTypeOf(block));
      const gap = this.gapOf(this.blockTypeOf(block));
      const ch = Math.trunc(this.contentHeight(block, fs, colW) * narrowFactor);
      const needed = ch + gap;
      const fits = !overflowing && usedH + needed <= availH;
      if (fits) {
        left.push(block);
        usedH += needed;
        i++;
      } else {
        if (block.kind === 'list') {
          const remaining = left.length === 0 ? availH : Math.max(0, availH - usedH);
          const [leftPart, rightPart] = this.splitListItems(block, colW, remaining, narrowFactor);
          if (leftPart.items.length > 0) {
            left.push(leftPart);
            const leftH = Math.trunc(this.contentHeight(leftPart, fs, colW) * narrowFactor) + gap;
            usedH += leftH;
          }
          if (rightPart.items.length > 0) {
            right.push(rightPart);
            overflowing = true;
          }
          i++;
        } else if (block.kind === 'text') {
          if (this.isHeading(block)) {
            // 标题块：标题及之后所有内容移到右栏（保持标题-正文关联）
            right.push(...blocks.slice(i));
            break;
          } else {
            right.push(block);
            overflowing = true;
            i++;
          }
        } else {
          // 表格/强制分页等：整块移到右栏
          right.push(block);
          overflowing = true;
          i++;
        }
      }
    }
    // 边界情况：如果左栏仍为空，强制把第一个块放入左栏
    if (left.length === 0 && right.length > 0) {
      const first = right.shift();
      if (first) left.push(first);
    }
    return [left, right];
  }

  /** 将一个 ListBlock 在项边界处拆分为两部分（返回左栏部分 + 右栏溢出部分）。 */
  splitListItems(list: ListBlock, colW: number, availH: number, narrowFactor: number): [ListBlock, ListBlock] {
    if (list.items.length === 0) return [list, list];
    const fs = this.style.fsBody;
    const prefixW = fs * 0.55 * 3;
    const effectiveW = Math.max(Math.round(colW - prefixW), Math.round(colW * 0.3));
    let used = 0;
    let splitIdx = list.items.length; // 默认：全部放入左栏
    for (let idx = 0; idx < list.items.length; idx++) {
      const item = list.items[idx];
      const indentDeduction = Math.min(item.indent * fs * 2, Math.floor(effectiveW / 2));
      const itemW = Math.max(effectiveW - indentDeduction, Math.round(colW * 0.2));
      const text = item.fragments.map((f) => f.text).join('');
      const lineC = this.lineCount(text, fs, itemW);
      const itemH = Math.trunc(lineC * (fs * this.style.lineMult * 1.04)) + 2;
      const totalItemH = Math.trunc(itemH * narrowFactor);
      if (used + totalItemH <= availH) {
        used += totalItemH;
      } else {
        splitIdx = idx;
        break;
      }
    }
    return [
      { kind: 'list', type: list.type, items: list.items.slice(0, splitIdx), listStart: list.listStart },
      { kind: 'list', type: list.type, items: list.items.slice(splitIdx), listStart: list.listStart + splitIdx },
    ];
  }

  // ────────────────────────────────────────────────
  // 在指定列内纵向堆叠
  // ────────────────────────────────────────────────

  layoutColumn(
    blocks: MdBlock[],
    x: number,
    w: number,
    opts: {
      cover?: boolean;
      listFs?: number;
      topStart?: number;
      availBottom?: number;
      vCenter?: boolean;
      gapMultiplier?: number;
      alignOverride?: Align | null;
    } = {}
  ): [LaidOutUnit[], SlideDeco] {
    const {
      cover = false,
      listFs = this.style.fsBody,
      topStart = this.style.contentTop,
      availBottom = this.style.contentBottom,
      vCenter = false,
      gapMultiplier = 1.0,
      alignOverride = null,
    } = opts;
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    // 先用实际列宽 w 估算总高，便于垂直居中
    let totalH = 0;
    blocks.forEach((block, idx) => {
      const fs = block.kind === 'list' ? listFs : this.fontSizeOf(this.blockTypeOf(block));
      totalH += this.contentHeight(block, fs, w) + Math.trunc(this.gapOf(this.blockTypeOf(block)) * gapMultiplier);
      // 引用块（非本列首个块）：与上方文本保持适度段前距
      if (block.kind === 'text' && block.type === BlockType.QUOTE && idx > 0) totalH += this.style.quoteGapBefore;
    });
    let y = topStart;
    if (vCenter && blocks.length > 0) {
      const avail = availBottom - topStart;
      y = topStart + Math.max(0, Math.trunc((avail - totalH) / 2));
    }
    const LEVEL_INDENT = 24;
    const isCenterAlign = alignOverride === Align.CENTER;
    let useX = x;
    let h3IndentApplied = false;
    let firstPlaced = false;
    const quoteBg: Rect[] = [];
    for (const block of blocks) {
      let fs = block.kind === 'list' ? listFs : this.fontSizeOf(this.blockTypeOf(block));
      // 引用块（非本列首个块）：与上方文本保持适度段前距
      if (block.kind === 'text' && block.type === BlockType.QUOTE && firstPlaced) y += this.style.quoteGapBefore;
      let ch = this.contentHeight(block, fs, w);
      // 窄栏时增加 15% 高度余量，避免预览截断
      if (w < this.style.contentW * 0.8) ch = Math.trunc(ch * 1.15);
      // 单块超高（超过本页剩余高度）：缩小字号使其不溢出下边距
      const availH = availBottom - y;
      if (availH > 0 && ch > availH) {
        fs = Math.max(9, Math.trunc((fs * availH) / ch));
        ch = this.contentHeight(block, fs, w);
      }
      const bh = ch + Math.trunc(this.gapOf(this.blockTypeOf(block)) * gapMultiplier);
      // 首次遇到三级及以下标题：切换到缩进 x（居中对齐时不缩进）
      if (!isCenterAlign && !h3IndentApplied && block.kind === 'text' && block.type >= BlockType.H3) {
        useX = x + LEVEL_INDENT;
        h3IndentApplied = true;
      }
      // 居中对齐时 H3 竖线不放入全局 bars
      if (!isCenterAlign && block.kind === 'text' && block.type === BlockType.H3) {
        bars.push(this.titleBarRect(useX, y, ch));
      }
      const isQuote = block.kind === 'text' && block.type === BlockType.QUOTE;
      const indent = Math.max(0, useX - x);
      const qx = useX;
      const qw = Math.max(40, w - indent);
      if (isQuote) {
        const padX = 8;
        const padY = 6;
        quoteBg.push(new Rect(qx - padX, y - padY, qw + padX * 2, ch + padY * 2));
      }
      units.push(this.makeUnit(block, qx, y, qw, ch, cover, ch > this.pageContentH(), fs, alignOverride ?? undefined));
      y += bh;
      firstPlaced = true;
    }
    return [units, new SlideDeco({ bars, quoteBg })];
  }

  // ────────────────────────────────────────────────
  // 特殊页渲染
  // ────────────────────────────────────────────────

  /** 章节页：左侧满高主色条 + 右侧文字垂直居中。 */
  layoutSection(blocks: MdBlock[], sectionNum: number, theme: PptTheme): [LaidOutUnit[], SlideDeco | null] {
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const resolved = this.resolveBandsFor(SlideLayout.SECTION, theme);
    const sideBand = resolved[0] ? resolved[0].rect : new Rect(0, 0, 0, 0);
    const sideColor = resolved[0] ? resolved[0].color : theme.coverBg;
    bars.push(sideBand);
    const leftW = sideBand.w;
    const frame = this.fullRect();
    const gap = 28; // SECTION_GAP
    const rx = frame.x + leftW + gap;
    const rw = frame.w - leftW - gap;
    const title = blocks.find((b) => this.isHeading(b)) ?? null;
    const items: Array<{ block: MdBlock; fs: number }> = [];
    const titleFs = title ? this.fontSizeOf(this.blockTypeOf(title)) : this.style.fsH1;
    if (title) items.push({ block: title, fs: titleFs });
    for (const b of blocks.filter((b) => b !== title)) items.push({ block: b, fs: this.fontSizeOf(this.blockTypeOf(b)) });
    if (items.length === 0) {
      for (const b of blocks) items.push({ block: b, fs: this.fontSizeOf(this.blockTypeOf(b)) });
    }
    const itemGap = 18;
    let totalH = 0;
    const heights = items.map((it) => {
      const ch = this.contentHeight(it.block, it.fs, rw);
      totalH += ch + itemGap;
      return ch;
    });
    totalH = Math.max(0, totalH - itemGap);
    let y = this.style.contentTop + Math.trunc(Math.max(0, this.style.contentBottom - this.style.contentTop - totalH) / 2);
    items.forEach((it, idx) => {
      const ch = heights[idx];
      units.push(this.makeUnit(it.block, rx, y, rw, ch, false, false, it.fs, Align.LEFT));
      y += ch + itemGap;
    });
    return [units, new SlideDeco({ bars, accentBg: false, barColor: sideColor })];
  }

  /** 居中页（金句/引用突出展示：水平垂直居中，渲染全部块，不删减文本）。 */
  layoutQuote(blocks: MdBlock[]): [LaidOutUnit[], SlideDeco | null] {
    if (blocks.length === 0) return [[], null];
    const units: LaidOutUnit[] = [];
    const gap = 16;
    const items = blocks.map((b) => {
      const fs = this.fontSizeOf(this.blockTypeOf(b));
      return { block: b, fs, ch: this.contentHeight(b, fs, this.style.contentW) };
    });
    const totalH = items.reduce((s, it) => s + it.ch, 0) + gap * (items.length - 1);
    let y = this.style.contentTop + Math.trunc(Math.max(0, this.style.contentBottom - this.style.contentTop - totalH) / 2);
    for (const it of items) {
      units.push(this.makeUnit(it.block, this.style.marginX, y, this.style.contentW, it.ch, false, false, it.fs, Align.CENTER));
      y += it.ch + gap;
    }
    return [units, null];
  }

  /** 要点页：标题居中置顶，其下内容垂直居中放大显示。 */
  layoutList(blocks: MdBlock[]): [LaidOutUnit[], SlideDeco | null] {
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const title = blocks.find((b) => this.isHeading(b)) ?? null;
    const rest = title ? blocks.filter((b) => b !== title) : blocks;
    const tFs = title ? this.fontSizeOf(this.blockTypeOf(title)) : this.style.fsH1;
    const fsOf = (b: MdBlock) => this.fontSizeOf(this.blockTypeOf(b));
    const titleCh = title ? this.contentHeight(title, tFs) : 0;
    const titleGap = 8;
    const contentH =
      rest.length === 0
        ? 0
        : rest.reduce((s, b) => s + this.contentHeight(b, fsOf(b), this.style.contentW) + this.gapOf(this.blockTypeOf(b)) + 8, 0);
    const totalH = titleCh + (title && rest.length > 0 ? titleGap : 0) + contentH;
    let y = this.style.contentTop + Math.trunc(Math.max(0, this.style.contentBottom - this.style.contentTop - totalH) / 2);
    if (title) {
      units.push(this.makeUnit(title, this.style.marginX, y, this.style.contentW, titleCh, false, false, tFs));
      y += titleCh + titleGap;
    }
    for (const block of rest) {
      const fs = fsOf(block);
      const ch = this.contentHeight(block, fs, this.style.contentW);
      units.push(this.makeUnit(block, this.style.marginX, y, this.style.contentW, ch, false, false, fs, Align.CENTER));
      y += ch + this.gapOf(this.blockTypeOf(block)) + 8;
    }
    return [units, new SlideDeco({ bars })];
  }

  /** 目录页：上 1/10 满屏主色块 + 标题反白，下 9/10 条目（未写条目时自动从全文标题生成）。 */
  layoutToc(blocks: MdBlock[], theme: PptTheme, autoEntries: MdBlock[] = []): [LaidOutUnit[], SlideDeco | null] {
    const units: LaidOutUnit[] = [];
    const bars: Rect[] = [];
    const resolved = this.resolveBandsFor(SlideLayout.TOC, theme);
    const band = resolved[0] ? resolved[0].rect : new Rect(0, 0, 0, 0);
    const bandColor = resolved[0] ? resolved[0].color : theme.coverBg;
    const bandH = band.h;
    bars.push(band);
    let title = blocks.find((b) => this.isHeading(b)) ?? null;
    let rest = title ? blocks.filter((b) => b !== title) : blocks.slice();
    if (rest.length === 0 && autoEntries.length > 0) {
      rest = autoEntries;
      if (title === null) title = { kind: 'text', type: BlockType.H1, fragments: fragmentsOf('目录'), raw: '' };
    }
    if (title) {
      const tFs = this.fontSizeOf(this.blockTypeOf(title));
      const ch = this.contentHeight(title, tFs, this.style.contentW);
      const ty = Math.max(0, Math.trunc((bandH - ch) / 2));
      units.push(this.makeUnit(title, this.style.marginX, ty, this.style.contentW, ch, false, false, tFs, Align.LEFT, false, 'FFFFFF'));
    }
    const topY = bandH + 24; // TOC_TEXT_GAP
    const [restUnits, restDeco] = this.layoutColumn(rest, this.style.marginX, this.style.contentW, {
      topStart: topY, gapMultiplier: 1.6,
    });
    units.push(...restUnits);
    bars.push(...restDeco.bars);
    return [units, new SlideDeco({ bars, barColor: bandColor, quoteBg: restDeco.quoteBg })];
  }

  /** 结尾页：页面正中居中总结语/感谢语，底部小字落款（无内容时默认致谢语）。 */
  layoutEnding(blocks: MdBlock[], theme: PptTheme): [LaidOutUnit[], SlideDeco | null] {
    const src: MdBlock[] =
      blocks.length === 0
        ? [{ kind: 'text', type: BlockType.QUOTE, fragments: fragmentsOf('感谢聆听'), raw: '' }]
        : blocks;
    const units: LaidOutUnit[] = [];
    const cx = this.style.marginX;
    const cw = this.style.contentW;
    const resolved = this.resolveBandsFor(SlideLayout.ENDING, theme);
    const band = resolved[0] ? resolved[0].rect : new Rect(0, 0, 0, 0);
    const bandColor = resolved[0] ? resolved[0].color : theme.coverBg;
    const bandH = band.h;
    const bandTop = band.y;
    const bars: Rect[] = [band];
    const main = src.find((b) => b.kind === 'text' && (b.type === BlockType.QUOTE || b.type === BlockType.PARAGRAPH)) ?? src[0];
    const mainFs = this.fontSizeOf(this.blockTypeOf(main));
    const mainCh = this.contentHeight(main, mainFs);
    const meta = src.filter((b) => b !== main);
    const metaFsOf = (b: MdBlock) => this.fontSizeOf(this.blockTypeOf(b));
    const metaGap = 6;
    let metaH = 0;
    const metaHeights = meta.map((b) => {
      const h = this.contentHeight(b, metaFsOf(b), cw);
      metaH += h + metaGap;
      return h;
    });
    metaH = Math.max(0, metaH - metaGap);
    const gap10 = bandH;
    const blockH = mainCh + gap10 + metaH;
    const areaBottom = bandTop;
    const startY = Math.max(this.style.contentTop, Math.trunc((areaBottom - blockH) / 2));
    const mainY = startY;
    units.push(this.makeUnit(main, cx, mainY, cw, mainCh, false, false, mainFs, Align.CENTER));
    let my = mainY + mainCh + gap10;
    for (let idx = 0; idx < meta.length; idx++) {
      const b = meta[idx];
      const ch = metaHeights[idx];
      units.push(this.makeUnit(b, cx, my, cw, ch, false, false, metaFsOf(b), Align.CENTER));
      my += ch + metaGap;
    }
    return [units, new SlideDeco({ bars, barColor: bandColor })];
  }

  // ────────────────────────────────────────────────
  // 组合渲染（role=NONE 的内容页）
  // ────────────────────────────────────────────────

  layoutComposition(comp: SlideComposition, blocks: MdBlock[], theme: PptTheme): [LaidOutUnit[], SlideDeco | null] {
    if (blocks.length === 0) return [[], null];
    const baseDeco = this.decoFor(comp, theme);
    const frame = this.frameFor(comp);
    const align = comp.halign === HAlign.CENTER ? Align.CENTER : Align.LEFT;
    const vCenter = comp.valign === VAlign.CENTER;
    let units: LaidOutUnit[];
    let contentDeco: SlideDeco | null;
    if (comp.structure === Structure.VERTICAL) {
      if (comp.colorBlock === ColorBlock.NONE) {
        if (!vCenter && comp.halign === HAlign.LEFT) {
          [units, contentDeco] = this.layoutStandard(blocks);
        } else if (vCenter && comp.halign === HAlign.LEFT) {
          [units, contentDeco] = this.layoutList(blocks);
        } else if (vCenter && comp.halign === HAlign.CENTER) {
          [units, contentDeco] = this.layoutQuote(blocks);
        } else {
          [units, contentDeco] = this.layoutColumn(blocks, frame.x, frame.w, {
            topStart: frame.y, availBottom: frame.y + frame.h, vCenter, alignOverride: align,
          });
        }
      } else {
        [units, contentDeco] = this.layoutColumn(blocks, frame.x, frame.w, {
          topStart: frame.y, availBottom: frame.y + frame.h, vCenter, alignOverride: align,
        });
      }
    } else if (comp.structure === Structure.TWO_COL) {
      [units, contentDeco] = this.layoutSplitInFrame(blocks, frame, vCenter, align, comp.colRatio);
    } else if (comp.structure === Structure.THREE_COL) {
      [units, contentDeco] = this.layoutThreeColInFrame(blocks, frame, align, comp.colRatio);
    } else if (comp.structure === Structure.FOUR_COL) {
      [units, contentDeco] = this.layoutFourColInFrame(blocks, frame, align, comp.colRatio);
    } else {
      // TOP_NARROW
      [units, contentDeco] = this.layoutTopNarrowInFrame(blocks, frame, vCenter, align);
    }
    // 合并色块装饰（band）与内容装饰（H3 竖线 / 引用背景）
    const merged = new SlideDeco({
      bars: baseDeco.bars.concat(contentDeco?.bars ?? []),
      barColor: baseDeco.barColor ?? contentDeco?.barColor ?? null,
      quoteBg: baseDeco.quoteBg.concat(contentDeco?.quoteBg ?? []),
      accentBg: contentDeco?.accentBg ?? false,
    });
    // 全色底色页：整页同色，H3 竖线不可见，统一不画
    const finalDeco = comp.colorBlock === ColorBlock.COVER ? merged.copy({ bars: [] }) : merged;
    return [units, finalDeco];
  }

  // ────────────────────────────────────────────────
  // 主入口：按页布局
  // ────────────────────────────────────────────────

  /**
   * 按页布局。封面页强制单列居中；其余按 [layoutOf] 选择模板。
   * @param barHeightDenom 直线色块高度分母 N（高度 = 画布高 / N，默认 60）。
   */
  layout(
    pages: PaginationResult,
    theme: PptTheme,
    layoutOf: (idx: number) => SlideLayout = () => SlideLayout.STANDARD,
    compOf: (idx: number) => SlideComposition | null = () => null,
    barHeightDenom: number = 60
  ): LaidOutSlide[] {
    let sectionSeq = 0;
    // 全文标题（H1/H2）：用于目录页自动补条目、避免空白页
    const docHeadings = pages.pages
      .flatMap((p) => p.blocks)
      .filter((b): b is TextBlock => b.kind === 'text' && (b.type === BlockType.H1 || b.type === BlockType.H2));
    const originalStyle = this.style;
    const results: LaidOutSlide[] = [];
    try {
      for (let idx = 0; idx < pages.pages.length; idx++) {
        const slide = pages.pages[idx];
        const requested = layoutOf(idx);
        const comp = compOf(idx) ?? CompositionResolver.compositionOf(requested);
        const hasBigBlock = comp.hasBigBlock;
        if (comp.role === PageRole.COVER) {
          const [units, deco] = this.layoutCover(slide.blocks, comp);
          results.push({ units, cover: true, layout: requested, deco, footer: null, composition: comp });
          continue;
        }
        const bottomDeco = comp.decoration;
        const waveOn = !hasBigBlock && bottomDeco === BottomDecoration.WAVE;
        const barOn = !hasBigBlock && bottomDeco === BottomDecoration.BAR;
        const logoOn = !hasBigBlock && bottomDeco === BottomDecoration.LOGO;
        const barH = Math.max(1, Math.trunc(this.style.canvasH / barHeightDenom));
        const effBottom = waveOn ? this.waveAwareContentBottom(true) : -1;
        this.style = this.style.copy({ contentBottomOverride: effBottom });
        let units: LaidOutUnit[];
        let deco: SlideDeco | null;
        let cover: boolean;
        switch (comp.role) {
          case PageRole.TOC: {
            [units, deco] = this.layoutToc(slide.blocks, theme, docHeadings);
            cover = false;
            break;
          }
          case PageRole.ENDING: {
            [units, deco] = this.layoutEnding(slide.blocks, theme);
            cover = false;
            break;
          }
          case PageRole.SECTION: {
            sectionSeq++;
            [units, deco] = this.layoutSection(slide.blocks, sectionSeq, theme);
            cover = false;
            break;
          }
          default: {
            [units, deco] = this.layoutComposition(comp, slide.blocks, theme);
            cover = comp.colorBlock === ColorBlock.COVER;
            break;
          }
        }
        let finalDeco: SlideDeco | null;
        if (waveOn) {
          finalDeco = deco ? deco.copy({ wave: true, waveColor: theme.accent }) : new SlideDeco({ wave: true, waveColor: theme.accent });
        } else if (barOn) {
          finalDeco = deco ? deco.copy({ bottomBar: true, bottomBarH: barH }) : new SlideDeco({ bottomBar: true, bottomBarH: barH });
        } else if (logoOn) {
          finalDeco = deco ? deco.copy({ logo: true }) : new SlideDeco({ logo: true });
        } else {
          finalDeco = deco;
        }
        results.push({ units, cover, layout: requested, deco: finalDeco, footer: null, composition: comp });
      }
      return results;
    } finally {
      this.style = originalStyle;
    }
  }
}

// ────────────────────────────────────────────────
// 顶层便捷函数（style 显式传入，供分页器/导出端/测试复用）
// ────────────────────────────────────────────────

/** 单块"内容渲染高"（不含段后距）。 */
export function contentHeight(block: MdBlock, style: PptStyleSheet, fontSize?: number, width?: number): number {
  return new PptLayoutEngine(style).contentHeight(block, fontSize, width);
}

/** 单块"总高"（含段后距），供分页与 Y 轴步进。 */
export function blockHeight(block: MdBlock, style: PptStyleSheet): number {
  return new PptLayoutEngine(style).blockHeight(block);
}

/** 超长代码块按物理行拆分（每页一个）。 */
export function splitLongCode(block: MdBlock, style: PptStyleSheet, availH?: number): MdBlock[] {
  return new PptLayoutEngine(style).splitLongCode(block, availH);
}

/** 超长表格按数据行拆分（每页保留表头）。 */
export function splitLongTable(block: TableBlock, style: PptStyleSheet, availH?: number): TableBlock[] {
  return new PptLayoutEngine(style).splitLongTable(block, availH);
}

/** 生成三层波浪装饰（导出端 custGeom 消费）。 */
export function generateWaveLayers(baseColor: string, style: PptStyleSheet, waveParams: PptWaveParams = DEFAULT_WAVE_PARAMS): WaveLayer[] {
  return new PptLayoutEngine(style, waveParams).generateWaveLayers(baseColor);
}

/**
 * 顶层布局入口：把分页结果排布成每页的渲染单元。
 * 默认：封面页（isCover）用 COVER 版式，其余用 STANDARD 版式；可通过 layoutOf/compOf 覆盖。
 */
export function layoutAll(
  pages: PaginationResult,
  theme: PptTheme,
  style: PptStyleSheet,
  layoutOf: (idx: number) => SlideLayout = (idx: number) => (pages.pages[idx].isCover ? SlideLayout.COVER : SlideLayout.STANDARD),
  compOf: (idx: number) => SlideComposition | null = () => null,
  barHeightDenom: number = 60
): LaidOutSlide[] {
  return new PptLayoutEngine(style).layout(pages, theme, layoutOf, compOf, barHeightDenom);
}
