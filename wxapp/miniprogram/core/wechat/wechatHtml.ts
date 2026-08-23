/**
 * 公众号排版核心引擎（TypeScript 版，替代 Android 版 MdWechatConverter.kt）。
 * 直接由 mdBlocks 解析出的块模型生成 HTML，不经过 HTML 字符串/DOM。
 *
 * 提供**两路**输出：
 *   1) buildPreviewHtml —— 完整 HTML 文档（含 <head><style>），仅供预览。
 *   2) buildInlineHtml —— 100% 内联、零 <head>/<style>/class 的纯净片段，仅供剪贴板粘贴。
 *
 * 运行环境 = Node（跑测试）+ 小程序 core 层：不 import 任何 Node/小程序 API，
 * 纯算法 + 字符串拼接，不引入 HTML 解析库。
 */
import {
  parseMarkdown,
  BlockType,
  MdBlock,
  TextBlock,
  ListBlock,
  TableBlock,
  InlineFragment
} from '../mdBlocks';
import { parseCss, CssRule } from './cssParser';

// ────────────────────────────────────────────────
// 渲染选项：区分「预览」与「内联粘贴」
// ────────────────────────────────────────────────
interface RenderOpts {
  /** 是否把标签规则内联到元素 style（inline=true；preview=false，预览依赖 <style>） */
  inlineTagStyles: boolean;
  /** 是否做 body 级排版硬兜底（line-height/font-size/font-family/color，inline=true） */
  bodyFallback: boolean;
  /** 是否做表格兜底：border/cellspacing/cellpadding 属性 + 单元格边框/内边距（inline=true） */
  tableFallback: boolean;
}

const BODY_FALLBACK_TAGS = new Set<string>([
  'p', 'li', 'blockquote',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'td', 'th', 'pre', 'code', 'strong', 'em', 'a', 'span', 'div'
]);

const BODY_PROPS = ['line-height', 'font-size', 'font-family', 'color'] as const;

// ────────────────────────────────────────────────
// 转义工具（纯字符串，不依赖 DOM）
// ────────────────────────────────────────────────
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const escapeAttr = escapeHtml;

function entriesToStyle(m: Record<string, string>): string {
  const parts: string[] = [];
  for (const k of Object.keys(m)) parts.push(`${k}: ${m[k]}`);
  return parts.join('; ');
}

/** 由已序列化的 style 字符串生成属性片段（空串则不输出 style 属性） */
function styleAttrStr(style: string): string {
  return style === '' ? '' : ` style="${escapeAttr(style)}"`;
}

// ────────────────────────────────────────────────
// CSS 规则匹配与内联
// ────────────────────────────────────────────────
/**
 * 后代选择器匹配：selector 形如 `pre code` / `h1` / `th`，last 段匹配当前标签，
 * 其余段必须出现在祖先链（从最外层到父元素）中。
 */
function matchSelector(selector: string, tag: string, ancestors: string[]): boolean {
  const parts = selector.trim().split(/\s+/).filter((p) => p !== '');
  if (parts.length === 0) return false;
  if (parts[parts.length - 1] !== tag) return false;
  for (let i = 0; i < parts.length - 1; i++) {
    if (!ancestors.includes(parts[i])) return false;
  }
  return true;
}

/** 把匹配元素的非 body 规则声明合并进 style（后规则覆盖先规则） */
function applyTagRules(style: Record<string, string>, tag: string, ancestors: string[], rules: CssRule[]): void {
  for (const rule of rules) {
    if (rule.isBodyRule) continue;
    for (const sel of rule.selectors) {
      if (matchSelector(sel, tag, ancestors)) {
        for (const k of Object.keys(rule.declarations)) style[k] = rule.declarations[k];
      }
    }
  }
}

/** body 级排版硬兜底：落到所有文本元素（含行内元素） */
function applyBodyFallback(style: Record<string, string>, tag: string, bodyDecls: Record<string, string>): void {
  if (!BODY_FALLBACK_TAGS.has(tag)) return;
  for (const prop of BODY_PROPS) {
    if (!(prop in style)) {
      const v = bodyDecls[prop];
      if (v !== undefined && v !== '') style[prop] = v;
    }
  }
}

