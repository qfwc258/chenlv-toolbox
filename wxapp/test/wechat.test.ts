/**
 * wechat 公众号排版模块单测。
 * 运行：npx tsx test/wechat.test.ts
 */
import { buildInlineHtml, buildPreviewHtml } from '../miniprogram/core/wechat/wechatHtml';
import { THEMES, getTheme } from '../miniprogram/core/wechat/themePreset';
import { parseCss } from '../miniprogram/core/wechat/cssParser';
import { parseMarkdown } from '../miniprogram/core/mdBlocks';
import { renderArticle } from '../miniprogram/utils/wechatCanvas';

let passed = 0;
let failed = 0;

function assert(cond: boolean, msg: string): void {
  if (cond) {
    passed++;
    console.log(`  ✓ ${msg}`);
  } else {
    failed++;
    console.error(`  ✗ ${msg}`);
  }
}

const lawBlue = getTheme('css_law_blue');

const SAMPLE = `# 主标题

## 二级标题

- 列表项一
- 列表项二

> 引用块内容

| 项目 | 金额 |
| --- | --- |
| 本金 | 100000 |
| 利息 | 500 |
`;

console.log('\n[1] 主题预设（key/name 与 Kotlin 完全一致，css 原样保留）');
{
  assert(THEMES.length === 5, `共 5 套主题（实际 ${THEMES.length}）`);
  const expect: Array<[string, string]> = [
    ['css_law_blue', '法律深蓝普法'],
    ['css_law_clean', '法律极简纯白'],
    ['css_simple', '简约阅读'],
    ['css_tech', '科技干货'],
    ['css_business', '商务正式']
  ];
  for (let i = 0; i < expect.length; i++) {
    const [k, n] = expect[i];
    assert(THEMES[i].key === k && THEMES[i].name === n, `第 ${i + 1} 套 key/name = ${k}/${n}`);
  }
  assert(getTheme('css_tech').name === '科技干货', 'getTheme 命中 css_tech');
  assert(getTheme('no_such_key').key === 'css_law_blue', 'getTheme 未知 key 兜底第一套');
  assert(lawBlue.css.includes('table-layout: fixed'), '主题 CSS 含 table-layout: fixed');
  assert(lawBlue.css.includes('font-family: -apple-system'), '主题 CSS 含 font-family');
}

console.log('\n[2] 极简 CSS 解析');
{
  const rules = parseCss(lawBlue.css);
  const bodyRule = rules.find((r) => r.isBodyRule);
  assert(!!bodyRule, '解析出 body 规则');
  assert(bodyRule!.declarations['line-height'] === '1.6', 'body line-height=1.6');
  assert(bodyRule!.declarations['font-family'].includes('PingFang SC'), 'body font-family 含 PingFang SC');
  assert(bodyRule!.declarations['color'] === '#2c3e50', 'body color=#2c3e50');
  assert(!!rules.find((r) => r.selectors.includes('th') && r.selectors.includes('td')), '解析出 th, td 多选择器规则');
  assert(!!rules.find((r) => r.selectors.includes('pre code')), '解析出后代选择器 pre code');
  assert(!!rules.find((r) => r.selectors.includes('ul') && r.selectors.includes('ol')), '解析出 ul, ol 多选择器规则');
}

console.log('\n[3] buildInlineHtml（100% 行内片段）');
{
  const html = buildInlineHtml(SAMPLE, lawBlue.css);
  assert(html.includes('line-height:'), '含行内 line-height');
  assert(!html.includes('<style'), '不含 <style>');
  assert(!html.includes(' class='), '不含 class=');
  assert(html.includes('<table'), '含 <table');
  assert(html.includes('table-layout: fixed'), '含 table-layout: fixed');
  assert(html.includes('border-collapse: collapse'), '含 border-collapse: collapse');
  assert(html.includes('<h2'), '含 <h2');
  assert(html.includes('<blockquote'), '含 <blockquote');
  assert(html.includes('width: '), '含 width: ');
  assert(html.startsWith('<section'), '以 <section 开头');
  assert(html.includes('border="1" cellspacing="0" cellpadding="6"'), '表格带 border/cellspacing/cellpadding 属性');
  assert(html.includes('<ul'), '无序列表渲染 <ul');
  assert(html.includes('<li'), '列表项渲染 <li');
}

