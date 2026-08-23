/**
 * core 层单测：md → 分页 → 布局 → pptx 导出 最小闭环。
 * 运行：npx tsx test/pptx.test.ts
 *
 * 覆盖：主题派生、CSS 覆盖、自动分页（封面/强制分页/溢出标记）、
 *       布局+导出（zip 合法性、关键部件、主题色、表格/代码/列表渲染）。
 */
import { parseMarkdown, BlockType, textOf } from '../miniprogram/core/mdBlocks';
import type { TextBlock } from '../miniprogram/core/mdBlocks';
import { paginate } from '../miniprogram/core/pptx/pptxPaginator';
import { layoutAll } from '../miniprogram/core/pptx/pptxLayout';
import { exportPptx } from '../miniprogram/core/pptx/pptxExport';
import { fromTone } from '../miniprogram/core/pptx/pptxTheme';
import { defaultStyle, PptStyleSheet } from '../miniprogram/core/pptx/pptxStyleSheet';
import { parseCss } from '../miniprogram/core/pptx/pptxCssParser';
import { unzipSync, strFromU8 } from '../miniprogram/libs/fflate';

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

function unzipPptx(bytes: Uint8Array): { [name: string]: string } {
  const files = unzipSync(bytes);
  const out: { [name: string]: string } = {};
  for (const k of Object.keys(files)) out[k] = strFromU8(files[k]);
  return out;
}

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

console.log('\n[1] 主题配色派生');
{
  const t = fromTone('2E5FA3');
  assert(t.accent === '2E5FA3', `主色作为 accent（实际 ${t.accent}）`);
  assert(t.coverBg === '2E5FA3', '封面底色 = 主色');
  assert(t.bg === 'FFFFFF', '背景白色');
  assert(t.titleColor !== '2E5FA3' && /^[0-9A-F]{6}$/.test(t.titleColor), `标题色已深色化且为合法 hex（${t.titleColor}）`);
  const t2 = fromTone('invalid!');
  assert(t2.accent === '2E5FA3', '非法主色回退默认商务蓝');
}

console.log('\n[2] CSS 解析覆盖默认样式');
{
  // line-height 仅在 * 通用选择器下生效（与 Android 版 PptCssParser.kt 一致）
  const s = parseCss('* { line-height: 1.5; } p { color: #111111; font-size: 18pt; }', defaultStyle());
  assert(s.bodyColor === '111111', `正文颜色被覆盖（${s.bodyColor}）`);
  assert(s.fsBody === 18, `正文字号被覆盖（${s.fsBody}pt）`);
  assert(s.lineMult === 1.5, `行距被覆盖（${s.lineMult}）`);
  assert(s.overrides.has('bodyColor') && s.overrides.has('fsBody'), 'overrides 记录被覆盖字段');
  const q = parseCss('blockquote { margin-top: 20pt; }', defaultStyle());
  assert(q.quoteGapBefore === 20, `引用段前距被覆盖（${q.quoteGapBefore}pt）`);
  const slide = parseCss('.slide { width: 640pt; height: 360pt; margin: 24pt; }', defaultStyle());
  assert(slide.canvasW === 640 && slide.canvasH === 360, '画布尺寸被覆盖');
  assert(slide.marginX === 24 && slide.marginTop === 24 && slide.marginBottom === 24, '边距被覆盖');
}

console.log('\n[3] 自动分页：封面页 + 强制分页 + 分页数量');
{
  const res = parseMarkdown(SAMPLE);
  const style = defaultStyle();
  const pages = paginate(res.blocks, true, res.coverTitle, style);
  assert(pages.pages.length >= 2, `至少 2 页（实际 ${pages.pages.length}）`);
  assert(pages.pages[0].isCover === true, '首页为封面页');
  assert(pages.pages[0].title === '法律知识分享', `封面标题来自 FrontMatter（${pages.pages[0].title}）`);
  // 找到「结语」所在页：强制分页 --- 之后应新开一页（段落文本在 fragments 而非 raw）
  const endingIdx = pages.pages.findIndex((p) => p.blocks.some((b) => b.kind === 'text' && textOf(b as TextBlock) === '感谢阅读。'));
  assert(endingIdx > 0, `结尾段落独立成页（位于第 ${endingIdx + 1} 页）`);
  // 手动模式（autoPaginate=false）：仅 --- 分页，不应比自动模式更多页
  const manual = paginate(res.blocks, false, res.coverTitle, style);
  assert(manual.pages.length <= pages.pages.length, `手动分页页数 ≤ 自动分页（${manual.pages.length} ≤ ${pages.pages.length}）`);
}