/** 收集 body 规则的合并声明 */
function collectBodyDecls(rules: CssRule[]): Record<string, string> {
  const bodyDecls: Record<string, string> = {};
  for (const rule of rules) {
    if (rule.isBodyRule) {
      for (const k of Object.keys(rule.declarations)) bodyDecls[k] = rule.declarations[k];
    }
  }
  return bodyDecls;
}

// ────────────────────────────────────────────────
// 表格列宽：按内容权重智能分配
// ────────────────────────────────────────────────
/** 折叠空白并去首尾空格；用于估算单元格文字占宽 */
function compactText(t: string): string {
  return t.replace(/\s+/g, ' ').trim();
}

/** 估算一个单元格的「显示权重」：数字/英文按半角 0.55，中文按全角 1 */
function cellWeight(text: string): number {
  const t = compactText(text);
  if (t === '') return 0;
  let half = 0;
  for (const ch of t) {
    if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch === '.' || ch === ',') {
      half++;
    }
  }
  const full = t.length - half;
  return half * 0.55 + full;
}

/** 单元格片段列表 → 纯文本（用于权重计算） */
function cellText(cell: InlineFragment[]): string {
  let s = '';
  for (const f of cell) s += f.text;
  return s;
}

/** 按内容权重计算各列宽（百分比字符串），全空时均分 */
function computeColumnWidths(header: InlineFragment[][], rows: InlineFragment[][][], colCount: number): string[] {
  if (colCount <= 0) return [];
  const colWeights: number[] = new Array<number>(colCount).fill(0);
  let total = 0;
  const accumulate = (cells: InlineFragment[][]): void => {
    for (let ci = 0; ci < cells.length; ci++) {
      const w = cellWeight(cellText(cells[ci]));
      colWeights[Math.min(ci, colCount - 1)] += w;
      total += w;
    }
  };
  accumulate(header);
  for (const row of rows) accumulate(row);

  const widths: string[] = [];
  if (total > 0) {
    for (let ci = 0; ci < colCount; ci++) {
      const pct = Math.round((colWeights[ci] / total) * 100);
      widths.push(`${Math.max(1, pct)}%`);
    }
  } else {
    const each = Math.floor(100 / colCount);
    for (let ci = 0; ci < colCount; ci++) widths.push(`${each}%`);
  }
  return widths;
}

// ────────────────────────────────────────────────
// 行内片段渲染
// ────────────────────────────────────────────────
function renderFragments(
  fragments: InlineFragment[],
  parentTag: string,
  ancestors: string[],
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  const inlineAncestors = ancestors.concat(parentTag);
  let out = '';
  for (const f of fragments) {
    let inner = escapeHtml(f.text);
    if (f.code) {
      const s = buildElementStyle('code', inlineAncestors, rules, bodyDecls, opts);
      inner = `<code${styleAttrStr(s)}>${inner}</code>`;
    }
    if (f.bold) {
      const s = buildElementStyle('strong', inlineAncestors, rules, bodyDecls, opts);
      inner = `<strong${styleAttrStr(s)}>${inner}</strong>`;
    }
    if (f.italic) {
      const s = buildElementStyle('em', inlineAncestors, rules, bodyDecls, opts);
      inner = `<em${styleAttrStr(s)}>${inner}</em>`;
    }
    if (f.strike) {
      const s = buildElementStyle('del', inlineAncestors, rules, bodyDecls, opts);
      inner = `<del${styleAttrStr(s)}>${inner}</del>`;
    }
    if (f.link) {
      const s = buildElementStyle('a', inlineAncestors, rules, bodyDecls, opts);
      inner = `<a href="${escapeAttr(f.link)}"${styleAttrStr(s)}>${inner}</a>`;
    }
    out += inner;
  }
  return out;
}

// ────────────────────────────────────────────────
// 块级渲染
// ────────────────────────────────────────────────
/** 计算单个元素的 style 字符串（标签规则 + body 硬兜底） */
function buildElementStyle(
  tag: string,
  ancestors: string[],
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  const style: Record<string, string> = {};
  if (opts.inlineTagStyles) applyTagRules(style, tag, ancestors, rules);
  if (opts.bodyFallback) applyBodyFallback(style, tag, bodyDecls);
  return entriesToStyle(style);
}

function headingTag(type: BlockType): string {
  switch (type) {
    case BlockType.H1: return 'h1';
    case BlockType.H2: return 'h2';
    case BlockType.H3: return 'h3';
    case BlockType.H4: return 'h4';
    case BlockType.H5: return 'h5';
    case BlockType.H6: return 'h6';
    default: return 'p';
  }
}

