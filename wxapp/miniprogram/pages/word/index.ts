/**
 * Word（公文）页：Markdown → 公文排版 → 导出 docx / 预览 / 分享。
 */
import { convert, mdToDocx, specByName, ALL_PRESETS, GovDocSpec } from '../../core/gongwen';
import { GovDoc, Block, TextRun, ParaProps } from '../../core/types';

interface Info {
  title: string;
  paraCount: number;
  tableCount: number;
}

const SAMPLE = `# 民事起诉状

原告：张三，男，1990年1月1日出生，汉族。

被告：李四，男，1985年5月5日出生，汉族。

尊敬的审判员：

## 诉讼请求

1. 判令被告偿还借款人民币 **100000** 元及利息。
2. 判令被告承担本案全部诉讼费用。

## 事实与理由

ll 被告于2023年1月1日向原告借款，约定一年内归还，双方签有借条。

| 项目 | 金额（元） |
| --- | --- |
| 本金 | 100000 |
| 利息 | 5000 |

tab 原告（签名）::日期

此致

XX人民法院

rr 具状人：张三
rr 2023年6月1日
`;

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

const px = (pt: number): number => Math.round(pt * 1.333);

function runsToHtml(runs: TextRun[]): string {
  return runs
    .map((r) => {
      let inner = esc(r.text);
      if (r.bold) inner = `<strong>${inner}</strong>`;
      if (r.italic) inner = `<em>${inner}</em>`;
      if (r.underline) inner = `<u>${inner}</u>`;
      if (r.strike) inner = `<s>${inner}</s>`;
      return inner;
    })
    .join('');
}

function paraHtml(runs: TextRun[], props: ParaProps): string {
  const font = runs.length > 0 ? runs[0].font : '仿宋_GB2312';
  const size = runs.length > 0 ? runs[0].sizePt : 14;
  const align = props.align ?? 'left';
  const ta = align === 'both' ? 'justify' : align;
  const indent = (props.firstLineIndentPt ?? 0) > 0 ? `text-indent:${px(props.firstLineIndentPt ?? 0)}px;` : '';
  const lh = props.lineSpacingPt ? (props.lineSpacingPt / size).toFixed(2) : 1.5;
  return `<div style="font-family:${esc(font)};font-size:${px(size)}px;line-height:${lh};text-align:${ta};${indent}margin:2px 0;">${runsToHtml(runs)}</div>`;
}

function tableHtml(rows: TextRun[][][]): string {
  const head = `<tr>${rows[0]
    .map((c) => `<th style="border:1px solid #999;padding:4px 8px;background:#f0f0f0;font-weight:600;">${runsToHtml(c)}</th>`)
    .join('')}</tr>`;
  const body = rows
    .slice(1)
    .map((r) => `<tr>${r.map((c) => `<td style="border:1px solid #999;padding:4px 8px;">${runsToHtml(c)}</td>`).join('')}</tr>`)
    .join('');
  return `<table style="width:100%;border-collapse:collapse;margin:8px 0;">${head}${body}</table>`;
}

function buildPreviewHtml(doc: GovDoc): string {
  const parts: string[] = [];
  parts.push(
    `<div style="text-align:center;font-family:${esc(doc.mainTitleFont)};font-size:${px(22)}px;font-weight:bold;margin:4px 0 12px;">${esc(doc.title)}</div>`
  );
  for (const b of doc.blocks) {
    if (b.kind === 'para') parts.push(paraHtml(b.runs, b.props));
    else parts.push(tableHtml(b.rows));
  }
  return parts.join('');
}

Page({
  data: {
    md: SAMPLE,
    specs: ALL_PRESETS.map((s) => ({ name: s.specName })),
    specName: ALL_PRESETS[0].specName,
    pageNumber: false,
    info: { title: '', paraCount: 0, tableCount: 0 } as Info,
    previewHtml: '',
  },

  onMdInput(e: any) {
    this.setData({ md: e.detail.value });
  },

  onSpecChange(e: any) {
    this.setData({ specName: e.detail.value });
  },

  onPageNumberChange(e: any) {
    this.setData({ pageNumber: e.detail.value });
  },

  onConvert() {
    const spec: GovDocSpec = specByName(this.data.specName);
    const options = {
      spec,
      mainTitleFont: spec.mainTitleFont,
      bodyFont: spec.bodyFont,
      mainTitleSizePt: spec.mainTitleSizePt,
      bodySizePt: spec.bodySizePt,
      lineSpacingPt: spec.lineSpacingPt,
      indentPt: spec.indentPt,
      smartQuotes: true,
      justify: true,
      autoSalutation: true,
      pageNumber: this.data.pageNumber,
    };
    const doc = convert(this.data.md, options);
    let tableCount = 0;
    for (const b of doc.blocks) if (b.kind === 'table') tableCount++;
    const paraCount = doc.blocks.length - tableCount;
    this.setData({
      info: { title: doc.title, paraCount, tableCount },
      previewHtml: buildPreviewHtml(doc),
    });
  },

  writeDocx(): string {
    const spec: GovDocSpec = specByName(this.data.specName);
    const options = {
      spec,
      mainTitleFont: spec.mainTitleFont,
      bodyFont: spec.bodyFont,
      mainTitleSizePt: spec.mainTitleSizePt,
      bodySizePt: spec.bodySizePt,
      lineSpacingPt: spec.lineSpacingPt,
      indentPt: spec.indentPt,
      smartQuotes: true,
      justify: true,
      autoSalutation: true,
      pageNumber: this.data.pageNumber,
    };
    const bytes = mdToDocx(this.data.md, options);
    const fs = wx.getFileSystemManager();
    const path = `${wx.env.USER_DATA_PATH}/公文_${Date.now()}.docx`;
    const buf = bytes.buffer as ArrayBuffer;
    fs.writeFileSync(path, buf.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength), 'binary');
    return path;
  },

  onExport() {
    try {
      const path = this.writeDocx();
      wx.openDocument({ filePath: path, fileType: 'docx', showMenu: true });
    } catch (err: any) {
      wx.showToast({ title: '导出失败：' + (err?.message ?? err), icon: 'none' });
    }
  },

  onShare() {
    try {
      const path = this.writeDocx();
      wx.shareFileMessage({ filePath: path, fileName: '公文.docx' });
    } catch (err: any) {
      wx.showToast({ title: '分享失败：' + (err?.message ?? err), icon: 'none' });
    }
  },
});
