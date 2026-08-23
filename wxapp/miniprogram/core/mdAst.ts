/**
 * Markdown 解析子集（行内样式 + 行级识别）。
 * 由 Android 版 MdToGongwen.kt 的解析逻辑移植，供公文转换（gongwen.ts）复用。
 */
import { TextRun, newRun } from './types';

/** 解析行内 `**粗体**` / `*斜体*`，返回样式片段 */
export function parseInline(
  text: string,
  font: string,
  sizePt: number
): TextRun[] {
  const runs: TextRun[] = [];
  let i = 0;
  let buf = '';

  const flush = () => {
    if (buf.length > 0) {
      runs.push(newRun(buf, font, sizePt));
      buf = '';
    }
  };

  while (i < text.length) {
    // 粗体 **xxx**
    if (i + 1 < text.length && text[i] === '*' && text[i + 1] === '*') {
      const end = text.indexOf('**', i + 2);
      if (end > i + 2) {
        flush();
        runs.push(newRun(text.substring(i + 2, end), font, sizePt, { bold: true }));
        i = end + 2;
        continue;
      }
    }
    // 斜体 *xxx*
    if (text[i] === '*') {
      const end = text.indexOf('*', i + 1);
      if (end > i + 1) {
        flush();
        runs.push(newRun(text.substring(i + 1, end), font, sizePt, { italic: true }));
        i = end + 1;
        continue;
      }
    }
    buf += text[i];
    i++;
  }
  flush();
  return runs;
}

export function isTableLine(s: string): boolean {
  const t = s.trim();
  return t.startsWith('|') && t.endsWith('|') && t.length >= 2;
}

export function parseTableRow(s: string): string[] {
  return s
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((x) => x.trim());
}

const SEP_REGEX = /^:?-{1,}:?$/;

const IMG_RE = /!\[[^\]]*]\([^)]*\)/; // 图片
const LINK_RE = /\[([^\]]*)]\([^)]*\)/; // 链接保留文字
const CODE_RE = /`([^`]*)`/; // 行内代码
const STRIKE_RE = /~~([^~]*)~~/; // 删除线
export const ENUM_CN_RE = /^[一二三四五六七八九十百]+[、.．]/; // 中文序号条目
export const ENUM_NUM_RE = /^[（(]?\d+[）)、.．]/; // 数字条目

/** 制表位前导符填空线（扩展语法）：`tab` / `tab.` / `tab-` / `tab@Ncm` */
export const TAB_LINE_RE = /^tab(\.|-|@(\d+(?:\.\d+)?)cm)?\s+(.*)$/;

/** 智能引号：把成对的直引号转成中文弯引号 */
export function applySmartQuotes(line: string): string {
  let out = '';
  let open = true;
  for (const c of line) {
    if (c === '"') {
      out += open ? '\u201C' : '\u201D';
      open = !open;
    } else {
      out += c;
    }
  }
  return out;
}

/** 去除 Markdown 语法噪声：图片、链接、行内代码、删除线 */
export function stripNoise(s: string): string {
  let t = s;
  t = t.replace(IMG_RE, '');
  t = t.replace(LINK_RE, '$1');
  t = t.replace(CODE_RE, '$1');
  t = t.replace(STRIKE_RE, '$1');
  return t;
}

/** 判断是否为称呼语（主送机关 / 受文者），公文规范要求顶格 */
export function isSalutation(text: string): boolean {
  const t = text.trim();
  if (t.length < 2 || t.length > 40) return false;
  if (!t.endsWith('：') && !t.endsWith(':')) return false;
  // 排除以数字/项目符号开头的条目式内容（如「一、事实认定：」属正文标题）
  if (ENUM_CN_RE.test(t)) return false;
  if (ENUM_NUM_RE.test(t)) return false;
  return true;
}

/** 是否为结束语引导词「此致」 */
export function isThisZhi(text: string): boolean {
  return text.trim() === '此致';
}
