/**
 * 公文文档模型（与 docx / PDF / 预览解耦）。
 * 由 Android 版 GovDoc.kt / DocxWriter.kt 移植而来。
 */

/** 对齐方式（OOXML w:jc） */
export type Align = 'left' | 'center' | 'right' | 'both';

/** 一段文字中的一个样式片段 */
export interface TextRun {
  text: string;
  font: string;
  sizePt: number;
  bold?: boolean;
  italic?: boolean;
  /** 下划线：Word 中带下划线的文字，打开/编辑/保存后仍需保留 */
  underline?: boolean;
  /** 下划线线型：single/double/dash/dotted/wave/...，缺省单线 */
  underlineStyle?: string | null;
  /** 删除线 */
  strike?: boolean;
  /** 文字颜色（CSS 十六进制，如 #FF0000）；null 表示默认黑（auto） */
  color?: string | null;
  /** 高亮：Word 高亮颜色名（如 yellow）；null 表示无 */
  highlight?: string | null;
  /** 上下标："superscript" / "subscript"；null 表示正常 */
  vertAlign?: string | null;
  /** 字符边框：公文中「【】式」强调、印章占位框 */
  border?: ParaBorder | null;
}

export function newRun(
  text: string,
  font: string,
  sizePt: number,
  extra: Partial<TextRun> = {}
): TextRun {
  return { text, font, sizePt, ...extra };
}

/** 段落边框的单条边 */
export interface ParaBorder {
  value?: string;
  szPt?: number;
  color?: string;
}

export function newParaBorder(
  value = 'single',
  szPt = 1.0,
  color = '#000000'
): ParaBorder {
  return { value, szPt, color };
}

/** 段落四边边框；某边为 null 表示无边框 */
export interface ParaBorders {
  top?: ParaBorder | null;
  bottom?: ParaBorder | null;
  left?: ParaBorder | null;
  right?: ParaBorder | null;
}

/** 段落中的制表位（含前导符） */
export interface TabStop {
  posPt: number;
  align?: string;
  leader?: string;
}

export function newTabStop(
  posPt: number,
  align = 'left',
  leader = 'none'
): TabStop {
  return { posPt, align, leader };
}

/** 段落属性 */
export interface ParaProps {
  align?: Align;
  firstLineIndentPt?: number;
  lineSpacingPt?: number;
  spaceBeforePt?: number;
  spaceAfterPt?: number;
  borders?: ParaBorders | null;
  tabs?: TabStop[];
}

export function newParaProps(props: ParaProps = {}): ParaProps {
  return {
    align: props.align ?? 'left',
    firstLineIndentPt: props.firstLineIndentPt ?? 0.0,
    lineSpacingPt: props.lineSpacingPt ?? 28.0,
    spaceBeforePt: props.spaceBeforePt ?? 0.0,
    spaceAfterPt: props.spaceAfterPt ?? 0.0,
    borders: props.borders ?? null,
    tabs: props.tabs ?? [],
  };
}

/** 块级元素：段落 / 表格 */
export type Block =
  | { kind: 'para'; runs: TextRun[]; props: ParaProps }
  | { kind: 'table'; rows: TextRun[][][] };

export function paraBlock(runs: TextRun[], props: ParaProps): Block {
  return { kind: 'para', runs, props };
}

export function tableBlock(rows: TextRun[][][]): Block {
  return { kind: 'table', rows };
}

/** 页面设置（默认 A4 + 国标公文页边距） */
export interface PageSetup {
  widthCm: number;
  heightCm: number;
  topCm: number;
  bottomCm: number;
  leftCm: number;
  rightCm: number;
}

export function newPageSetup(p: Partial<PageSetup> = {}): PageSetup {
  return {
    widthCm: p.widthCm ?? 21.0,
    heightCm: p.heightCm ?? 29.7,
    topCm: p.topCm ?? 3.0,
    bottomCm: p.bottomCm ?? 2.8,
    leftCm: p.leftCm ?? 2.5,
    rightCm: p.rightCm ?? 2.5,
  };
}

/** 页码样式：Word 页脚与 PDF 页码共用同一份参数 */
export interface PageNumStyle {
  fontSizePt: number;
  font: string;
  footerDistanceCm: number;
}

export function newPageNumStyle(s: Partial<PageNumStyle> = {}): PageNumStyle {
  return {
    fontSizePt: s.fontSizePt ?? 14.0,
    font: s.font ?? '',
    footerDistanceCm: s.footerDistanceCm ?? 1.75,
  };
}

/** 公文文档模型 */
export interface GovDoc {
  blocks: Block[];
  page: PageSetup;
  title: string;
  mainTitleFont: string;
  bodyFont: string;
  bodySizePt: number;
  lineSpacingPt: number;
  indentPt: number;
  pageNumber?: boolean;
  pageNumStyle?: PageNumStyle;
}
