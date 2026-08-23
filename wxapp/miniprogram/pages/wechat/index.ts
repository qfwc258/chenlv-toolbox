/**
 * 公众号页：Markdown → 主题排版 → 预览（内联 HTML）/ 渲染图片存相册。
 *
 * 说明：微信公众号后台只认「内联样式」的富文本，且小程序剪贴板只能复制纯文本、
 * 无法富文本复制，故本页提供两条可用路径：
 *   1) rich-text 内联排版预览（观感近似，供快速检查）；
 *   2) Canvas 渲染成图片保存到相册（推荐，公众号可"图片+文字"发图）。
 */
import { parseMarkdown, MdBlock } from '../../core/mdBlocks';
import { THEMES, getTheme } from '../../core/wechat/themePreset';
import { buildInlineHtml } from '../../core/wechat/wechatHtml';
import { parseCss } from '../../core/wechat/cssParser';
import { renderArticle } from '../../utils/wechatCanvas';

const SAMPLE = `# 劳动争议处理指引

**适用情形**：用人单位与劳动者之间因劳动权利义务发生的争议。

## 一、协商解决

> 与用人单位协商，是成本最低、效率最高的方式。

1. 向单位人事部门提出书面诉求
2. 协商不成的，及时转入仲裁程序

## 二、申请劳动仲裁

| 事项 | 说明 |
| --- | --- |
| 时效 | 一年内提出申请 |
| 受理 | 劳动人事争议仲裁委员会 |

**注意**：仲裁是诉讼的前置程序。`;

/** 从主题 CSS 中取 body 背景色（用于画布底色）。 */
function bodyBg(css: string): string {
  let bg = '#ffffff';
  for (const r of parseCss(css)) {
    if (r.isBodyRule && r.declarations['background']) bg = r.declarations['background'];
  }
  return bg;
}

Page({
  data: {
    md: SAMPLE,
    themes: THEMES.map((t) => ({ key: t.key, name: t.name })),
    themeKey: THEMES[0].key,
    previewHtml: '',
    img: '',
    canvasW: 327,
    canvasH: 100,
  },

  onLoad() {
    const info: any = wx.getSystemInfoSync();
    const windowWidth: number = info.windowWidth || 375;
    // 页面左右 padding 24rpx + 卡片 padding 24rpx = 96rpx
    this.setData({ canvasW: Math.max(200, Math.round(windowWidth * (1 - 96 / 750))) });
  },

  onMdInput(e: any) {
    this.setData({ md: e.detail.value });
  },

  onThemeChange(e: any) {
    this.setData({ themeKey: e.detail.value });
  },

  /** 排版预览（内联 HTML，rich-text 可渲染样式） */
  onPreview() {
    const theme = getTheme(this.data.themeKey);
    this.setData({ previewHtml: buildInlineHtml(this.data.md, theme.css) });
  },

  /** Canvas 渲染图片 */
  onRender() {
    const css = getTheme(this.data.themeKey).css;
    const { blocks } = parseMarkdown(this.data.md);
    const query = wx.createSelectorQuery();
    query
      .select('#wc')
      .fields({ node: true })
      .exec((res: any[]) => {
        const canvas = res && res[0] && res[0].node;
        if (!canvas) {
          wx.showToast({ title: 'Canvas 未就绪', icon: 'none' });
          return;
        }
        this.drawToCanvas(canvas, blocks, css);
      });
  },

  drawToCanvas(canvas: any, blocks: MdBlock[], css: string) {
    const info: any = wx.getSystemInfoSync();
    const dpr: number = info.pixelRatio || 2;
    const logicalW = this.data.canvasW;
    const ctx: any = canvas.getContext('2d');

    // 第一遍：只测量总高度
    canvas.width = logicalW * dpr;
    canvas.height = 10 * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const h = renderArticle(ctx, blocks, css, { width: logicalW, measureOnly: true });
    const height = Math.max(10, Math.ceil(h));

    // 第二遍：铺底色 + 正式绘制
    canvas.width = logicalW * dpr;
    canvas.height = height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = bodyBg(css);
    ctx.fillRect(0, 0, logicalW, height);
    renderArticle(ctx, blocks, css, { width: logicalW });

    this.setData({ canvasH: height });
    wx.canvasToTempFilePath({
      canvas,
      destWidth: logicalW * dpr,
      destHeight: height * dpr,
      fileType: 'png',
      success: (res) => {
        this.setData({ img: res.tempFilePath });
        wx.showToast({ title: '图片已生成', icon: 'success' });
      },
      fail: (err: any) => {
        wx.showToast({ title: '渲染失败：' + ((err && err.errMsg) || '未知'), icon: 'none' });
      },
    });
  },

  /** 保存图片到相册 */
  onSaveAlbum() {
    const filePath = this.data.img;
    if (!filePath) {
      wx.showToast({ title: '请先渲染图片', icon: 'none' });
      return;
    }
    wx.saveImageToPhotosAlbum({
      filePath,
      success: () => {
        wx.showToast({ title: '已保存到相册', icon: 'success' });
      },
      fail: (err: any) => {
        const msg = (err && err.errMsg) || '';
        if (msg.indexOf('auth') >= 0 || msg.indexOf('deny') >= 0 || msg.indexOf('cancel') >= 0) {
          wx.showModal({
            title: '需要相册权限',
            content: '保存图片需要相册权限，请在设置中开启。',
            confirmText: '去设置',
            success: (r) => {
              if (r.confirm) wx.openSetting({});
            },
          });
        } else {
          wx.showToast({ title: '保存失败：' + msg, icon: 'none' });
        }
      },
    });
  },
});
