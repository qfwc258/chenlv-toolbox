/**
 * 轻量 CSS 解析器（公众号式样式编辑器原型）。由 Android 版 PptCssParser.kt 移植。
 *
 * 仅支持「子集」语法，足以覆盖行距 / 段距 / 颜色 / 字体 / 字号 / 画布 的调整：
 *
 *   *        全局（行距、默认字体）
 *   h1 h2 h3 各级标题（字号、颜色、段后距）
 *   p .paragraph 正文（字号、颜色、段后距）
 *   .quote / blockquote  引用（字号、颜色、段前距 margin-top，二者等价）
 *   .code    代码块（字号、字体、背景）
 *   .accent  主题强调色（accent / 引用条）
 *   .cover   封面底色
 *   .slide   画布与边距
 *
 * 支持属性：font-size( pt|px|em )、color、line-height、margin、margin-top、
 *           margin-bottom、font-family、background、width、height。
 * 其中 .quote / blockquote 的 margin-top 表示「引用块与上方文本的段前距」，
 * 对应 PptStyleSheet.quoteGapBefore。
 * 单位：pt 原样；px 按 0.75 折算为 pt（96dpi）；em 按 16 折算。
 *
 * 解析结果除返回 PptStyleSheet 外，还会在 PptStyleSheet.overrides 中记录
 * 「被 CSS 显式声明的字段名」——UI 层据此实现「仅当用户写了某颜色字段，才覆盖主题配色」。
 *
 * 运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node 专有 API。
 */
import { PptStyleSheet } from './pptxStyleSheet';

const DEFAULT_LATIN = 'Arial';
const DEFAULT_EA = '微软雅黑';

const KNOWN_CJK = new Set([
  'simsun', 'simhei', 'yahei', 'microsoft yahei', 'microsoft yahei ui', 'microsoft jhenghei',
  'source han sans', 'source han sans sc', 'source han sans cn', 'noto sans cjk',
  'noto sans cjk sc', 'noto sans cjk jp', 'noto sans sc', 'pingfang', 'pingfang sc',
  'stsong', 'stkaiti', 'stheiti', 'heiti', 'songti', 'kaiti', 'fangsong',
  'ms song', 'ms mincho', 'himalaya', 'fzshusong', 'fzyaoti',
]);

const NAMED: Record<string, string> = {
  black: '000000', white: 'FFFFFF', red: 'FF0000',
  green: '008000', blue: '0000FF', gray: '808080',
  grey: '808080', orange: 'FFA500', yellow: 'FFFF00',
  purple: '800080', navy: '000080', teal: '008080',
};

/** 解析 CSS 文本，返回叠加在 [base] 之上的新样式表（含被覆盖字段集合）。 */
export function parseCss(css: string, base: PptStyleSheet = new PptStyleSheet()): PptStyleSheet {
  // 0) 去除 CSS 注释 /* ... */（避免注释中的示例规则被误解析）
  const cleaned = css.replace(/\/\*[\s\S]*?\*\//g, '');
  // 1) 提取规则：selector { body }
  const rules = new Map<string, Record<string, string>>();
  const ruleRegex = /([^{}]+)\s*\{([^}]*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = ruleRegex.exec(cleaned)) !== null) {
    const selectors = m[1]
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    const props = parseDeclarations(m[2]);
    for (const sel of selectors) {
      const bucket = rules.get(sel) ?? {};
      Object.assign(bucket, props);
      rules.set(sel, bucket);
    }
  }

  // 2) 按优先级顺序叠加：* < 具体选择器
  const ov = new Set<string>();
  let s = base;
  const r = (sel: string): Record<string, string> | undefined => rules.get(sel);
  if (r('*')) s = applyUniversal(s, r('*')!, ov);
  if (r('h1')) s = applyHeading(s, r('h1')!, 1, ov);
  if (r('h2')) s = applyHeading(s, r('h2')!, 2, ov);
  if (r('h3')) s = applyHeading(s, r('h3')!, 3, ov);
  if (r('p')) s = applyBody(s, r('p')!, ov);
  if (r('.paragraph')) s = applyBody(s, r('.paragraph')!, ov);
  if (r('.quote')) s = applyQuote(s, r('.quote')!, ov);
  if (r('blockquote')) s = applyQuote(s, r('blockquote')!, ov);
  if (r('.code')) s = applyCode(s, r('.code')!, ov);
  if (r('.accent')) s = applyAccent(s, r('.accent')!, ov);
  if (r('.cover')) s = applyCover(s, r('.cover')!, ov);
  if (r('.slide')) s = applySlide(s, r('.slide')!, ov);
  return s.copy({ overrides: ov });
}

