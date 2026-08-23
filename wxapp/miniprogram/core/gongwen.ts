/**
 * Markdown → 法律文书排版（参照党政机关公文 GB/T 9704 格式，无红头）。
 * 由 Android 版 MdToGongwen.kt 移植。
 *
 * 排版规则：
 *  - `#` 一级标题：小标宋体 二号(22pt) 居中，后加空行
 *  - `##` 二级标题：黑体 三号，首行缩进 2 字符
 *  - `###` 三级标题：楷体_GB2312 三号，首行缩进 2 字符
 *  - `####` 四级标题：仿宋_GB2312 三号，首行缩进 2 字符
 *  - 正文：仿宋_GB2312 三号，首行缩进 32pt，固定行距 28pt
 *  - `ll ` 前缀：顶格；`rr ` 前缀：右对齐（落款）
 *  - `tab` / `tab.` / `tab-` / `tab@Ncm`：制表位前导符填空线
 *  - 表格：三线/全框线，单元格不缩进
 */
import { TextRun, ParaProps, PageSetup, GovDoc, newRun } from './types';
import { DocxWriter } from './docxWriter';
import {
  parseInline,
  stripNoise,
  applySmartQuotes,
  isTableLine,
  parseTableRow,
  isSalutation,
  isThisZhi,
  TAB_LINE_RE,
  ENUM_CN_RE,
  ENUM_NUM_RE,
} from './mdAst';

export interface GovDocSpec {
  specName: string;
  mainTitleFont: string;
  mainTitleSizePt: number;
  level2TitleFont: string;
  level2TitleSizePt: number;
  level3TitleFont: string;
  level3TitleSizePt: number;
  bodyFont: string;
  bodySizePt: number;
  lineSpacingPt: number;
  indentPt: number;
  page: PageSetup;
  justify?: boolean;
  autoSalutation?: boolean;
  smartQuotes?: boolean;
  pageNumber?: boolean;
}

/** 国标通用公文规范 */
export const GB_STANDARD: GovDocSpec = {
  specName: '国标通用',
  mainTitleFont: '方正小标宋简体',
  mainTitleSizePt: 22.0,
  level2TitleFont: '黑体',
  level2TitleSizePt: 16.0,
  level3TitleFont: '楷体_GB2312',
  level3TitleSizePt: 16.0,
  bodyFont: '仿宋_GB2312',
  bodySizePt: 16.0,
  lineSpacingPt: 28.0,
  indentPt: 32.0,
  page: { widthCm: 21, heightCm: 29.7, topCm: 3.0, bottomCm: 2.8, leftCm: 2.5, rightCm: 2.5 },
};

/** 法院诉讼文书专用规范（默认） */
export const COURT_DOC: GovDocSpec = {
  specName: '诉讼文书',
  mainTitleFont: '小标宋体',
  mainTitleSizePt: 22.0,
  level2TitleFont: '黑体',
  level2TitleSizePt: 16.0,
  level3TitleFont: '楷体_GB2312',
  level3TitleSizePt: 16.0,
  bodyFont: '仿宋_GB2312',
  bodySizePt: 14.0,
  lineSpacingPt: 25.0,
  indentPt: 28.0,
  page: { widthCm: 21, heightCm: 29.7, topCm: 3.0, bottomCm: 2.8, leftCm: 2.5, rightCm: 2.5 },
};

/** 行政机关公文规范 */
export const GOV_OFFICIAL: GovDocSpec = {
  specName: '行政机关',
  mainTitleFont: '方正小标宋简体',
  mainTitleSizePt: 22.0,
  level2TitleFont: '黑体',
  level2TitleSizePt: 16.0,
  level3TitleFont: '楷体_GB2312',
  level3TitleSizePt: 16.0,
  bodyFont: '仿宋_GB2312',
  bodySizePt: 16.0,
  lineSpacingPt: 30.0,
  indentPt: 32.0,
  page: { widthCm: 21, heightCm: 29.7, topCm: 3.7, bottomCm: 3.5, leftCm: 2.8, rightCm: 2.5 },
};

export const ALL_PRESETS: GovDocSpec[] = [COURT_DOC, GB_STANDARD, GOV_OFFICIAL];

/** 默认规范：诉讼文书 */
export const DEFAULT_SPEC: GovDocSpec = COURT_DOC;

/** 按名称回查规范；未知名称回退到默认 */
export function specByName(name: string | null | undefined): GovDocSpec {
  return ALL_PRESETS.find((s) => s.specName === name) ?? DEFAULT_SPEC;
}