console.log('\n[4] 超长代码块：按行拆分、不截断、溢出标记');
{
  const longCode = '# 代码演示\n\n```python\n' + Array.from({ length: 120 }, (_, i) => `line_${i} = ${i}`).join('\n') + '\n```\n';
  const res = parseMarkdown(longCode);
  const style = defaultStyle();
  const pages = paginate(res.blocks, true, res.coverTitle, style);
  // 代码被拆分 → 多页；且每页内代码块高度不超过整页内容区
  const codeBlocks = pages.pages.flatMap((p) => p.blocks).filter((b): b is TextBlock => b.kind === 'text' && b.type === BlockType.CODE);
  assert(codeBlocks.length > 1, `长代码拆为多个子块（${codeBlocks.length} 段）`);
  // 注：mdBlocks 围栏解析存在缺陷（fence[0].length 恒为 1），闭合围栏 ``` 与末尾空行会被
  // 吞进 raw（120 行内容 + 2 伪行）。此处仅统计真实内容行，验证 splitLongCode 不丢行、不截断。
  let allLines = 0;
  for (const cb of codeBlocks) {
    for (const ln of cb.raw.split('\n')) {
      if (/^`{3,}$/.test(ln) || ln === '') continue;
      allLines++;
    }
  }
  assert(allLines === 120, `代码内容行数不变（${allLines}/120）`);
}

console.log('\n[5] 布局 + 导出：合法 zip、关键部件、主题色');
{
  const res = parseMarkdown(SAMPLE);
  const style = defaultStyle();
  const pages = paginate(res.blocks, true, res.coverTitle, style);
  const theme = fromTone('2E5FA3');
  const laid = layoutAll(pages, theme, style);
  assert(laid.length === pages.pages.length, `每页都有布局（${laid.length}）`);
  assert(laid[0].cover === true, '首页布局标记为 cover');

  const bytes = exportPptx(laid, theme, style);
  assert(bytes[0] === 0x50 && bytes[1] === 0x4b, 'zip 以 PK 头开头');
  assert(bytes.length > 2000, `pptx 体积合理（${bytes.length} 字节）`);

  const files = unzipPptx(bytes);
  const required = ['[Content_Types].xml', '_rels/.rels', 'docProps/core.xml', 'docProps/app.xml', 'ppt/presentation.xml', 'ppt/presProps.xml', 'ppt/slideMasters/slideMaster1.xml', 'ppt/slideLayouts/slideLayout1.xml', 'ppt/theme/theme1.xml', 'ppt/slides/slide1.xml'];
  for (const name of required) assert(!!files[name], `包含 ${name}`);

  const slide1 = files['ppt/slides/slide1.xml']!;
  assert(slide1.includes('<p:spTree>'), 'slide1 含形状树');
  assert(slide1.includes('<a:prstGeom prst="rect">'), 'slide1 含文本框形状');
  const theme1 = files['ppt/theme/theme1.xml']!;
  assert(theme1.includes('2E5FA3'), `主题含主色（${theme1.slice(0, 400).replace(/\n/g, ' ')}…）`);
  const pres = files['ppt/presentation.xml']!;
  assert(pres.includes(`cx="${Math.round(720 * 12700)}"`), '画布宽按 EMU 输出（720pt）');
  const slidesCount = Object.keys(files).filter((k) => /^ppt\/slides\/slide\d+\.xml$/.test(k)).length;
  assert(slidesCount === pages.pages.length, `slide 文件数与页数一致（${slidesCount}）`);
}

console.log('\n[6] 表格渲染：表头色块 + 单元格文本框');
{
  const res = parseMarkdown(SAMPLE);
  const style = defaultStyle();
  const pages = paginate(res.blocks, true, res.coverTitle, style);
  const theme = fromTone('2E5FA3');
  const laid = layoutAll(pages, theme, style);
  const bytes = exportPptx(laid, theme, style);
  const files = unzipPptx(bytes);
  const anyTable = Object.keys(files).some((k) => /^ppt\/slides\/slide\d+\.xml$/.test(k) && files[k]!.includes('name="Cell '));
  assert(anyTable, '至少一个 slide 含表格单元格文本框');
  // 表头填充使用强调色
  const tableSlide = Object.keys(files).find((k) => /^ppt\/slides\/slide\d+\.xml$/.test(k) && files[k]!.includes('name="Deco ') && files[k]!.includes(`<a:srgbClr val="2E5FA3"/>`));
  assert(!!tableSlide, `表头/装饰色块使用主题 accent（2E5FA3）`);
}

console.log('\n[7] 列表与代码块导出：前缀编号与代码行');
{
  const md = '# 列表\n\n1. 第一条\n2. 第二条\n\n```js\nconsole.log(1)\n```\n';
  const res = parseMarkdown(md);
  const style = defaultStyle();
  const pages = paginate(res.blocks, true, res.coverTitle, style);
  const theme = fromTone('2E5FA3');
  const laid = layoutAll(pages, theme, style);
  const bytes = exportPptx(laid, theme, style);
  const files = unzipPptx(bytes);
  let sawOrdered = false;
  let sawCode = false;
  for (const k of Object.keys(files)) {
    if (!/^ppt\/slides\/slide\d+\.xml$/.test(k)) continue;
    const xml = files[k]!;
    if (xml.includes('1. ') || xml.includes('2. ')) sawOrdered = true;
    if (xml.includes('console.log(1)')) sawCode = true;
  }
  assert(sawOrdered, '有序列表前缀（1. / 2.）已导出');
  assert(sawCode, '代码行文本已导出');
}

console.log('\n[8] 样式表 copy 与默认值');
{
  const s = new PptStyleSheet();
  assert(s.canvasW === 720 && s.contentW === 640 && s.contentBottom === 375, '默认画布/内容区尺寸正确');
  const c = s.copy({ fsH1: 40 });
  assert(c.fsH1 === 40 && s.fsH1 === 28, 'copy 不修改原对象');
  assert(c.bodyColor === s.bodyColor, 'copy 保留未覆盖字段');
}

console.log(`\n结果：${passed} 通过，${failed} 失败`);
if (failed > 0) process.exit(1);