function parseDeclarations(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const decl of body.split(';')) {
    const idx = decl.indexOf(':');
    if (idx >= 0) {
      const k = decl.substring(0, idx).trim().toLowerCase();
      const v = decl.substring(idx + 1).trim();
      if (k.length > 0) out[k] = v;
    }
  }
  return out;
}

// ── 各选择器映射 ──

function applyUniversal(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['line-height'] !== undefined) {
    // 行距是倍数（如 1.5），不是 pt：按原样解析，不做取整（对齐 Kotlin toDoubleOrNull）
    const v = parseFloat(p['line-height']);
    if (!isNaN(v)) {
      r = r.copy({ lineMult: v });
      ov.add('lineMult');
    }
  }
  if (p['font-family'] !== undefined) {
    const [latin, ea] = splitFontFamily(p['font-family']);
    r = r.copy({ latinFont: latin, bodyFont: ea, titleFont: ea });
    ov.add('latinFont'); ov.add('bodyFont'); ov.add('titleFont');
  }
  return r;
}

function applyHeading(s: PptStyleSheet, p: Record<string, string>, level: number, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['font-size'] !== undefined) {
    const size = parsePt(p['font-size']);
    switch (level) {
      case 1: r = r.copy({ fsH1: size }); break;
      case 2: r = r.copy({ fsH2: size }); break;
      case 3: r = r.copy({ fsH3: size }); break;
      case 4: r = r.copy({ fsH4: size }); break;
      case 5: r = r.copy({ fsH5: size }); break;
      default: r = r.copy({ fsH6: size }); break;
    }
    ov.add('fsH' + level);
  }
  if (p['color'] !== undefined) {
    r = r.copy({ titleColor: parseColor(p['color']) });
    ov.add('titleColor');
  }
  if (p['margin-bottom'] !== undefined) {
    r = r.copy({ headGap: parsePt(p['margin-bottom']) });
    ov.add('headGap');
  }
  if (p['font-family'] !== undefined) {
    const [latin, ea] = splitFontFamily(p['font-family']);
    r = r.copy({ titleFont: ea, latinFont: latin });
    ov.add('titleFont'); ov.add('latinFont');
  }
  return r;
}

function applyBody(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['font-size'] !== undefined) {
    r = r.copy({ fsBody: parsePt(p['font-size']) });
    ov.add('fsBody');
  }
  if (p['color'] !== undefined) {
    r = r.copy({ bodyColor: parseColor(p['color']) });
    ov.add('bodyColor');
  }
  if (p['margin-bottom'] !== undefined) {
    r = r.copy({ paraGap: parsePt(p['margin-bottom']) });
    ov.add('paraGap');
  }
  if (p['font-family'] !== undefined) {
    const [latin, ea] = splitFontFamily(p['font-family']);
    r = r.copy({ bodyFont: ea, latinFont: latin });
    ov.add('bodyFont'); ov.add('latinFont');
  }
  return r;
}

function applyQuote(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['font-size'] !== undefined) {
    r = r.copy({ fsQuote: parsePt(p['font-size']) });
    ov.add('fsQuote');
  }
  if (p['color'] !== undefined) {
    r = r.copy({ quoteBg: parseColor(p['color']) });
    ov.add('quoteBg');
  }
  // 引用块（md >）与上方文本的段前距：对应 PptStyleSheet.quoteGapBefore
  if (p['margin-top'] !== undefined) {
    r = r.copy({ quoteGapBefore: parsePt(p['margin-top']) });
    ov.add('quoteGapBefore');
  }
  return r;
}

function applyCode(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['font-size'] !== undefined) {
    r = r.copy({ fsCode: parsePt(p['font-size']) });
    ov.add('fsCode');
  }
  if (p['font-family'] !== undefined) {
    const [latin, ea] = splitFontFamily(p['font-family']);
    // 代码块拉丁槽由 runXml 固定 Consolas（等宽）；codeFont 作为东亚槽，
    // 优先取用户指定的东亚字体，否则取 Latin 字体名（如 Consolas，CJK 回落可接受）。
    r = r.copy({ codeFont: ea === DEFAULT_EA ? latin : ea });
    ov.add('codeFont');
  }
  if (p['background'] !== undefined) {
    r = r.copy({ codeBg: parseColor(p['background']) });
    ov.add('codeBg');
  }
  return r;
}

function applyAccent(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['color'] !== undefined) {
    const c = parseColor(p['color']);
    r = r.copy({ accent: c, quoteBg: c });
    ov.add('accent'); ov.add('quoteBg');
  }
  return r;
}

function applyCover(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  // 仅影响封面底色；不改全局标题色，避免误伤内容页标题
  if (p['background'] !== undefined) {
    r = r.copy({ coverBg: parseColor(p['background']) });
    ov.add('coverBg');
  }
  return r;
}