export interface GongwenOptions {
  spec: GovDocSpec;
  mainTitleFont: string;
  bodyFont: string;
  mainTitleSizePt: number;
  bodySizePt: number;
  lineSpacingPt: number;
  indentPt: number;
  smartQuotes: boolean;
  justify: boolean;
  autoSalutation: boolean;
  pageNumber: boolean;
}

export function defaultOptions(): GongwenOptions {
  const spec = DEFAULT_SPEC;
  return {
    spec,
    mainTitleFont: spec.mainTitleFont,
    bodyFont: spec.bodyFont,
    mainTitleSizePt: spec.mainTitleSizePt,
    bodySizePt: spec.bodySizePt,
    lineSpacingPt: spec.lineSpacingPt,
    indentPt: spec.indentPt,
    smartQuotes: spec.smartQuotes ?? true,
    justify: spec.justify ?? true,
    autoSalutation: spec.autoSalutation ?? true,
    pageNumber: spec.pageNumber ?? false,
  };
}

const SEP_REGEX = /^:?-{1,}:?$/;

/** 执行转换：Markdown → 公文文档模型（不含 docx 打包，docx 由 toDocxBytes 完成） */
export function convert(markdown: string, options: GongwenOptions = defaultOptions()): GovDoc {
  const bodyFont = options.bodyFont;
  const bodySize = options.bodySizePt;
  const indent = options.indentPt;
  const spacing = options.lineSpacingPt;
  const spec = options.spec;

  // ---------- 预处理：清洗行 ----------
  const cleanLines: string[] = [];
  let inCodeFence = false;
  const normalized = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  for (const rawLine of normalized.split('\n')) {
    let line = rawLine.replace(/^\s+/, '').replace(/\n+$/, '');
    // 跳过代码围栏，围栏内容按普通正文输出
    if (line.replace(/^\s+/, '').startsWith('```')) {
      inCodeFence = !inCodeFence;
      continue;
    }
    if (options.smartQuotes && !inCodeFence) line = applySmartQuotes(line);
    if (!inCodeFence) line = stripNoise(line);
    if (line === '') continue;
    cleanLines.push(line);
  }

  const page = spec.page;
  const doc = new DocxWriter({
    page,
    defaultFont: bodyFont,
    defaultSizePt: bodySize,
    defaultIndentPt: indent,
    defaultLineSpacingPt: spacing,
    pageNumber: options.pageNumber,
  });

  // 公文正文两端对齐
  const bodyAlign: 'both' | 'left' = options.justify ? 'both' : 'left';
  const bodyProps: ParaProps = {
    align: bodyAlign,
    firstLineIndentPt: indent,
    lineSpacingPt: spacing,
  };
  const noIndentProps: ParaProps = { align: 'left', firstLineIndentPt: 0.0, lineSpacingPt: spacing };

  let bodyStarted = false;
  let i = 0;
  const total = cleanLines.length;

  while (i < total) {
    const text = cleanLines[i];

    // 水平线
    const trimmed = text.trim();
    if (trimmed === '---' || trimmed === '***' || trimmed === '___') {
      doc.addEmptyParagraph(bodyProps);
      i++;
      continue;
    }

    // 表格
    if (isTableLine(text)) {
      const tableRows: string[] = [];
      while (i < total && isTableLine(cleanLines[i])) {
        tableRows.push(cleanLines[i].trim());
        i++;
      }
      const realRows: TextRun[][][] = [];
      for (const r of tableRows) {
        const cells = parseTableRow(r);
        // 过滤 |---|---| 对齐分隔行
        const isSep = cells.length > 0 && cells.every((c) => SEP_REGEX.test(c));
        if (!isSep) {
          realRows.push(cells.map((c) => parseInline(c, bodyFont, bodySize)));
        }
      }
      if (realRows.length > 0) doc.addTable(realRows);
      continue;
    }

    // `rr ` 前缀 -> 强制右对齐（落款）
    if (text.startsWith('rr ')) {
      doc.addParagraph(parseInline(text.substring(3), bodyFont, bodySize), {
        align: 'right',
        firstLineIndentPt: 0.0,
        lineSpacingPt: spacing,
      });
      bodyStarted = true;
      i++;
      continue;
    }

    // `ll ` 前缀 -> 强制顶格
    if (text.startsWith('ll ')) {
      doc.addParagraph(parseInline(text.substring(3), bodyFont, bodySize), noIndentProps);
      bodyStarted = true;
      i++;
      continue;
    }

    // 制表位前导符填空线：`tab` / `tab.` / `tab-` / `tab@Ncm`
    const tabMatch = text.match(TAB_LINE_RE);
    if (tabMatch) {
      const raw = tabMatch[1] ?? ''; // "." / "-" / "@Ncm" / ""
      const leader = raw === '.' ? 'dot' : raw === '-' ? 'dash' : 'underline';
      const usableCm = page.widthCm - page.leftCm - page.rightCm;
      const posCm = tabMatch[2] ? parseFloat(tabMatch[2]) : usableCm;
      const content = tabMatch[3] ?? '';
      const parts = content.split('::', 2);
      const left = parts[0] ?? '';
      const right = parts[1] ?? '';
      const tabRun: TextRun = newRun('\t', bodyFont, bodySize);
      const runs: TextRun[] = right !== ''
        ? [...parseInline(left, bodyFont, bodySize), tabRun, ...parseInline(right, bodyFont, bodySize)]
        : [...parseInline(left, bodyFont, bodySize), tabRun];
      doc.addParagraph(runs, {
        align: 'left',
        firstLineIndentPt: 0.0,
        lineSpacingPt: spacing,
        tabs: [{ posPt: Math.round((posCm * 566.929) / 20.0), align: 'right', leader }],
      });
      bodyStarted = true;
      i++;
      continue;
    }

    // 称呼语（主送机关）自动顶格：仅在正文开始之前生效
    if (options.autoSalutation && !bodyStarted && !text.startsWith('#') && isSalutation(text)) {
      doc.addParagraph(parseInline(text, bodyFont, bodySize), noIndentProps);
      i++;
      continue;
    }

    // 结束语「此致」：缩进两字，其下一行顶格
    if (isThisZhi(text)) {
      doc.addParagraph(parseInline(text.trim(), bodyFont, bodySize), {
        align: 'left',
        firstLineIndentPt: indent,
        lineSpacingPt: spacing,
      });
      if (i + 1 < total) {
        const next = cleanLines[i + 1];
        doc.addParagraph(parseInline(next, bodyFont, bodySize), noIndentProps);
        i += 2;
        continue;
      }
      i++;
      continue;
    }

    if (text.startsWith('# ')) {
      doc.addParagraph(
        parseInline(text.replace(/^# /, '').trim(), options.mainTitleFont, options.mainTitleSizePt),
        { align: 'center', firstLineIndentPt: 0.0, lineSpacingPt: spacing }
      );
      doc.addEmptyParagraph(bodyProps);
    } else if (text.startsWith('## ')) {
      doc.addParagraph(
        parseInline(text.replace(/^## /, '').trim(), spec.level2TitleFont, bodySize),
        { ...bodyProps, align: 'left' }
      );
      bodyStarted = true;
    } else if (text.startsWith('### ')) {
      doc.addParagraph(
        parseInline(text.replace(/^### /, '').trim(), spec.level3TitleFont, bodySize),
        { ...bodyProps, align: 'left' }
      );
      bodyStarted = true;
    } else if (text.startsWith('#### ')) {
      doc.addParagraph(
        parseInline(text.replace(/^#### /, '').trim(), bodyFont, bodySize),
        { ...bodyProps, align: 'left' }
      );
      bodyStarted = true;
    } else if (text.startsWith('> ')) {
      doc.addParagraph(
        parseInline(text.replace(/^> /, '').trim(), '楷体_GB2312', bodySize),
        bodyProps
      );
      bodyStarted = true;
    } else {
      doc.addParagraph(parseInline(text, bodyFont, bodySize), bodyProps);
      bodyStarted = true;
    }
    i++;
  }

  // 空文档兜底
  if (total === 0) doc.addEmptyParagraph(bodyProps);

  const firstTitle = cleanLines.find((l) => l.startsWith('# '));
  const title = firstTitle ? firstTitle.replace(/^# /, '').trim() : '公文';

  return {
    blocks: doc.blocks,
    page,
    title,
    mainTitleFont: options.mainTitleFont,
    bodyFont: options.bodyFont,
    bodySizePt: options.bodySizePt,
    lineSpacingPt: options.lineSpacingPt,
    indentPt: options.indentPt,
    pageNumber: options.pageNumber,
  };
}

/** 便捷入口：Markdown → docx 字节流 */
export function mdToDocx(markdown: string, options: GongwenOptions = defaultOptions()): Uint8Array {
  const doc = convert(markdown, options);
  const w = new DocxWriter({
    page: doc.page,
    defaultFont: doc.bodyFont,
    defaultSizePt: doc.bodySizePt,
    defaultIndentPt: doc.indentPt,
    defaultLineSpacingPt: doc.lineSpacingPt,
    pageNumber: doc.pageNumber ?? false,
  });
  for (const b of doc.blocks) {
    if (b.kind === 'para') w.addParagraph(b.runs, b.props);
    else w.addTable(b.rows);
  }
  return w.build(doc.title);
}
