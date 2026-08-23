/**
 * 轻量级 OOXML (.docx) 生成引擎（TypeScript）。
 * 由 Android 版 DocxWriter.kt 移植：docx 本质是 zip 包 + 若干 XML 部件，
 * 用 fflate 打包，无需重型库即可完全掌控公文排版细节。
 */
import {
  TextRun,
  ParaProps,
  Block,
  PageSetup,
  PageNumStyle,
  Align,
  newPageSetup,
  newPageNumStyle,
} from './types';
import { zipSync, strToU8 } from '../libs/fflate';

// ---------- 单位换算 ----------
export function ptToHalfPt(pt: number): number {
  return Math.round(pt * 2);
}
export function ptToTwips(pt: number): number {
  return Math.round(pt * 20);
}
export function cmToTwips(cm: number): number {
  return Math.round(cm * 566.929);
}

/** XML 文本转义（同时过滤 XML 1.0 非法控制字符） */
export function xmlEscape(s: string): string {
  let out = '';
  for (const c of s) {
    switch (c) {
      case '&':
        out += '&amp;';
        break;
      case '<':
        out += '&lt;';
        break;
      case '>':
        out += '&gt;';
        break;
      case '"':
        out += '&quot;';
        break;
      case "'":
        out += '&apos;';
        break;
      default: {
        const code = c.codePointAt(0)!;
        if (
          code === 0x9 ||
          code === 0xa ||
          code === 0xd ||
          (code >= 0x20 && code <= 0xd7ff) ||
          (code >= 0xe000 && code <= 0xfffd)
        ) {
          out += c;
        }
      }
    }
  }
  return out;
}

/** OOXML w:val 合法边框线型（ST_Border） */
const ST_BORDER_VALUES = new Set([
  'single', 'thick', 'double', 'dotted', 'dashed', 'dotDash', 'dotDotDash', 'doubleWave',
  'wave', 'dashSmallGap', 'dashDotStroked', 'threeDEngrave', 'threeDEmboss', 'outset', 'inset',
  'thinThickSmallGap', 'thickThinSmallGap', 'thinThickThinSmallGap', 'thickBetweenThin',
]);

/** OOXML 合法下划线线型（ST_Underline） */
const ST_UNDERLINE_VALUES = new Set([
  'single', 'double', 'thick', 'dash', 'dashed', 'dashLong', 'dotDash', 'dotDotDash',
  'dotted', 'dottedHeavy', 'dashDotHeavy', 'dashDotDotHeavy', 'wave', 'wavyDouble',
  'wavyHeavy', 'words',
]);

export interface DocxWriterOpts {
  page?: PageSetup;
  defaultFont?: string;
  defaultSizePt?: number;
  defaultIndentPt?: number;
  defaultLineSpacingPt?: number;
  pageNumber?: boolean;
  pageNumStyle?: PageNumStyle;
}

export class DocxWriter {
  private page: PageSetup;
  private defaultFont: string;
  private defaultSizePt: number;
  private defaultIndentPt: number;
  private defaultLineSpacingPt: number;
  private pageNumber: boolean;
  private pageNumStyle: PageNumStyle;
  private _blocks: Block[] = [];

  constructor(opts: DocxWriterOpts = {}) {
    this.page = opts.page ?? newPageSetup();
    this.defaultFont = opts.defaultFont ?? '仿宋_GB2312';
    this.defaultSizePt = opts.defaultSizePt ?? 16.0;
    this.defaultIndentPt = opts.defaultIndentPt ?? 32.0;
    this.defaultLineSpacingPt = opts.defaultLineSpacingPt ?? 28.0;
    this.pageNumber = opts.pageNumber ?? false;
    this.pageNumStyle = opts.pageNumStyle ?? newPageNumStyle();
  }

  get blocks(): Block[] {
    return this._blocks;
  }

  addParagraph(runs: TextRun[], props: ParaProps): void {
    this._blocks.push({ kind: 'para', runs, props });
  }

  /** 添加空段落（用于标题后的空行） */
  addEmptyParagraph(props: ParaProps = { lineSpacingPt: this.defaultLineSpacingPt }): void {
    this._blocks.push({ kind: 'para', runs: [], props });
  }

  addTable(rows: TextRun[][][]): void {
    if (rows.length > 0) this._blocks.push({ kind: 'table', rows });
  }