/** 代码块 raw：首尾去掉多余空行 */
function trimCodeRaw(raw: string): string {
  const lines = raw.split('\n');
  let s = 0;
  let e = lines.length;
  while (s < e && lines[s].trim() === '') s++;
  while (e > s && lines[e - 1].trim() === '') e--;
  return lines.slice(s, e).join('\n');
}

function renderTextBlock(
  b: TextBlock,
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  if (b.type === BlockType.CODE) {
    const preStyle = buildElementStyle('pre', ['section'], rules, bodyDecls, opts);
    const codeStyle = buildElementStyle('code', ['section', 'pre'], rules, bodyDecls, opts);
    const raw = trimCodeRaw(b.raw);
    return `<pre${styleAttrStr(preStyle)}><code${styleAttrStr(codeStyle)}>${escapeHtml(raw)}</code></pre>`;
  }
  if (b.type === BlockType.QUOTE) {
    const bqStyle = buildElementStyle('blockquote', ['section'], rules, bodyDecls, opts);
    const pStyle = buildElementStyle('p', ['section', 'blockquote'], rules, bodyDecls, opts);
    const inner = renderFragments(b.fragments, 'p', ['section', 'blockquote'], rules, bodyDecls, opts);
    return `<blockquote${styleAttrStr(bqStyle)}><p${styleAttrStr(pStyle)}>${inner}</p></blockquote>`;
  }
  if (b.type === BlockType.DIVIDER) {
    const hrStyle = buildElementStyle('hr', ['section'], rules, bodyDecls, opts);
    return `<hr${styleAttrStr(hrStyle)}>`;
  }
  const tag = headingTag(b.type);
  const style = buildElementStyle(tag, ['section'], rules, bodyDecls, opts);
  const inner = renderFragments(b.fragments, tag, ['section'], rules, bodyDecls, opts);
  return `<${tag}${styleAttrStr(style)}>${inner}</${tag}>`;
}

function renderListBlock(
  b: ListBlock,
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  const ordered = b.type === BlockType.ORDERED_LIST;
  const tag = ordered ? 'ol' : 'ul';
  const listStyle = buildElementStyle(tag, ['section'], rules, bodyDecls, opts);

  let startAttr = '';
  if (ordered) {
    for (const item of b.items) {
      if (item.number !== null) {
        if (item.number !== 1) startAttr = ` start="${item.number}"`;
        break;
      }
    }
  }

  const liAncestors = ['section', tag];
  let itemsHtml = '';
  for (const item of b.items) {
    const liStyle = buildElementStyle('li', liAncestors, rules, bodyDecls, opts);
    const inner = renderFragments(item.fragments, 'li', liAncestors, rules, bodyDecls, opts);
    itemsHtml += `<li${styleAttrStr(liStyle)}>${inner}</li>`;
  }
  return `<${tag}${startAttr}${styleAttrStr(listStyle)}>${itemsHtml}</${tag}>`;
}

function renderRow(
  cells: InlineFragment[][],
  cellTag: 'th' | 'td',
  widths: string[] | null,
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  const ancestors = ['section', 'table', 'tr'];
  let cellsHtml = '';
  for (let ci = 0; ci < cells.length; ci++) {
    const style: Record<string, string> = {};
    if (opts.inlineTagStyles) applyTagRules(style, cellTag, ancestors, rules);
    // 列宽只写首行（微信按首行定宽）
    if (widths !== null && ci < widths.length) {
      if (!('width' in style)) style['width'] = widths[ci];
      if (!('word-break' in style)) style['word-break'] = 'break-word';
    }
    if (opts.tableFallback) {
      if (!('border' in style)) style['border'] = '1px solid #dfdfdf';
      if (!('padding' in style)) style['padding'] = '8px 10px';
      if (!('word-break' in style)) style['word-break'] = 'break-word';
    }
    if (opts.bodyFallback) applyBodyFallback(style, cellTag, bodyDecls);
    const inner = renderFragments(cells[ci], cellTag, ancestors, rules, bodyDecls, opts);
    cellsHtml += `<${cellTag}${styleAttrStr(entriesToStyle(style))}>${inner}</${cellTag}>`;
  }
  return `<tr>${cellsHtml}</tr>`;
}

