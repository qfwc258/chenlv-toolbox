/**
 * Markdown 块解析（TypeScript 自研，替代 commonmark-java）。
 * 由 Android 版 MdAstParser.kt（PPtX）+ MdWechatConverter.kt（公众号）的解析逻辑移植，
 * 作为两个模块共用的解析器：md → 块列表（含行内片段）。
 *
 * 支持：
 *  - YAML FrontMatter（title / theme，供 PPTX 封面）
 *  - ATX 标题 # ~ ######
 *  - 段落（含行内样式：**粗体** *斜体* ~~删除线~~ `行内代码` [链接](url)）
 *  - 有序/无序列表（含嵌套，扁平化并携带缩进层级）
 *  - 引用块 `>`（多行合并，行间以 \n 分隔）
 *  - 围栏代码块 ```（raw 保留原文）
 *  - GFM 管道表格（表头/分隔行/对齐）
 *  - 强制分页 ---
 */

// ────────────────────────────────────────────────
// 块类型
// ────────────────────────────────────────────────
export enum BlockType {
  H1, H2, H3, H4, H5, H6,
  PARAGRAPH,
  BULLET_LIST, ORDERED_LIST,
  QUOTE,
  CODE,
  DIVIDER,
  TABLE
}

/** 表格列对齐 */
export enum TableAlign { LEFT, CENTER, RIGHT }

/** 一段带样式的文本片段 */
export interface InlineFragment {
  text: string;
  bold: boolean;
  italic: boolean;
  strike: boolean;
  link: string | null;
  code: boolean;
}

export function fragment(text: string): InlineFragment {
  return { text, bold: false, italic: false, strike: false, link: null, code: false };
}

/** 文本型块（标题/段落/引用/代码） */
export interface TextBlock {
  kind: 'text';
  type: BlockType;
  fragments: InlineFragment[];
  raw: string;
}
export const textOf = (b: TextBlock) => (b.raw !== '' ? b.raw : b.fragments.map((f) => f.text).join(''));

/** 列表项（携带缩进层级） */
export interface ListItemData {
  fragments: InlineFragment[];
  indent: number;
  number: number | null;
}

/** 列表块 */
export interface ListBlock {
  kind: 'list';
  type: BlockType;
  items: ListItemData[];
  listStart: number;
}

/** 强制分页 */
export interface ForcedBreak {
  kind: 'break';
}

/** 表格块 */
export interface TableBlock {
  kind: 'table';
  header: InlineFragment[][];
  rows: InlineFragment[][][];
  colAlign: TableAlign[];
}

export type MdBlock = TextBlock | ListBlock | ForcedBreak | TableBlock;

export interface ParseResult {
  blocks: MdBlock[];
  coverTitle: string | null;
  coverTheme: string | null;
}

// ────────────────────────────────────────────────
// 行内解析
// ────────────────────────────────────────────────

/**
 * 解析行内样式。文本中若包含 \n（引用块分行），按软换行拆成独立片段。
 * 返回片段列表；空文本返回空列表。
 */
export function parseInlineRaw(text: string): InlineFragment[] {
  const out: InlineFragment[] = [];
  let i = 0;
  const buf: string[] = [];
  const flush = () => {
    if (buf.length > 0) {
      out.push(fragment(buf.join('')));
      buf.length = 0;
    }
  };

  const handleMarkdown = (marker: string, endMarker: string, decorate: (f: InlineFragment) => InlineFragment): boolean => {
    if (text.startsWith(marker, i)) {
      const end = text.indexOf(endMarker, i + marker.length);
      if (end > i + marker.length) {
        flush();
        const inner = parseInlineRaw(text.substring(i + marker.length, end));
        out.push(...inner.map(decorate));
        i = end + endMarker.length;
        return true;
      }
    }
    return false;
  };

  while (i < text.length) {
    // **粗体**
    if (handleMarkdown('**', '**', (f) => ({ ...f, bold: true }))) continue;
    // *斜体* 或 _斜体_（单星号）
    if (handleMarkdown('*', '*', (f) => ({ ...f, italic: true }))) continue;
    if (handleMarkdown('_', '_', (f) => ({ ...f, italic: true }))) continue;
    // ~~删除线~~
    if (handleMarkdown('~~', '~~', (f) => ({ ...f, strike: true }))) continue;
    // `行内代码`
    if (text[i] === '`') {
      const end = text.indexOf('`', i + 1);
      if (end > i + 1) {
        flush();
        out.push({ text: text.substring(i + 1, end), bold: false, italic: false, strike: false, link: null, code: true });
        i = end + 1;
        continue;
      }
    }
    // [文字](链接)
    if (text[i] === '[') {
      const close = text.indexOf(']', i + 1);
      if (close > i + 1 && text[close + 1] === '(') {
        const end = text.indexOf(')', close + 2);
        if (end > close + 2) {
          flush();
          const label = text.substring(i + 1, close);
          const dest = text.substring(close + 2, end).trim();
          out.push(...parseInlineRaw(label).map((f) => ({ ...f, link: dest })));
          i = end + 1;
          continue;
        }
      }
    }
    buf.push(text[i]);
    i++;
  }
  flush();
  return out;
}

