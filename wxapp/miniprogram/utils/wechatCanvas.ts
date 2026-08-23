/**
 * 公众号文章 → Canvas 2D 渲染（存相册）。
 * 纯布局绘制：不依赖任何 DOM，仅用 ctx.measureText / fillText 等基础能力，
 * 与 core/wechat/wechatHtml.ts 共用同一套块模型 + 主题 CSS 解析，观感一致。
 *
 * 用法：先 renderArticle(ctx, blocks, css, { width, measureOnly: true }) 得到总高度，
 * 设置 canvas.height 后再次调用正式绘制，再 canvasToTempFilePath 导出图片。
 */
import { MdBlock, BlockType, InlineFragment } from '../core/mdBlocks';
import { parseCss, CssRule } from '../core/wechat/cssParser';

export interface RenderOptions {
  /** 逻辑宽度（px，不含 dpr）。正文内容区 = width - paddingLeft - paddingRight */
  width: number;
  /** 仅测量（不绘制），用于第一遍算高度 */
  measureOnly?: boolean;
}

interface BoxStyle {
  bg: string | null;
  color: string;
  fontFamily: string;
  fontSize: number;
  lineHeight: number;
  bold: boolean;
  textAlign: 'left' | 'center' | 'right' | 'justify';
  paddingTop: number;
  paddingRight: number;
  paddingBottom: number;
  paddingLeft: number;
  marginTop: number;
  marginBottom: number;
  borderLeftW: number;
  borderLeftColor: string | null;
}

const DEFAULT_BOX: BoxStyle = {
  bg: null,
  color: '#333333',
  fontFamily: 'sans-serif',
  fontSize: 16,
  lineHeight: 25.6,
  bold: false,
  textAlign: 'left',
  paddingTop: 0,
  paddingRight: 0,
  paddingBottom: 0,
  paddingLeft: 0,
  marginTop: 0,
  marginBottom: 0,
  borderLeftW: 0,
  borderLeftColor: null,
};

const NAMED: Record<string, string> = {
  black: '#000000',
  white: '#ffffff',
  red: '#ff0000',
  green: '#008000',
  blue: '#0000ff',
  gray: '#808080',
  grey: '#808080',
  orange: '#ffa500',
  yellow: '#ffff00',
  purple: '#800080',
  navy: '#000080',
  teal: '#008080',
};

function parseColor(v: string): string | null {
  const t = v.trim().toLowerCase();
  if (!t || t === 'transparent' || t === 'none') return null;
  if (t.startsWith('#')) {
    const hex = t.slice(1);
    if (hex.length === 3) return '#' + hex.split('').map((c) => c + c).join('');
    if (hex.length === 6) return '#' + hex;
    return null;
  }
  if (t.startsWith('rgb')) {
    const m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(t);
    if (m) {
      return '#' + [m[1], m[2], m[3]].map((n) => parseInt(n, 10).toString(16).padStart(2, '0')).join('');
    }
    return null;
  }
  return NAMED[t] ?? null;
}

function parsePx(v: string): number {
  const m = /^([\d.]+)(?:px)?$/.exec(v.trim());
  return m ? parseFloat(m[1]) : 0;
}

function parseLineHeight(v: string, fontSize: number): number {
  const t = v.trim();
  const num = parseFloat(t);
  if (isNaN(num)) return fontSize * 1.6;
  return t.endsWith('px') ? num : fontSize * num;
}

function parseBox(v: string): [number, number, number, number] {
  const parts = v.trim().split(/\s+/).map(parsePx);
  if (parts.length === 1) return [parts[0], parts[0], parts[0], parts[0]];
  if (parts.length === 2) return [parts[0], parts[1], parts[0], parts[1]];
  if (parts.length === 3) return [parts[0], parts[1], parts[2], parts[1]];
  if (parts.length >= 4) return [parts[0], parts[1], parts[2], parts[3]];
  return [0, 0, 0, 0];
}

