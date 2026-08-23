/**
 * core 层单测：md → 公文模型 → docx 最小闭环。
 * 运行：npm test
 */
import { convert, mdToDocx, COURT_DOC, GB_STANDARD } from '../miniprogram/core/gongwen';
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

function unzipDocx(bytes: Uint8Array): { [name: string]: string } {
  const files = unzipSync(bytes);
  const out: { [name: string]: string } = {};
  for (const k of Object.keys(files)) out[k] = strFromU8(files[k]);
  return out;
}

const SAMPLE = `# 民事起诉状

原告：张三，男，1990年1月1日出生。

被告：李四，男，1985年5月5日出生。

尊敬的审判员：

## 诉讼请求

1. 判令被告偿还借款人民币 **100000** 元。
2. 判令被告承担本案诉讼费用。

## 事实与理由

ll 被告于2023年1月1日向原告借款，约定一年内归还。

| 项目 | 金额 |
| --- | --- |
| 本金 | 100000 |
| 利息 | 5000 |

tab 原告（签名）::日期

此致

XX人民法院

rr 具状人：张三

rr 2023年6月1日
`;

console.log('\n[1] 转换基本结构');
{
  const doc = convert(SAMPLE);
  assert(doc.blocks.length > 5, `blocks 数量 > 5（实际 ${doc.blocks.length}）`);
  assert(doc.title === '民事起诉状', `标题解析为「民事起诉状」（实际「${doc.title}」）`);
  assert(doc.bodyFont === '仿宋_GB2312', `正文字体仿宋_GB2312`);
  assert(doc.pageNumber === false, '默认不带页码');
}

console.log('\n[2] docx 打包为合法 zip，可解包');
{
  const bytes = mdToDocx(SAMPLE);
  assert(bytes[0] === 0x50 && bytes[1] === 0x4b, 'zip 以 PK 头开头');
  assert(bytes.length > 1000, `docx 体积合理（${bytes.length} 字节）`);
  const files = unzipDocx(bytes);
  assert(!!files['[Content_Types].xml'], '包含 [Content_Types].xml');
  assert(!!files['word/document.xml'], '包含 word/document.xml');
  assert(!!files['word/styles.xml'], '包含 word/styles.xml');
  assert(!!files['_rels/.rels'], '包含 _rels/.rels');
}

console.log('\n[3] document.xml 内容校验');
{
  const files = unzipDocx(mdToDocx(SAMPLE));
  const xml = files['word/document.xml'];
  assert(xml.includes('民事起诉状'), '正文含一级标题文字');
  assert(xml.includes('仿宋_GB2312'), '含正文字体');
  assert(xml.includes('100000'), '粗体文字保留');
  assert(xml.includes('<w:b/>'), '含粗体标记');
  assert(xml.includes('<w:tbl>'), '含表格');
  assert(xml.includes('<w:tblGrid>'), '表格含网格');
  assert(xml.includes('XX人民法院'), '受文法院顶格保留');
  assert(xml.includes('<w:tab w:val="right"'), 'tab 填空线生成右对齐制表位');
  assert(xml.includes('w:leader="underline"'), 'tab 默认前导符为下划线');
  assert(xml.includes('<w:jc w:val="both"/>'), '正文两端对齐');
  assert(xml.includes('w:firstLineChars="200"'), '首行缩进 2 字符');
}

console.log('\n[4] 规范切换与页码');
{
  const gbOpts = {
    ...require('../miniprogram/core/gongwen').defaultOptions(),
    spec: GB_STANDARD,
    bodyFont: GB_STANDARD.bodyFont,
    bodySizePt: GB_STANDARD.bodySizePt,
    mainTitleFont: GB_STANDARD.mainTitleFont,
    mainTitleSizePt: GB_STANDARD.mainTitleSizePt,
    lineSpacingPt: GB_STANDARD.lineSpacingPt,
    indentPt: GB_STANDARD.indentPt,
    pageNumber: true,
  };
  const doc = convert(SAMPLE, gbOpts);
  assert(doc.bodySizePt === 16.0, '国标正文 16pt');
  const bytes = mdToDocx(SAMPLE, gbOpts);
  const files = unzipDocx(bytes);
  assert(!!files['word/footer1.xml'], '开启页码后生成页脚');
  assert(files['word/footer1.xml'].includes('PAGE'), '页脚含 PAGE 域');
  assert(files['[Content_Types].xml'].includes('footer1.xml'), 'Content_Types 声明页脚');
}

console.log('\n[5] 特殊行前缀（rr / ll / 表格分隔行过滤）');
{
  const bytes = mdToDocx('ll 顶格内容\n\nrr 落款内容\n\n| a | b |\n| --- | --- |\n| 1 | 2 |');
  const files = unzipDocx(bytes);
  const xml = files['word/document.xml'];
  assert(xml.includes('顶格内容'), 'll 前缀内容保留');
  assert(xml.includes('<w:jc w:val="right"/>'), 'rr 前缀右对齐');
  const tbl = xml.match(/<w:tbl>([\s\S]*?)<\/w:tbl>/);
  assert(!!tbl && !tbl[1].includes('---'), '表格分隔行被过滤');
}

console.log('\n[6] 智能引号');
{
  const files = unzipDocx(mdToDocx('他说："你好"'));
  const xml = files['word/document.xml'];
  assert(xml.includes('\u201C你好\u201D'), '直引号转为弯引号');
}

console.log('\n[7] 空文档兜底');
{
  const bytes = mdToDocx('');
  const files = unzipDocx(bytes);
  assert(files['word/document.xml'].includes('<w:body>'), '空文档仍生成合法 body');
}

console.log(`\n==== 结果：通过 ${passed}，失败 ${failed} ====\n`);
if (failed > 0) process.exit(1);