function applySlide(s: PptStyleSheet, p: Record<string, string>, ov: Set<string>): PptStyleSheet {
  let r = s;
  if (p['width'] !== undefined) {
    r = r.copy({ canvasW: parsePt(p['width']) });
    ov.add('canvasW');
  }
  if (p['height'] !== undefined) {
    r = r.copy({ canvasH: parsePt(p['height']) });
    ov.add('canvasH');
  }
  // margin: 40pt 30pt  →  上下 40 / 左右 30；margin: 40pt → 四边
  if (p['margin'] !== undefined) {
    const nums = p['margin'].split(/\s+/).map(parsePtOrNull).filter((x): x is number => x !== null);
    if (nums.length === 1) {
      r = r.copy({ marginX: nums[0], marginTop: nums[0], marginBottom: nums[0] });
      ov.add('marginX'); ov.add('marginTop'); ov.add('marginBottom');
    } else if (nums.length === 2) {
      r = r.copy({ marginTop: nums[0], marginBottom: nums[0], marginX: nums[1] });
      ov.add('marginTop'); ov.add('marginBottom'); ov.add('marginX');
    }
  }
  if (p['margin-left'] !== undefined) {
    r = r.copy({ marginX: parsePt(p['margin-left']) });
    ov.add('marginX');
  }
  if (p['margin-top'] !== undefined) {
    r = r.copy({ marginTop: parsePt(p['margin-top']) });
    ov.add('marginTop');
  }
  if (p['margin-bottom'] !== undefined) {
    r = r.copy({ marginBottom: parsePt(p['margin-bottom']) });
    ov.add('marginBottom');
  }
  return r;
}

// ── 基础解析助手 ──

/** "28pt" → 28；"16px" → 12（×0.75）；"1em" → 16；非法 → 0 */
function parsePt(v: string): number {
  return parsePtOrNull(v) ?? 0;
}

function parsePtOrNull(v: string): number | null {
  const t = v.trim().toLowerCase();
  if (t.endsWith('pt')) {
    const d = parseFloat(t.substring(0, t.length - 2));
    return isNaN(d) ? null : Math.round(d);
  }
  if (t.endsWith('px')) {
    const d = parseFloat(t.substring(0, t.length - 2));
    return isNaN(d) ? null : Math.round(d * 0.75);
  }
  if (t.endsWith('em')) {
    const d = parseFloat(t.substring(0, t.length - 2));
    return isNaN(d) ? null : Math.round(d * 16);
  }
  const d = parseFloat(t);
  return isNaN(d) ? null : Math.round(d);
}

/** "#RGB" / "#RRGGBB" / 颜色名 → "RRGGBB"（大写、不带 #）。非法 → 原样去除 #。 */
function parseColor(v: string): string {
  const t = v.trim().replace(/^#/, '').toLowerCase();
  if (/^[0-9a-f]{6}$/.test(t)) return t.toUpperCase();
  if (/^[0-9a-f]{3}$/.test(t)) {
    return t
      .split('')
      .map((c) => c + c)
      .join('')
      .toUpperCase();
  }
  const named = NAMED[t];
  if (named !== undefined) return named;
  return v.trim().replace(/^#/, '').toUpperCase();
}

function stripQuotes(v: string): string {
  return v
    .trim()
    .replace(/^"/, '')
    .replace(/"$/, '')
    .replace(/^'/, '')
    .replace(/'$/, '')
    .trim();
}

/**
 * 将 CSS font-family（可能是一串逗号分隔、中英文混排）拆成 (拉丁字体, 东亚字体) 两路。
 * 规则：
 * - 含 CJK 字符、或命中常见中文/日文字体英文名的 token → 候选东亚字体；
 * - 其余（Arial / Consolas / Helvetica 等，及通用关键字 sans-serif 之外）→ 候选拉丁字体；
 * - 通用关键字 sans-serif / serif / monospace 等不是真实字体名，跳过；
 * - 某路缺失时回落到默认（拉丁 Arial、东亚 微软雅黑），二者均为无衬线。
 */
function splitFontFamily(value: string): [string, string] {
  const generic = new Set(['sans-serif', 'serif', 'monospace', 'cursive', 'fantasy']);
  const tokens = value
    .split(',')
    .map(stripQuotes)
    .filter((t) => t.length > 0);
  const cjk: string[] = [];
  const latin: string[] = [];
  for (const t of tokens) {
    const low = t.toLowerCase();
    if (generic.has(low)) continue;
    const hasCjk = [...t].some((c) => {
      const code = c.codePointAt(0) ?? 0;
      return code >= 0x3000 && code <= 0x9fff;
    });
    if (hasCjk || KNOWN_CJK.has(low)) cjk.push(t);
    else latin.push(t);
  }
  const ea = cjk[0] ?? DEFAULT_EA;
  const lat = latin[0] ?? DEFAULT_LATIN;
  return [lat, ea];
}
