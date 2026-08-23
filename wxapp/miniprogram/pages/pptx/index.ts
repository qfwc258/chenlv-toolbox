/**
 * PPTX 页：Markdown → 自动分页 → 主题色 → 导出 pptx → 预览/分享。
 * 底层：mdBlocks 解析 → pptxPaginator 分页 → pptxLayout 布局 → pptxExport 生成 OOXML。
 */
import { parseMarkdown, textOf, TextBlock } from '../../core/mdBlocks';
import { paginate } from '../../core/pptx/pptxPaginator';
import { layoutAll, LaidOutSlide } from '../../core/pptx/pptxLayout';
import { exportPptx } from '../../core/pptx/pptxExport';
import { fromTone, CUSTOM_PALETTE, DEFAULT_TONE } from '../../core/pptx/pptxTheme';
import { defaultStyle } from '../../core/pptx/pptxStyleSheet';
import type { PaginationResult } from '../../core/pptx/pptxModels';

const SAMPLE = `---
title: 法律知识分享
theme: 2E5FA3
---
# 民事诉讼流程

民事诉讼一般包含以下几个环节：

## 起诉

1. 提交起诉状
2. 法院立案
3. 送达被告

## 证据

> 谁主张，谁举证。

## 管辖

| 情形 | 法院 |
| --- | --- |
| 被告住所地 | 基层法院 |
| 合同纠纷 | 合同履行地 |

---

# 结语

感谢阅读。
`;

Page({
  data: {
    md: SAMPLE,
    palette: CUSTOM_PALETTE,
    tone: DEFAULT_TONE,
    slideCount: 0,
    pageTitles: [] as string[],
    overflowPages: [] as number[],
  },

  onMdInput(e: any) {
    this.setData({ md: e.detail.value });
  },

  onToneChange(e: any) {
    this.setData({ tone: e.detail.value });
  },

  build(): Uint8Array | null {
    try {
      const res = parseMarkdown(this.data.md);
      const style = defaultStyle();
      const pagination: PaginationResult = paginate(res.blocks, true, res.coverTitle, style);
      const theme = fromTone(this.data.tone);
      const laid: LaidOutSlide[] = layoutAll(pagination, theme, style);
      const titles = pagination.pages.map((p, i) => {
        const first = p.blocks.find((b) => b.kind === 'text' && b.type >= 1 && b.type <= 6);
        const label = p.isCover ? '封面' : first ? textOf(first as TextBlock) : '第 ' + (i + 1) + ' 页';
        return label || '第 ' + (i + 1) + ' 页';
      });
      const bytes = exportPptx(laid, theme, style);
      this.setData({
        slideCount: laid.length,
        pageTitles: titles,
        overflowPages: Array.from(pagination.overflowPages),
      });
      (this as any).pptxBytes = bytes;
      return bytes;
    } catch (err: any) {
      wx.showToast({ title: '转换失败：' + ((err && err.message) || err), icon: 'none' });
      return null;
    }
  },

  onConvert() {
    this.build();
  },

  writeFile(): string | null {
    const bytes = (this as any).pptxBytes as Uint8Array | undefined;
    if (!bytes) {
      wx.showToast({ title: '请先转换预览', icon: 'none' });
      return null;
    }
    const fs = wx.getFileSystemManager();
    const path = `${wx.env.USER_DATA_PATH}/演示_${Date.now()}.pptx`;
    const buf = bytes.buffer as ArrayBuffer;
    fs.writeFileSync(path, buf.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength), 'binary');
    return path;
  },

  onExport() {
    try {
      const bytes = this.build();
      if (!bytes) return;
      const path = this.writeFile();
      if (!path) return;
      wx.openDocument({ filePath: path, fileType: 'pptx', showMenu: true });
    } catch (err: any) {
      wx.showToast({ title: '导出失败：' + ((err && err.message) || err), icon: 'none' });
    }
  },

  onShare() {
    try {
      this.build();
      const path = this.writeFile();
      if (!path) return;
      wx.shareFileMessage({ filePath: path, fileName: '演示.pptx' });
    } catch (err: any) {
      wx.showToast({ title: '分享失败：' + ((err && err.message) || err), icon: 'none' });
    }
  },
});