function renderTableBlock(
  b: TableBlock,
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  const colCount = b.header.length;
  const widths = computeColumnWidths(b.header, b.rows, colCount);

  const tableStyle: Record<string, string> = {};
  if (opts.inlineTagStyles) applyTagRules(tableStyle, 'table', ['section'], rules);
  if (!('table-layout' in tableStyle)) tableStyle['table-layout'] = 'fixed';
  if (!('width' in tableStyle)) tableStyle['width'] = '100%';
  if (opts.tableFallback) {
    if (!('border-collapse' in tableStyle)) tableStyle['border-collapse'] = 'collapse';
    if (!('border-spacing' in tableStyle)) tableStyle['border-spacing'] = '0';
    if (!('box-sizing' in tableStyle)) tableStyle['box-sizing'] = 'border-box';
  }
  const tableAttr = opts.tableFallback ? ' border="1" cellspacing="0" cellpadding="6"' : '';

  let rowsHtml = renderRow(b.header, 'th', widths, rules, bodyDecls, opts);
  for (const row of b.rows) {
    rowsHtml += renderRow(row, 'td', null, rules, bodyDecls, opts);
  }
  return `<table${tableAttr}${styleAttrStr(entriesToStyle(tableStyle))}>${rowsHtml}</table>`;
}

function renderBlock(
  b: MdBlock,
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  switch (b.kind) {
    case 'break':
      return '';
    case 'text':
      return renderTextBlock(b, rules, bodyDecls, opts);
    case 'list':
      return renderListBlock(b, rules, bodyDecls, opts);
    case 'table':
      return renderTableBlock(b, rules, bodyDecls, opts);
  }
}

/** 把块列表渲染为语义 HTML（预览与内联共用） */
function renderBlocks(
  blocks: MdBlock[],
  rules: CssRule[],
  bodyDecls: Record<string, string>,
  opts: RenderOpts
): string {
  let out = '';
  for (const b of blocks) out += renderBlock(b, rules, bodyDecls, opts);
  return out;
}

// ────────────────────────────────────────────────
// 预览专用：剥离危险标签与事件属性（我们自身不产出这些，仅作防御）
// ────────────────────────────────────────────────
function sanitize(html: string): string {
  let out = html;
  out = out.replace(/<script[\s\S]*?<\/script>/gi, '');
  out = out.replace(/<iframe[\s\S]*?<\/iframe>/gi, '');
  out = out.replace(/<object[\s\S]*?<\/object>/gi, '');
  out = out.replace(/<embed[\s\S]*?\/?>/gi, '');
  out = out.replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '');
  return out;
}

// ────────────────────────────────────────────────
// 对外两路输出
// ────────────────────────────────────────────────

/**
 * 预览用：返回完整 HTML 文档（含 <head><style>）。
 * 标签选择器在 <style> 中正常生效；表格同样内联列宽/固定布局，保证预览与内联观感一致。
 */
export function buildPreviewHtml(markdown: string, css: string): string {
  const { blocks } = parseMarkdown(markdown);
  const rules = parseCss(css);
  const bodyDecls = collectBodyDecls(rules);
  const opts: RenderOpts = { inlineTagStyles: false, bodyFallback: false, tableFallback: false };
  const inner = sanitize(renderBlocks(blocks, rules, bodyDecls, opts));
  return (
    '<!DOCTYPE html>\n' +
    '<html><head>' +
    '<meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    `<style>html,body{margin:0;padding:0;}\n${css}</style>` +
    '</head><body>' +
    inner +
    '</body></html>'
  );
}

/**
 * 粘贴用：返回 100% 内联、零 <head>/<style>/class 的纯净片段。
 * 公众号只认行内 style，粘贴后样式 100% 还原、不丢。
 */
export function buildInlineHtml(markdown: string, css: string): string {
  const { blocks } = parseMarkdown(markdown);
  const rules = parseCss(css);
  const bodyDecls = collectBodyDecls(rules);
  const opts: RenderOpts = { inlineTagStyles: true, bodyFallback: true, tableFallback: true };
  const inner = renderBlocks(blocks, rules, bodyDecls, opts);
  const wrapperStyle = entriesToStyle(bodyDecls);
  const sectionAttr = wrapperStyle === '' ? '' : ` style="${escapeAttr(wrapperStyle)}"`;
  return `<section${sectionAttr}>${inner}</section>`;
}