  // ---------- XML 片段构造 ----------

  private runXml(r: TextRun): string {
    let sb = '';
    sb += '<w:r><w:rPr>';
    const f = xmlEscape(r.font);
    sb += `<w:rFonts w:ascii="${f}" w:hAnsi="${f}" w:eastAsia="${f}" w:cs="${f}"/>`;
    if (r.bold) sb += '<w:b/><w:bCs/>';
    if (r.italic) sb += '<w:i/><w:iCs/>';
    // OOXML 顺序要求：u / strike / color / highlight / vertAlign 必须排在 sz / szCs 之前
    if (r.underline) {
      const uv = r.underlineStyle && ST_UNDERLINE_VALUES.has(r.underlineStyle) ? r.underlineStyle : 'single';
      sb += `<w:u w:val="${uv}"/>`;
    }
    if (r.strike) sb += '<w:strike/>';
    if (r.color) {
      const hex = r.color.replace(/^#/, '').trim();
      if (hex !== '' && hex !== 'auto') sb += `<w:color w:val="${xmlEscape(hex)}"/>`;
    }
    if (r.highlight && r.highlight.trim() !== '' && r.highlight !== 'none') {
      sb += `<w:highlight w:val="${xmlEscape(r.highlight)}"/>`;
    }
    if (r.vertAlign === 'superscript') sb += '<w:vertAlign w:val="superscript"/>';
    if (r.vertAlign === 'subscript') sb += '<w:vertAlign w:val="subscript"/>';
    if (r.border) {
      const b = r.border;
      let v = 'single';
      if (b.value && ST_BORDER_VALUES.has(b.value)) v = b.value;
      else if (b.value === 'dash') v = 'dashed';
      else if (b.value === 'dot') v = 'dotted';
      const color = (b.color ?? '#000000').replace(/^#/, '');
      const sz = Math.max(2, Math.min(96, Math.round((b.szPt ?? 1.0) * 8)));
      sb += `<w:bdr w:val="${v}" w:sz="${sz}" w:space="0" w:color="${xmlEscape(color)}"/>`;
    }
    const hp = ptToHalfPt(r.sizePt);
    sb += `<w:sz w:val="${hp}"/>`;
    sb += `<w:szCs w:val="${hp}"/>`;
    sb += '</w:rPr>';
    // 文字中的 \t / \n 拆成 w:tab / w:br
    const t = r.text;
    if (t.length === 0) {
      sb += '<w:t xml:space="preserve"></w:t>';
    } else {
      let i = 0;
      while (i < t.length) {
        const ch = t[i];
        if (ch === '\t') {
          sb += '<w:tab/>';
          i++;
        } else if (ch === '\n') {
          sb += '<w:br/>';
          i++;
        } else {
          let j = i;
          while (j < t.length && t[j] !== '\t' && t[j] !== '\n') j++;
          sb += `<w:t xml:space="preserve">${xmlEscape(t.substring(i, j))}</w:t>`;
          i = j;
        }
      }
    }
    sb += '</w:r>';
    return sb;
  }

  /** pPr 子元素顺序：spacing -> ind -> jc -> tabs */
  private pPrXml(p: ParaProps): string {
    let sb = '';
    sb += '<w:pPr>';
    sb += `<w:spacing w:before="${ptToTwips(p.spaceBeforePt ?? 0)}" w:after="${ptToTwips(p.spaceAfterPt ?? 0)}" w:line="${ptToTwips(p.lineSpacingPt ?? 28)}" w:lineRule="exact"/>`;
    sb += `<w:ind w:firstLine="${ptToTwips(p.firstLineIndentPt ?? 0)}"`;
    if ((p.firstLineIndentPt ?? 0) > 0) sb += ' w:firstLineChars="200"';
    sb += '/>';
    sb += `<w:jc w:val="${p.align ?? 'left'}"/>`;
    if (p.tabs && p.tabs.length > 0) {
      sb += '<w:tabs>';
      for (const t of p.tabs) {
        const al = t.align === 'center' ? 'center' : t.align === 'right' ? 'right' : t.align === 'decimal' ? 'decimal' : 'left';
        const ld = t.leader === 'underline' || t.leader === 'dot' || t.leader === 'dash' || t.leader === 'middleDot' || t.leader === 'none' ? t.leader : 'none';
        sb += `<w:tab w:val="${al}" w:pos="${ptToTwips(t.posPt)}"`;
        if (ld !== 'none') sb += ` w:leader="${ld}"`;
        sb += '/>';
      }
      sb += '</w:tabs>';
    }
    sb += '</w:pPr>';
    return sb;
  }

  private paraXml(b: { runs: TextRun[]; props: ParaProps }): string {
    let sb = '';
    sb += `<w:p>${this.pPrXml(b.props)}`;
    for (const r of b.runs) sb += this.runXml(r);
    sb += '</w:p>';
    return sb;
  }

  private tableXml(b: { rows: TextRun[][][] }): string {
    const colCount = Math.max(1, Math.max(...b.rows.map((r) => r.length)));
    const usable = cmToTwips(this.page.widthCm - this.page.leftCm - this.page.rightCm);
    const colW = usable / colCount;

    let sb = '';
    sb += '<w:tbl><w:tblPr>';
    sb += '<w:tblStyle w:val="TableGrid"/>';
    sb += `<w:tblW w:w="${usable}" w:type="dxa"/>`;
    sb += '<w:jc w:val="center"/>';
    sb += '<w:tblBorders>';
    for (const edge of ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']) {
      sb += `<w:${edge} w:val="single" w:sz="4" w:space="0" w:color="000000"/>`;
    }
    sb += '</w:tblBorders>';
    sb += '<w:tblLayout w:type="fixed"/>';
    sb += '</w:tblPr>';

    sb += '<w:tblGrid>';
    for (let k = 0; k < colCount; k++) sb += `<w:gridCol w:w="${colW}"/>`;
    sb += '</w:tblGrid>';

    for (const row of b.rows) {
      sb += '<w:tr>';
      for (let c = 0; c < colCount; c++) {
        const cell = row[c] ?? [];
        sb += '<w:tc><w:tcPr>';
        sb += `<w:tcW w:w="${colW}" w:type="dxa"/>`;
        sb += '<w:vAlign w:val="center"/>';
        sb += '</w:tcPr>';
        // 表格单元格内不缩进
        sb += '<w:p>';
        sb += this.pPrXml({ align: 'left', firstLineIndentPt: 0, lineSpacingPt: this.defaultLineSpacingPt });
        for (const r of cell) sb += this.runXml(r);
        sb += '</w:p>';
        sb += '</w:tc>';
      }
      sb += '</w:tr>';
    }
    sb += '</w:tbl>';
    // 表格后必须紧跟一个段落，否则 Word 中两个相邻表格会被合并
    sb += `<w:p>${this.pPrXml({ firstLineIndentPt: 0, lineSpacingPt: this.defaultLineSpacingPt })}</w:p>`;
    return sb;
  }

  private sectPrXml(): string {
    let sb = '';
    sb += '<w:sectPr>';
    sb += `<w:pgSz w:w="${cmToTwips(this.page.widthCm)}" w:h="${cmToTwips(this.page.heightCm)}" w:orient="portrait" w:code="9"/>`;
    sb += `<w:pgMar w:top="${cmToTwips(this.page.topCm)}" w:right="${cmToTwips(this.page.rightCm)}" w:bottom="${cmToTwips(this.page.bottomCm)}" w:left="${cmToTwips(this.page.leftCm)}" w:header="851" w:footer="${cmToTwips(this.pageNumStyle.footerDistanceCm)}" w:gutter="0"/>`;
    sb += '<w:cols w:space="425"/>';
    sb += '<w:docGrid w:type="lines" w:linePitch="312"/>';
    if (this.pageNumber) {
      sb += '<w:footerReference w:type="default" r:id="rIdFtr"/>';
    }
    sb += '</w:sectPr>';
    return sb;
  }

  private documentXml(): string {
    let sb = '';
    sb += '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
    sb += '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" ';
    sb += 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">';
    sb += '<w:body>';
    for (const b of this._blocks) {
      if (b.kind === 'para') sb += this.paraXml(b);
      else sb += this.tableXml(b);
    }
    sb += this.sectPrXml();
    sb += '</w:body></w:document>';
    return sb;
  }

  private stylesXml(): string {
    const f = xmlEscape(this.defaultFont);
    const hp = ptToHalfPt(this.defaultSizePt);
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults>
<w:rPrDefault><w:rPr>
<w:rFonts w:ascii="${f}" w:hAnsi="${f}" w:eastAsia="${f}" w:cs="${f}"/>
<w:sz w:val="${hp}"/><w:szCs w:val="${hp}"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr>
<w:spacing w:before="0" w:after="0" w:line="${ptToTwips(this.defaultLineSpacingPt)}" w:lineRule="exact"/>
<w:ind w:firstLine="${ptToTwips(this.defaultIndentPt)}"/>
</w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal">
<w:name w:val="Normal"/><w:qFormat/>
<w:pPr>
<w:spacing w:before="0" w:after="0" w:line="${ptToTwips(this.defaultLineSpacingPt)}" w:lineRule="exact"/>
<w:ind w:firstLine="${ptToTwips(this.defaultIndentPt)}"/>
</w:pPr>
<w:rPr><w:rFonts w:ascii="${f}" w:hAnsi="${f}" w:eastAsia="${f}" w:cs="${f}"/><w:sz w:val="${hp}"/><w:szCs w:val="${hp}"/></w:rPr>
</w:style>
<w:style w:type="table" w:styleId="TableGrid">
<w:name w:val="Table Grid"/>
<w:tblPr><w:tblBorders>
<w:top w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:left w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:bottom w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:right w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:insideH w:val="single" w:sz="4" w:space="0" w:color="000000"/>
<w:insideV w:val="single" w:sz="4" w:space="0" w:color="000000"/>
</w:tblBorders></w:tblPr>
</w:style>
</w:styles>`;
  }

  private contentTypesXml(): string {
    let s = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>`;
    if (this.pageNumber) {
      s += '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>';
    }
    s += `<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>`;
    return s;
  }

  private rootRelsXml(): string {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>`;
  }

  private docRelsXml(): string {
    let s = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>`;
    if (this.pageNumber) {
      s += '<Relationship Id="rIdFtr" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>';
    }
    s += '</Relationships>';
    return s;
  }

  /** 页脚：PAGE 域页码（Word 打开后自动计算实际页号） */
  private footerXml(): string {
    const st = this.pageNumStyle;
    const fontName = st.font !== '' ? st.font : this.defaultFont;
    const hp = ptToHalfPt(st.fontSizePt);
    const f = xmlEscape(fontName);
    const rPr =
      `<w:rPr>` +
      `<w:rFonts w:ascii="${f}" w:hAnsi="${f}" w:eastAsia="${f}" w:cs="${f}"/>` +
      `<w:sz w:val="${hp}"/><w:szCs w:val="${hp}"/>` +
      `</w:rPr>`;
    const pageField =
      `<w:r>${rPr}<w:fldChar w:fldCharType="begin"/></w:r>` +
      `<w:r>${rPr}<w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>` +
      `<w:r>${rPr}<w:fldChar w:fldCharType="end"/></w:r>`;
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p>
<w:pPr><w:jc w:val="center"/><w:ind w:firstLine="0"/><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>
${pageField}
</w:p>
</w:ftr>`;
  }

  private corePropsXml(title: string): string {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
 xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>${xmlEscape(title)}</dc:title>
<dc:creator>陈律工具箱</dc:creator>
<cp:lastModifiedBy>陈律工具箱</cp:lastModifiedBy>
</cp:coreProperties>`;
  }

  private appPropsXml(): string {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
 xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
<Application>陈律工具箱 for WeChat</Application>
</Properties>`;
  }

  /** 打包生成 .docx 字节流（Uint8Array，PN 开头即合法 zip） */
  build(title = '公文'): Uint8Array {
    const files: { [name: string]: Uint8Array } = {};
    files['[Content_Types].xml'] = strToU8(this.contentTypesXml());
    files['_rels/.rels'] = strToU8(this.rootRelsXml());
    files['docProps/core.xml'] = strToU8(this.corePropsXml(title));
    files['docProps/app.xml'] = strToU8(this.appPropsXml());
    files['word/_rels/document.xml.rels'] = strToU8(this.docRelsXml());
    files['word/styles.xml'] = strToU8(this.stylesXml());
    if (this.pageNumber) files['word/footer1.xml'] = strToU8(this.footerXml());
    files['word/document.xml'] = strToU8(this.documentXml());
    return zipSync(files, { level: 6 });
  }
}