console.log('\n[4] 其它块类型与行内样式');
{
  assert(buildInlineHtml('1. 甲\n2. 乙', lawBlue.css).includes('<ol'), '有序列表渲染 <ol');
  assert(buildInlineHtml('3. 甲\n4. 乙', lawBlue.css).includes('<ol start="3"'), '有序列表带 start');
  const codeHtml = buildInlineHtml('```\nconst a = 1;\n```', lawBlue.css);
  assert(codeHtml.includes('<pre') && codeHtml.includes('<code') && codeHtml.includes('const a = 1;') && codeHtml.includes('</code>'), '代码块 <pre><code> 保留 raw');
  const brHtml = buildInlineHtml('---', lawBlue.css);
  assert(!brHtml.includes('<hr') && !brHtml.includes('---'), 'ForcedBreak 被忽略（不产 <hr）');
  assert(buildInlineHtml('**加粗**', lawBlue.css).includes('<strong') && buildInlineHtml('**加粗**', lawBlue.css).includes('line-height:'), '粗体带行内样式');
  assert(buildInlineHtml('[链接](https://example.com)', lawBlue.css).includes('<a href="https://example.com"'), '链接渲染 <a href');
  assert(buildInlineHtml('~~删除~~', lawBlue.css).includes('<del>'), '删除线渲染 <del>');
}

console.log('\n[5] buildPreviewHtml（完整文档）');
{
  const html = buildPreviewHtml(SAMPLE, lawBlue.css);
  assert(html.includes('<style>'), '含 <style>');
  assert(html.includes('<meta'), '含 <meta');
  assert(html.includes('<!DOCTYPE html>'), '含 DOCTYPE');
  assert(html.includes('<html'), '含 <html');
  assert(html.includes('table-layout: fixed'), '预览表格固定布局内联');
  assert(html.includes('width: '), '预览表格列宽内联');
  assert(html.includes('<h2'), '预览含 <h2');
  assert(html.includes('<blockquote'), '预览含 <blockquote');
  assert(!html.includes('<script'), '预览无脚本标签');
  assert(!html.includes(' on' ), '预览无 on* 事件属性');
}

console.log('\n[6] Canvas 渲染冒烟（wechatCanvas.renderArticle）');
{
  function mockCtx(): any {
    return {
      font: '16px sans-serif',
      measureText(text: string) {
        const m = /(\d+)px/.exec(String(this.font));
        const size = m ? parseInt(m[1], 10) : 16;
        return { width: text.length * size * 0.55 };
      },
      fillRect() {}, strokeRect() {}, fillText() {}, strokeText() {},
      beginPath() {}, moveTo() {}, lineTo() {}, stroke() {}, closePath() {},
      save() {}, restore() {}, translate() {}, rotate() {}, setTransform() {}, scale() {},
    };
  }
  const { blocks } = parseMarkdown(SAMPLE);
  const h = renderArticle(mockCtx(), blocks, lawBlue.css, { width: 320, measureOnly: true });
  assert(h > 100, `测高模式返回合理高度（${h}px）`);
  const hDraw = renderArticle(mockCtx(), blocks, lawBlue.css, { width: 320 });
  assert(hDraw === h, `绘制模式高度与测高一致（${hDraw}）`);
  // 长段落：窄宽度下折行更多 → 高度明显更高
  const longText = '这是一段很长很长的中文正文内容，用来验证文本折行逻辑在窄宽度下会产生更多行，从而推高总高度，确保渲染不溢出画布。';
  const longMd = '# 标题\n\n' + longText + longText + longText + '\n';
  const { blocks: blocksLong } = parseMarkdown(longMd);
  const hWide = renderArticle(mockCtx(), blocksLong, lawBlue.css, { width: 320, measureOnly: true });
  const hLongNarrow = renderArticle(mockCtx(), blocksLong, lawBlue.css, { width: 200, measureOnly: true });
  assert(hLongNarrow > hWide, `长文窄宽度折行更多（${hLongNarrow} > ${hWide}）`);
  // 代码块 + 长文本不崩溃
  const md2 = '# 代码\n\n```ts\nconst a = 1;\nconsole.log(a)\n```\n\n**粗体**内容，包含中文与English混排的一段较长正文，用于验证换行与折行逻辑不会抛出异常。\n';
  const { blocks: blocks2 } = parseMarkdown(md2);
  const h2 = renderArticle(mockCtx(), blocks2, lawBlue.css, { width: 320, measureOnly: true });
  assert(h2 > 50, `代码块+长文渲染不崩溃（${h2}px）`);
}

console.log(`\n==== 结果：通过 ${passed}，失败 ${failed} ====\n`);
if (failed > 0) process.exit(1);