function parseBorderLeft(v: string): { w: number; color: string | null } {
  const m = /([\d.]+)px\s+[\w-]+\s+(#[0-9a-fA-F]{3,8}|[a-z]+)/.exec(v.trim());
  if (!m) return { w: 0, color: null };
  return { w: parseFloat(m[1]), color: parseColor(m[2]) };
}

function pickFontFamily(v: string): string {
  const generic = new Set(['sans-serif', 'serif', 'monospace', 'cursive', 'fantasy', '-apple-system']);
  const tokens = v
    .split(',')
    .map((t) => t.trim().replace(/^["']|["']$/g, ''))
    .filter((t) => t && !generic.has(t.toLowerCase()));
  return tokens[0] || 'sans-serif';
}

function declsToBox(decls: Record<string, string>): Partial<BoxStyle> {
  const out: Partial<BoxStyle> = {};
  const fs = decls['font-size'] ? parsePx(decls['font-size']) : 16;
  out.fontSize = fs;
  if (decls['color']) out.color = parseColor(decls['color']) ?? '#333333';
  if (decls['font-family']) out.fontFamily = pickFontFamily(decls['font-family']);
  if (decls['line-height']) out.lineHeight = parseLineHeight(decls['line-height'], fs);
  if (decls['background']) out.bg = parseColor(decls['background']);
  const weight = (decls['font-weight'] ?? '').trim();
  out.bold = weight === 'bold' || parseInt(weight, 10) >= 600;
  const ta = (decls['text-align'] ?? 'left').trim();
  out.textAlign = ta === 'center' || ta === 'right' || ta === 'justify' ? ta : 'left';
  if (decls['padding']) {
    const [t, r, b, l] = parseBox(decls['padding']);
    out.paddingTop = t;
    out.paddingRight = r;
    out.paddingBottom = b;
    out.paddingLeft = l;
  }
  if (decls['margin']) {
    const [t, , b] = parseBox(decls['margin']);
    out.marginTop = t;
    out.marginBottom = b;
  }
  if (decls['margin-top']) out.marginTop = parsePx(decls['margin-top']);
  if (decls['margin-bottom']) out.marginBottom = parsePx(decls['margin-bottom']);
  if (decls['border-left']) {
    const bl = parseBorderLeft(decls['border-left']);
    out.borderLeftW = bl.w;
    out.borderLeftColor = bl.color;
  }
  return out;
}

interface IndexedCss {
  body: BoxStyle;
  byTag: Map<string, BoxStyle>;
}

/** 解析主题 CSS，按标签名索引（供 canvas 取样式） */
function indexRules(css: string): IndexedCss {
  const rules: CssRule[] = parseCss(css);
  const bodyDecls: Record<string, string> = {};
  const byTagDecls = new Map<string, Record<string, string>>();
  for (const r of rules) {
    if (r.isBodyRule) {
      Object.assign(bodyDecls, r.declarations);
      continue;
    }
    for (const sel of r.selectors) {
      const key = sel.trim().toLowerCase();
      if (/^[a-z0-9]+$/.test(key)) {
        const cur = byTagDecls.get(key) ?? {};
        Object.assign(cur, r.declarations);
        byTagDecls.set(key, cur);
      }
    }
  }
  const body: BoxStyle = { ...DEFAULT_BOX, ...declsToBox(bodyDecls) };
  const byTag = new Map<string, BoxStyle>();
  for (const [tag, decls] of byTagDecls) {
    byTag.set(tag, { ...body, ...declsToBox(decls) });
  }
  return { body, byTag };
}

function styleFor(tag: string, index: IndexedCss): BoxStyle {
  const s = index.byTag.get(tag);
  return s ? { ...index.body, ...s } : { ...index.body };
}

// ── 文本排版 ──

interface TSpan {
  text: string;
  bold: boolean;
  italic: boolean;
  strike: boolean;
  link: boolean;
  underline: boolean;
  color: string;
  fontFamily: string;
  fontSize: number;
  lineHeight: number;
}

function fragToSpans(frags: InlineFragment[], style: BoxStyle): TSpan[] {
  return frags.map((f) => ({
    text: f.text,
    bold: f.bold || style.bold,
    italic: f.italic,
    strike: f.strike,
    link: !!f.link,
    underline: !!f.link,
    color: style.color,
    fontFamily: f.code ? 'monospace' : style.fontFamily,
    fontSize: style.fontSize,
    lineHeight: style.lineHeight,
  }));
}

function setFont(ctx: any, s: { bold?: boolean; fontFamily?: string; fontSize?: number }): void {
  const weight = s.bold ? 'bold ' : '';
  const fam = s.fontFamily || 'sans-serif';
  const size = s.fontSize ?? 16;
  ctx.font = `${weight}${size}px ${fam}`;
}

function spanWidth(ctx: any, s: TSpan): number {
  if (!s.text) return 0;
  setFont(ctx, s);
  return ctx.measureText(s.text).width;
}

/** 把 spans 切行（每行仍是 spans 列表），按 maxWidth 贪婪折行 */
function splitLines(ctx: any, spans: TSpan[], maxWidth: number): TSpan[][] {
  const lines: TSpan[][] = [];
  let line: TSpan[] = [];
  let lineW = 0;

  const flush = () => {
    if (line.length > 0) {
      lines.push(line);
      line = [];
      lineW = 0;
    }
  };

  for (const sp of spans) {
    if (!sp.text) continue;
    const w = spanWidth(ctx, sp);
    if (w <= maxWidth) {
      // 片段整体放得下：能塞进当前行就塞，否则换行
      if (lineW + w <= maxWidth) {
        line.push(sp);
        lineW += w;
      } else {
        flush();
        line.push(sp);
        lineW = w;
      }
      continue;
    }
    // 单个片段超宽（如无行内样式的整段长文本）：按字符逐字折行
    flush();
    let rest = sp.text;
    while (rest.length > 0) {
      let take = 1;
      while (take < rest.length && lineW + spanWidth(ctx, { ...sp, text: rest.slice(0, take) }) <= maxWidth) take++;
      const piece = rest.slice(0, take);
      rest = rest.slice(take);
      if (line.length > 0 && lineW + spanWidth(ctx, { ...sp, text: piece }) > maxWidth) flush();
      line.push({ ...sp, text: piece });
      lineW += spanWidth(ctx, { ...sp, text: piece });
    }
  }
  flush();
  return lines;
}

function drawLine(
  ctx: any,
  line: TSpan[],
  x: number,
  y: number,
  maxWidth: number,
  align: 'left' | 'center' | 'right' | 'justify',
  isLast: boolean
): void {
  const totalW = line.reduce((a, s) => a + spanWidth(ctx, s), 0);
  let cursor = x;
  if (align === 'center') cursor = x + Math.max(0, (maxWidth - totalW) / 2);
  else if (align === 'right') cursor = x + Math.max(0, maxWidth - totalW);

  const justify = align === 'justify' && !isLast && totalW < maxWidth && line.length > 0;
  if (justify) {
    const charCount = line.reduce((a, s) => a + s.text.length, 0);
    const extra = charCount > 1 ? (maxWidth - totalW) / (charCount - 1) : 0;
    for (const s of line) {
      setFont(ctx, s);
      ctx.fillStyle = s.color;
      for (const ch of s.text) {
        const w = spanWidth(ctx, { ...s, text: ch });
        ctx.fillText(ch, cursor, y);
        if (s.underline) ctx.fillRect(cursor, y + 2, w, 1);
        cursor += w + extra;
      }
    }
    return;
  }

  for (const s of line) {
    setFont(ctx, s);
    ctx.fillStyle = s.color;
    const w = spanWidth(ctx, s);
    ctx.fillText(s.text, cursor, y);
    if (s.underline) ctx.fillRect(cursor, y + 2, w, 1);
    if (s.strike) ctx.fillRect(cursor, y - s.fontSize * 0.25, w, 1);
    cursor += w;
  }
}

// ── 渲染入口 ──

export function renderArticle(ctx: any, blocks: MdBlock[], css: string, opts: RenderOptions): number {
  const measure = opts.measureOnly === true;
  const idx = indexRules(css);
  const body = idx.body;
  const pageW = opts.width;
  const padL = body.paddingLeft;
  const padR = body.paddingRight;
  const contentW = Math.max(10, pageW - padL - padR);
  const x = padL;
  let y = body.paddingTop;

  const fillRect = (rx: number, ry: number, rw: number, rh: number, color: string) => {
    if (measure) return;
    ctx.fillStyle = color;
    ctx.fillRect(rx, ry, rw, rh);
  };

  for (const b of blocks) {
    if (b.kind === 'break') continue;

    if (b.kind === 'text') {
      const tag =
        b.type === BlockType.H1 ? 'h1' :
        b.type === BlockType.H2 ? 'h2' :
        b.type === BlockType.H3 ? 'h3' :
        b.type === BlockType.H4 ? 'h4' :
        b.type === BlockType.H5 ? 'h5' :
        b.type === BlockType.H6 ? 'h6' :
        b.type === BlockType.QUOTE ? 'blockquote' :
        b.type === BlockType.CODE ? 'pre' : 'p';
      const style = styleFor(tag, idx);
      y += style.marginTop;

      let text = b.fragments.length > 0 ? b.fragments.map((f) => f.text).join('') : b.raw;
      const spans: TSpan[] =
        b.type === BlockType.CODE
          ? text
              .replace(/^\n+|\n+$/g, '')
              .split('\n')
              .map((l) => {
                const cs = styleFor('code', idx);
                return {
                  text: l,
                  bold: false,
                  italic: false,
                  strike: false,
                  link: false,
                  underline: false,
                  color: cs.color,
                  fontFamily: 'monospace',
                  fontSize: cs.fontSize,
                  lineHeight: cs.lineHeight,
                };
              })
          : fragToSpans(b.fragments.length > 0 ? b.fragments : [{ text } as InlineFragment], style);

      const innerX = x + style.paddingLeft;
      const innerW = Math.max(10, contentW - style.paddingLeft - style.paddingRight);
      const lines = splitLines(ctx, spans, innerW);
      const blockH = lines.length * style.lineHeight + style.paddingTop + style.paddingBottom;

      if (style.bg) fillRect(x, y, contentW, blockH, style.bg);
      if (style.borderLeftW > 0 && style.borderLeftColor) {
        fillRect(x, y, style.borderLeftW, blockH, style.borderLeftColor);
      }

      let lineY = y + style.paddingTop + style.lineHeight * 0.85;
      for (let li = 0; li < lines.length; li++) {
        drawLine(ctx, lines[li], innerX, lineY, innerW, style.textAlign, li === lines.length - 1);
        lineY += style.lineHeight;
      }
      y += blockH + style.marginBottom;
      continue;
    }

    if (b.kind === 'list') {
      const liStyle = styleFor('li', idx);
      const listStyle = styleFor(b.type === BlockType.ORDERED_LIST ? 'ol' : 'ul', idx);
      y += listStyle.marginTop;
      let num = 1;
      for (const item of b.items) {
        const prefix = b.type === BlockType.ORDERED_LIST ? `${num}. ` : '• ';
        const depth = item.indent || 0;
        const indent = 16 + depth * 18;
        const availW = Math.max(10, contentW - indent);
        const bodySpans = fragToSpans(item.fragments, liStyle);
        const first = bodySpans[0];
        const prefixSpan: TSpan = first
          ? { ...first, text: prefix, fontFamily: 'sans-serif', fontSize: liStyle.fontSize }
          : { text: prefix, bold: false, italic: false, strike: false, link: false, underline: false, color: liStyle.color, fontFamily: 'sans-serif', fontSize: liStyle.fontSize, lineHeight: liStyle.lineHeight };
        const lines = splitLines(ctx, [prefixSpan, ...bodySpans], availW);
        let lineY = y + liStyle.lineHeight * 0.85;
        for (let li = 0; li < lines.length; li++) {
          drawLine(ctx, lines[li], x + indent, lineY, availW, 'left', li === lines.length - 1);
          lineY += liStyle.lineHeight;
        }
        y += lines.length * liStyle.lineHeight + 4;
        num++;
      }
      y += listStyle.marginBottom;
      continue;
    }

    if (b.kind === 'table') {
      const tableStyle = styleFor('table', idx);
      const thStyle = styleFor('th', idx);
      const tdStyle = styleFor('td', idx);
      y += tableStyle.marginTop;

      const colCount = Math.max(1, b.header.length);
      const weights = new Array<number>(colCount).fill(0);
      const allRows: InlineFragment[][][] = [b.header, ...b.rows];
      for (const row of allRows) {
        row.forEach((cell, ci) => {
          if (ci >= colCount) return;
          const t = cell.map((f) => f.text).join('');
          let w = 0;
          for (const ch of t) {
            const code = ch.charCodeAt(0);
            const cjk = (code >= 0x4e00 && code <= 0x9fff) || (code >= 0x3000 && code <= 0x303f) || (code >= 0xff00 && code <= 0xffef);
            w += cjk ? 1 : 0.55;
          }
          weights[ci] += w;
        });
      }
      const totalWt = weights.reduce((a, v) => a + v, 0);
      const colW: number[] = weights.map((w) => {
        if (totalWt <= 0) return contentW / colCount;
        return Math.max(20, (w / totalWt) * contentW);
      });

      const cellPad = 8;
      const rowTops: number[] = [y];

      const renderRow = (row: InlineFragment[][], header: boolean, ry: number): number => {
        const style = header ? thStyle : tdStyle;
        let maxLines = 1;
        const cellLines: TSpan[][][] = row.map((cell, ci) => {
          const cw = colW[Math.min(ci, colW.length - 1)];
          const spans = fragToSpans(cell, style);
          const lines = splitLines(ctx, spans, Math.max(20, cw - cellPad * 2));
          if (lines.length > maxLines) maxLines = lines.length;
          return lines;
        });
        const rh = maxLines * style.lineHeight + cellPad * 2;
        if (header && style.bg) fillRect(x, ry, contentW, rh, style.bg);
        let cx = x;
        for (let ci = 0; ci < colW.length; ci++) {
          const lines = cellLines[ci] ?? [];
          let cy = ry + cellPad + style.lineHeight * 0.85;
          for (let li = 0; li < lines.length; li++) {
            drawLine(ctx, lines[li], cx + cellPad, cy, colW[ci] - cellPad * 2, 'left', li === lines.length - 1);
            cy += style.lineHeight;
          }
          cx += colW[ci];
        }
        return rh;
      };

      const headH = renderRow(b.header, true, y);
      y += headH;
      rowTops.push(y);
      for (const row of b.rows) {
        const rh = renderRow(row, false, y);
        y += rh;
        rowTops.push(y);
      }

      // 边框
      if (!measure) {
        ctx.strokeStyle = '#dfdfdf';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (const ty of rowTops) {
          ctx.moveTo(x, ty);
          ctx.lineTo(x + contentW, ty);
        }
        let cx = x;
        for (let ci = 0; ci <= colW.length; ci++) {
          ctx.moveTo(cx, rowTops[0]);
          ctx.lineTo(cx, y);
          cx += ci < colW.length ? colW[ci] : 0;
        }
        ctx.stroke();
      }
      y += tableStyle.marginBottom;
      continue;
    }
  }

  return Math.ceil(y + body.paddingBottom);
}