// ────────────────────────────────────────────────
// 块解析
// ────────────────────────────────────────────────

const SEP_CELL_RE = /^:?-{1,}:?$/;

interface Line {
  text: string;
  indent: number;
  index: number;
}

function leadingSpaces(s: string): number {
  let n = 0;
  while (n < s.length && s[n] === ' ') n++;
  return n;
}

/** 判断是否为表格分隔行（仅 | - : 空格 组成且含管道与连字符） */
function isTableSepLine(t: string): boolean {
  return (
    t.includes('|') &&
    t.includes('-') &&
    [...t].every((c) => c === '|' || c === '-' || c === ':' || c === ' ')
  );
}

/** 解析管道表格行 → 单元格文本数组 */
function parseTableRow(t: string): string[] {
  let s = t.trim();
  if (s.startsWith('|')) s = s.substring(1);
  if (s.endsWith('|')) s = s.substring(0, s.length - 1);
  return s.split('|').map((x) => x.trim());
}

/** 分离 YAML FrontMatter */
function stripFrontMatter(md: string): { body: string; front: Map<string, string> } {
  const m = /^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?/.exec(md);
  if (!m) return { body: md, front: new Map() };
  const map = new Map<string, string>();
  for (const line of m[1].split('\n')) {
    const idx = line.indexOf(':');
    if (idx > 0) {
      const k = line.substring(0, idx).trim();
      const v = line.substring(idx + 1).trim().replace(/^["']|["']$/g, '');
      if (k) map.set(k, v);
    }
  }
  return { body: md.substring(m[0].length), front: map };
}

/**
 * 解析 Markdown → 块列表。
 */
export function parseMarkdown(markdown: string): ParseResult {
  const { body, front } = stripFrontMatter(markdown);
  const normalized = body.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const rawLines = normalized.split('\n');
  const lines: Line[] = rawLines.map((t, index) => ({
    text: t.replace(/\s+$/, ''),
    indent: leadingSpaces(t),
    index
  }));

  const blocks: MdBlock[] = [];
  let i = 0;
  const total = lines.length;

  while (i < total) {
    const line = lines[i];
    const t = line.text.trim();

    // 空行：跳过
    if (t === '') {
      i++;
      continue;
    }

    // 围栏代码块
    const fenceMatch = /^(```+|~~~+)\s*(.*)$/.exec(t);
    if (fenceMatch) {
      const fence = fenceMatch[1];
      const rawLines_: string[] = [];
      i++;
      while (i < total) {
        const cur = lines[i].text.trim();
        if (cur.startsWith(fence[0]) && fence.length >= 3 && /^`{3,}|~{3,}$/.test(cur)) break;
        rawLines_.push(lines[i].text);
        i++;
      }
      i++; // 跳过闭合围栏
      blocks.push({ kind: 'text', type: BlockType.CODE, fragments: [], raw: rawLines_.join('\n') });
      continue;
    }

    // 强制分页 / 水平线：--- *** ___
    if (t === '---' || t === '***' || t === '___') {
      blocks.push({ kind: 'break' });
      i++;
      continue;
    }

    // ATX 标题
    const headingMatch = /^(#{1,6})\s+(.*)$/.exec(t);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const type =
        level === 1 ? BlockType.H1 : level === 2 ? BlockType.H2 : level === 3 ? BlockType.H3 : level === 4 ? BlockType.H4 : level === 5 ? BlockType.H5 : BlockType.H6;
      blocks.push({
        kind: 'text',
        type,
        fragments: parseInlineRaw(headingMatch[2].trim().replace(/#+\s*$/, '').trim()),
        raw: ''
      });
      i++;
      continue;
    }

    // 表格：本行含 | 且下一行是分隔行
    if (t.includes('|') && i + 1 < total && isTableSepLine(lines[i + 1].text.trim())) {
      const headerCells = parseTableRow(t);
      const sepCells = parseTableRow(lines[i + 1].text);
      const colAlign = sepCells.map((c) => {
        const l = c.startsWith(':');
        const r = c.endsWith(':');
        if (l && r) return TableAlign.CENTER;
        if (r) return TableAlign.RIGHT;
        return TableAlign.LEFT;
      });
      const rows: InlineFragment[][][] = [];
      i += 2;
      while (i < total) {
        const ct = lines[i].text.trim();
        if (!ct.includes('|')) break;
        rows.push(parseTableRow(ct).map((cell) => parseInlineRaw(cell)));
        i++;
      }
      blocks.push({
        kind: 'table',
        header: headerCells.map((cell) => parseInlineRaw(cell)),
        rows,
        colAlign
      });
      continue;
    }

    // 引用块：> 开头，连续多行合并
    if (t.startsWith('>')) {
      const quoteLines: string[] = [];
      while (i < total) {
        const qt = lines[i].text.trim();
        if (!qt.startsWith('>')) break;
        quoteLines.push(qt.replace(/^>\s?/, ''));
        i++;
      }
      // 多行合并：行间以 \n 分隔（commonmark BlockQuote > Paragraph > SoftLineBreak）
      const merged = quoteLines.join('\n');
      blocks.push({ kind: 'text', type: BlockType.QUOTE, fragments: parseInlineRaw(merged), raw: '' });
      continue;
    }

    // 列表：无序 - * + 或 有序 1.
    const ulMatch = /^([-*+])\s+(.*)$/.exec(t);
    const olMatch = /^(\d+)[.)]\s+(.*)$/.exec(t);
    if (ulMatch || olMatch) {
      const ordered = !!olMatch;
      const type = ordered ? BlockType.ORDERED_LIST : BlockType.BULLET_LIST;
      const items: ListItemData[] = [];
      // 记录有序列表起始编号
      let orderedStart = olMatch ? parseInt(olMatch[1], 10) : null;
      // 解析列表块：以当前缩进为基准，支持嵌套（更深缩进 = 子列表）
      const baseIndent = line.indent;
      const pending: Array<{ indent: number; ordered: boolean; number: number | null; text: string }> = [];

      while (i < total) {
        const cl = lines[i];
        const ct2 = cl.text.trim();
        if (ct2 === '') break;
        const cul = /^([-*+])\s+(.*)$/.exec(ct2);
        const col2 = /^(\d+)[.)]\s+(.*)$/.exec(ct2);
        if (cul || col2) {
          pending.push({
            indent: cl.indent,
            ordered: !!col2,
            number: col2 ? parseInt(col2[1], 10) : null,
            text: col2 ? col2[2] : cul![2]
          });
          i++;
        } else {
          // 列表内非列表行（段落续行）→ 追加到上一个 item
          if (pending.length > 0) {
            const last = pending[pending.length - 1];
            last.text += '\n' + cl.text.trim();
            i++;
          } else {
            break;
          }
        }
      }

      // 扁平化：按缩进层级生成 items（嵌套深度 = (indent - baseIndent) / 2，至少 0）
      let currentNumber = orderedStart;
      for (let k = 0; k < pending.length; k++) {
        const p = pending[k];
        // 嵌套深度按缩进差计算（每 2 空格一层）
        let depth = 0;
        if (p.indent > baseIndent) {
          depth = Math.max(1, Math.floor((p.indent - baseIndent) / 2));
        }
        // 编号：仅顶层有序列表用 MD 原文编号
        let num: number | null = null;
        if (p.ordered && depth === 0) {
          num = p.number;
          currentNumber = p.number;
        }
        items.push({
          fragments: parseInlineRaw(p.text),
          indent: depth,
          number: num
        });
        // 简化：续行（含 \n）合并进同一片段
      }
      if (items.length > 0) {
        blocks.push({ kind: 'list', type, items, listStart: 0 });
      }
      continue;
    }

    // 缩进代码块（4 空格或 tab）：连续缩进行
    if (line.indent >= 4 || t.startsWith('\t')) {
      const codeLines: string[] = [];
      while (i < total) {
        const cl = lines[i];
        if (cl.text.trim() === '') {
          codeLines.push('');
          i++;
          continue;
        }
        if (cl.indent < 4) break;
        codeLines.push(cl.text.replace(/^ {1,4}/, ''));
        i++;
      }
      // 去掉末尾空行
      while (codeLines.length > 0 && codeLines[codeLines.length - 1] === '') codeLines.pop();
      blocks.push({ kind: 'text', type: BlockType.CODE, fragments: [], raw: codeLines.join('\n') });
      continue;
    }

    // 普通段落：收集直到空行或块级起始
    const paraLines: string[] = [t];
    i++;
    while (i < total) {
      const ct3 = lines[i].text.trim();
      if (ct3 === '') break;
      if (/^(#{1,6})\s+/.test(ct3)) break;
      if (ct3 === '---' || ct3 === '***' || ct3 === '___') break;
      if (ct3.startsWith('>')) break;
      if (/^([-*+])\s+/.test(ct3)) break;
      if (/^(\d+)[.)]\s+/.test(ct3)) break;
      if (/^(```+|~~~+)/.test(ct3)) break;
      paraLines.push(ct3);
      i++;
    }
    const paraText = paraLines.join('\n');
    blocks.push({ kind: 'text', type: BlockType.PARAGRAPH, fragments: parseInlineRaw(paraText), raw: '' });
  }

  return {
    blocks,
    coverTitle: front.get('title') ?? null,
    coverTheme: front.get('theme') ?? null
  };
}

/** 从块列表提取所有 H1/H2 标题文本（目录自动生成用） */
export function extractHeadings(blocks: MdBlock[]): TextBlock[] {
  return blocks.filter(
    (b): b is TextBlock =>
      b.kind === 'text' && (b.type === BlockType.H1 || b.type === BlockType.H2)
  );
}
