/**
 * PPTX 原生导出引擎（由 Android 版 PptExportEngine.kt 移植，手写 OOXML，零第三方依赖）。
 *
 * 输出标准 .pptx（zip 包内 XML），每个块渲染为一个原生可编辑文本框 Shape，
 * WPS / Office 完全可二次编辑，保留加粗/斜体/删除线/链接色/列表编号。
 * 不依赖图片或渲染快照，100% 矢量可编辑。
 *
 * 与 Kotlin 版差异：打包用本地拷贝的 fflate（zipSync），输出为 Uint8Array 字节流，
 * 而非写 OutputStream。运行环境 = Node（跑测试）+ 微信小程序，禁止任何 DOM/Node 专有 API。
 */
import { BlockType, TableAlign, InlineFragment } from '../mdBlocks';
import { fragment } from '../mdBlocks';
import { Rect } from './pptxModels';
import type { PptTheme } from './pptxModels';
import type { PptStyleSheet } from './pptxStyleSheet';
import { isLight } from './pptxTheme';
import { Align, generateWaveLayers, DEFAULT_LOGO_SCALE, DEFAULT_LOGO_HALIGN, DEFAULT_LOGO_VALIGN } from './pptxLayout';
import type { LaidOutSlide, LaidOutUnit } from './pptxLayout';
import type { WaveLayer } from './pptxModels';
import { zipSync, strToU8 } from '../../libs/fflate';

const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const RP = 'http://schemas.openxmlformats.org/package/2006/relationships';
const P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const REL_SLIDE = `${R}/slide`;
const REL_SLIDEMASTER = `${R}/slideMaster`;
const REL_SLIDELAYOUT = `${R}/slideLayout`;
const REL_THEME = `${R}/theme`;
const REL_PREPSPROPS = `${R}/presProps`;

/** 1pt = 12700 EMU */
function emu(pt: number): number {
  return Math.round(pt * 12700);
}

function xmlEsc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** 取代码块原始文本（LaidOutUnit 未直接存 raw，从 fragments 重建）。 */
function rawText(unit: LaidOutUnit): string {
  return unit.fragments.map((f) => f.text).join('');
}

// ────────────────────────────────────────────────
// 入口
// ────────────────────────────────────────────────

/** 导出 PPTX 字节流（zip）。 */
export function exportPptx(slides: LaidOutSlide[], theme: PptTheme, style: PptStyleSheet): Uint8Array {
  const n = Math.max(1, slides.length);
  const files: Record<string, Uint8Array> = {};
  const add = (name: string, body: string): void => {
    files[name] = strToU8(body);
  };
  add('[Content_Types].xml', contentTypesXml(n));
  add('_rels/.rels', relsDotRels());
  add('docProps/core.xml', coreXml());
  add('docProps/app.xml', appXml(n));
  add('ppt/presentation.xml', presentationXml(n, style));
  add('ppt/_rels/presentation.xml.rels', presentationRels(n));
  add('ppt/presProps.xml', presPropsXml());
  add('ppt/slideMasters/slideMaster1.xml', slideMasterXml());
  add('ppt/slideMasters/_rels/slideMaster1.xml.rels', slideMasterRels());
  add('ppt/slideLayouts/slideLayout1.xml', slideLayoutXml());
  add('ppt/slideLayouts/_rels/slideLayout1.xml.rels', slideLayoutRels());
  add('ppt/theme/theme1.xml', themeXml(theme));
  add('ppt/theme/_rels/theme1.xml.rels', themeRels());
  for (let i = 1; i <= n; i++) {
    add(`ppt/slides/slide${i}.xml`, slideXml(slides[i - 1], theme, style));
    add(`ppt/slides/_rels/slide${i}.xml.rels`, slideRels());
  }
  return zipSync(files);
}

// ────────────────────────────────────────────────
// 包级 XML
// ────────────────────────────────────────────────

function contentTypesXml(n: number): string {
  let sb = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  sb += '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">';
  sb += '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>';
  sb += '<Default Extension="xml" ContentType="application/xml"/>';
  sb += '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>';
  sb += '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>';
  sb += '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>';
  sb += '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>';
  sb += '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>';
  sb += '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>';
  sb += '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>';
  for (let i = 1; i <= n; i++) {
    sb += `<Override PartName="/ppt/slides/slide${i}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>`;
  }
  sb += '</Types>';
  return sb;
}

function relsDotRels(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<Relationships xmlns="${RP}">` +
    `<Relationship Id="rId1" Type="${R}/officeDocument" Target="ppt/presentation.xml"/>` +
    '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
    `<Relationship Id="rId3" Type="${R}/extended-properties" Target="docProps/app.xml"/>` +
    '</Relationships>'
  );
}

function coreXml(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' +
    '<dc:creator>陈律工具箱</dc:creator><cp:lastModifiedBy>陈律工具箱</cp:lastModifiedBy>' +
    '</cp:coreProperties>'
  );
}

function appXml(n: number): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">' +
    `<Slides>${n}</Slides><Company>陈律工具箱</Company></Properties>`
  );
}

function presentationXml(n: number, style: PptStyleSheet): string {
  let sb = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  sb += `<p:presentation xmlns:a="${A}" xmlns:r="${R}" xmlns:p="${P}">`;
  sb += '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>';
  sb += '<p:sldIdLst>';
  for (let i = 1; i <= n; i++) {
    sb += `<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>`;
  }
  sb += '</p:sldIdLst>';
  sb += `<p:sldSz cx="${emu(style.canvasW)}" cy="${emu(style.canvasH)}" type="screen16x9"/>`;
  sb += '<p:notesSz cx="6858000" cy="9144000"/>';
  sb += '</p:presentation>';
  return sb;
}

function presentationRels(n: number): string {
  let sb = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  sb += `<Relationships xmlns="${RP}">`;
  sb += `<Relationship Id="rId1" Type="${REL_SLIDEMASTER}" Target="slideMasters/slideMaster1.xml"/>`;
  for (let i = 1; i <= n; i++) {
    sb += `<Relationship Id="rId${i + 1}" Type="${REL_SLIDE}" Target="slides/slide${i}.xml"/>`;
  }
  sb += `<Relationship Id="rId${n + 2}" Type="${REL_PREPSPROPS}" Target="presProps.xml"/>`;
  sb += '</Relationships>';
  return sb;
}

function presPropsXml(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<p:presentationPr xmlns:a="${A}" xmlns:r="${R}" xmlns:p="${P}"/>`
  );
}

function slideMasterXml(): string {
  const spTree =
    '<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>' +
    '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>';
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<p:sldMaster xmlns:a="${A}" xmlns:r="${R}" xmlns:p="${P}">` +
    `<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>${spTree}</p:cSld>` +
    '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>' +
    '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>' +
    '<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>' +
    '</p:sldMaster>'
  );
}

function slideMasterRels(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<Relationships xmlns="${RP}">` +
    `<Relationship Id="rId1" Type="${REL_SLIDELAYOUT}" Target="../slideLayouts/slideLayout1.xml"/>` +
    `<Relationship Id="rId2" Type="${REL_THEME}" Target="../theme/theme1.xml"/>` +
    '</Relationships>'
  );
}

function slideLayoutXml(): string {
  const spTree =
    '<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>' +
    '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>';
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<p:sldLayout xmlns:a="${A}" xmlns:r="${R}" xmlns:p="${P}" type="blank" preserve="1">` +
    `<p:cSld name="Blank">${spTree}</p:cSld>` +
    '<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>' +
    '</p:sldLayout>'
  );
}

function slideLayoutRels(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<Relationships xmlns="${RP}"><Relationship Id="rId1" Type="${REL_SLIDEMASTER}" Target="../slideMasters/slideMaster1.xml"/></Relationships>`
  );
}

function themeXml(theme: PptTheme): string {
  // 注意：fmtScheme 必须包含 4 个必需子元素（fillStyleLst/lnStyleLst/effectStyleLst/bgFillStyleLst），
  // 且均使用 phClr 占位色（由具体形状的实际填充决定），与主题配色解耦，对任何主题都安全。
  const fmtScheme =
    '<a:fmtScheme name="Office">' +
    '<a:fillStyleLst>' +
    '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>' +
    '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="50000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="35000"><a:schemeClr val="phClr"><a:tint val="37000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="15000"/><a:satMod val="350000"/></a:schemeClr></a:gs></a:gsLst><a:lin ang="16200000" scaled="1"/></a:gradFill>' +
    '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="100000"/><a:shade val="100000"/><a:satMod val="130000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="50000"/><a:shade val="100000"/><a:satMod val="350000"/></a:schemeClr></a:gs></a:gsLst><a:lin ang="16200000" scaled="0"/></a:gradFill>' +
    '</a:fillStyleLst>' +
    '<a:lnStyleLst>' +
    '<a:ln w="9525" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"><a:shade val="95000"/><a:satMod val="105000"/></a:schemeClr></a:solidFill><a:prstDash val="solid"/></a:ln>' +
    '<a:ln w="25400" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>' +
    '<a:ln w="38100" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>' +
    '</a:lnStyleLst>' +
    '<a:effectStyleLst>' +
    '<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="20000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="38000"/></a:srgbClr></a:outerShdw></a:effectLst></a:effectStyle>' +
    '<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="23000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="35000"/></a:srgbClr></a:outerShdw></a:effectLst></a:effectStyle>' +
    '<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="23000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="35000"/></a:srgbClr></a:outerShdw></a:effectLst><a:scene3d><a:camera prst="orthographicFront"><a:rot lat="0" lon="0" rev="0"/></a:camera><a:lightRig rig="threePt" dir="t"><a:rot lat="0" lon="0" rev="1200000"/></a:lightRig></a:scene3d><a:sp3d><a:bevelT w="63500" h="25400"/></a:sp3d></a:effectStyle>' +
    '</a:effectStyleLst>' +
    '<a:bgFillStyleLst>' +
    '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>' +
    '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="40000"/><a:satMod val="350000"/></a:schemeClr></a:gs><a:gs pos="40000"><a:schemeClr val="phClr"><a:tint val="45000"/><a:shade val="99000"/><a:satMod val="350000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="20000"/><a:satMod val="255000"/></a:schemeClr></a:gs></a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="-80000" r="50000" b="180000"/></a:path></a:gradFill>' +
    '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="80000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="30000"/><a:satMod val="200000"/></a:schemeClr></a:gs></a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="50000" r="50000" b="50000"/></a:path></a:gradFill>' +
    '</a:bgFillStyleLst>' +
    '</a:fmtScheme>';
  const objectDefaults =
    '<a:objectDefaults><a:spDef><a:spPr/><a:bodyPr/><a:lstStyle/><a:style><a:lnRef idx="1"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="3"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="2"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></a:style></a:spDef><a:lnDef><a:spPr/><a:bodyPr/><a:lstStyle/><a:style><a:lnRef idx="2"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="0"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="1"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="tx1"/></a:fontRef></a:style></a:lnDef></a:objectDefaults>';
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<a:theme xmlns:a="${A}" name="Office Theme"><a:themeElements>` +
    '<a:clrScheme name="Custom"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>' +
    `<a:dk2><a:srgbClr val="${theme.titleColor}"/></a:dk2><a:lt2><a:srgbClr val="F2F2F2"/></a:lt2>` +
    `<a:accent1><a:srgbClr val="${theme.accent}"/></a:accent1><a:accent2><a:srgbClr val="${theme.accent}"/></a:accent2>` +
    `<a:accent3><a:srgbClr val="${theme.accent}"/></a:accent3><a:accent4><a:srgbClr val="${theme.accent}"/></a:accent4>` +
    `<a:accent5><a:srgbClr val="${theme.accent}"/></a:accent5><a:accent6><a:srgbClr val="${theme.accent}"/></a:accent6>` +
    `<a:hlink><a:srgbClr val="${theme.accent}"/></a:hlink><a:folHlink><a:srgbClr val="${theme.accent}"/></a:folHlink></a:clrScheme>` +
    '<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:majorFont>' +
    '<a:minorFont><a:latin typeface="Arial"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme>' +
    fmtScheme +
    objectDefaults +
    '<a:extraClrSchemeLst/></a:themeElements></a:theme>'
  );
}

function themeRels(): string {
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' + `<Relationships xmlns="${RP}"></Relationships>`;
}

function slideRels(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<Relationships xmlns="${RP}"><Relationship Id="rId1" Type="${REL_SLIDELAYOUT}" Target="../slideLayouts/slideLayout1.xml"/></Relationships>`
  );
}

// ────────────────────────────────────────────────
// 幻灯片内容
// ────────────────────────────────────────────────

function slideXml(slide: LaidOutSlide, theme: PptTheme, style: PptStyleSheet): string {
  const sb: string[] = [];
  sb.push('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>');
  sb.push(`<p:sld xmlns:a="${A}" xmlns:r="${R}" xmlns:p="${P}">`);
  sb.push('<p:cSld>');
  const bg = slide.cover ? theme.coverBg : slide.deco?.accentBg === true ? theme.accent : theme.bg;
  sb.push(`<p:bg><p:bgPr><a:solidFill><a:srgbClr val="${bg}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>`);
  sb.push('<p:spTree>');
  sb.push('<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>');
  sb.push('<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>');
  let id = 2;
  // 底部波浪装饰（内容页、目录页、结尾页，开关打开时）——置于最底层，透明度叠加出柔和层次
  if (slide.deco?.wave === true) {
    const waveColor = slide.deco.waveColor ?? theme.accent;
    const layers = generateWaveLayers(waveColor, style);
    for (const layer of layers) {
      sb.push(waveShapeXml(layer, style, id++));
    }
  }
  // 底部直线色块装饰（与波浪并列、可独立开关）：满屏宽、贴齐页底、高度 = 画布高 1/N（默认 1/60，可在设置中调），
  // 颜色固定跟随主题主色调（theme.accent），置于波浪之上、内容之下
  if (slide.deco?.bottomBar === true) {
    const bc = theme.accent;
    const barH = Math.max(1, slide.deco.bottomBarH > 0 ? slide.deco.bottomBarH : Math.floor(style.canvasH / 60));
    sb.push(barShapeXml(new Rect(0, style.canvasH - barH, style.canvasW, barH), bc, id++));
  }
  // Logo 装饰（右下角）：红色斜角块 + LAWYER.C 文字
  if (slide.deco?.logo === true) {
    sb.push(logoShapeXml(style, id++));
    id++; // 红块 + 文字两个 shape 各占一个 id
  }
  // 标题/引用左侧强调竖条、封面色条、强调线等装饰矩形
  const barColor = slide.deco?.barColor ?? theme.accent;
  slide.deco?.bars?.forEach((b) => {
    sb.push(barShapeXml(b, barColor, id++));
  });
  // 居中对齐的 H3：竖线以内联方式随文本框一起绘制（与预览端一致，避免与文字分离）。
  // 竖线 x 坐标根据文本内容估算位置（文本框内居中 → 文本起始 ≈ 框左 + (框宽-文宽)/2），
  // 使竖线紧贴文本左侧而非文本框左边界。
  for (const unit of slide.units) {
    if (unit.type === BlockType.H3 && unit.align === Align.CENTER) {
      const barW = 3;
      const gapPt = 6; // 竖线与文字间距（pt，与预览端一致）
      const barH = unit.h;
      // 估算文本像素宽度：字符数 × 字号 × 0.6（中西文混排平均字符宽系数）
      const estTextW = Math.max(40, Math.round(unit.fragments.reduce((s, f) => s + f.text.length, 0) * unit.fontSize * 0.6));
      // 文本在框内居中时的起始 x（留出竖线+间距的空间）
      const totalInlineW = barW + gapPt + estTextW;
      const barX = Math.max(unit.x, Math.round(unit.x + (unit.w - totalInlineW) / 2));
      sb.push(barShapeXml(new Rect(barX, unit.y, barW, barH), barColor, id++));
    }
  }
  // 引用块（Markdown `>`）浅色圆角背景底色，用主题 quoteBg 色（与 H3 竖条 accent 区分）
  slide.deco?.quoteBg?.forEach((b) => {
    sb.push(roundRectShapeXml(b, theme.quoteBg, id++));
  });
  for (const unit of slide.units) {
    if (unit.type === BlockType.TABLE && unit.table != null) {
      id = tableShapesXml(sb, unit, slide.cover, slide.deco?.accentBg === true, theme, style, id);
    } else {
      sb.push(shapeXml(unit, slide.cover, slide.deco?.accentBg === true, theme, style, id++));
    }
  }
  sb.push('</p:spTree></p:cSld>');
  sb.push('<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>');
  sb.push('</p:sld>');
  return sb.join('');
}

function shapeXml(unit: LaidOutUnit, cover: boolean, accentBg: boolean, theme: PptTheme, style: PptStyleSheet, id: number): string {
  // 封面背景可能偏亮（如简约灰白），按背景明暗自适应前景色；强调背景始终白字
  const coverText = isLight(theme.coverBg) ? '222222' : 'FFFFFF';
  const color =
    unit.color != null ? unit.color : // 显式颜色覆盖（如目录标题反白）
    accentBg ? 'FFFFFF' :
    cover ? coverText :
    unit.type >= BlockType.H1 && unit.type <= BlockType.H6 ? theme.titleColor :
    theme.bodyColor;
  // 整单元加粗（如金句）：把片段统一标记为 bold，保证导出与预览一致
  const boldFrags = unit.bold ? unit.fragments.map((f) => ({ ...f, bold: true })) : unit.fragments;
  const bodyPr = '<a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t"><a:normAutofit fontScale="95000"/></a:bodyPr><a:lstStyle/>';
  let paras: string;
  if (unit.type === BlockType.CODE) {
    // 颜色统一用已算好的 color（含遇色块反色）：COVER 页/accentBg 上 CODE/LIST 也反色，与预览端一致
    paras = codeParas(rawText(unit), color, style, unit.fontFamily, unit.gapAfter, unit.latinFont);
  } else if (unit.type === BlockType.BULLET_LIST || unit.type === BlockType.ORDERED_LIST) {
    paras = listParas(unit, color, unit.fontFamily, unit.gapAfter, unit.latinFont);
  } else {
    paras = paragraphsFromFrags(boldFrags, unit.fontSize, color, unit.align, false, unit.fontFamily, unit.gapAfter, unit.latinFont);
  }
  const fill = unit.type === BlockType.CODE ? `<a:solidFill><a:srgbClr val="${theme.codeBg}"/></a:solidFill>` : '';
  // 文本框高度留充足余量（20% 或至少 16pt），避免 CJK 混排行高估算误差导致 PPTX 溢出下边距
  const h = Math.max(unit.h + 16, Math.round(unit.h * 1.2));
  return (
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="TextBox ${id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(unit.x)}" y="${emu(unit.y)}"/><a:ext cx="${emu(unit.w)}" cy="${emu(h)}"/></a:xfrm>` +
    `<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>${fill}</p:spPr>` +
    `<p:txBody>${bodyPr}${paras}</p:txBody></p:sp>`
  );
}

/** 标题/引用左侧强调竖条（矩形形状）。 */
function barShapeXml(rect: Rect, color: string, id: number): string {
  return (
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Deco ${id}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(rect.x)}" y="${emu(rect.y)}"/><a:ext cx="${emu(rect.w)}" cy="${emu(rect.h)}"/></a:xfrm>` +
    `<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="${color}"/></a:solidFill></p:spPr>` +
    '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>'
  );
}

/**
 * Logo 装饰（右下角）：红色斜角四边形 + LAWYER.C 文字。
 * 占画布宽 20%，等比缩放，红块用 custGeom 绘制自定义四边形。
 */
function logoShapeXml(style: PptStyleSheet, id: number): string {
  const cw = style.canvasW; // 720pt
  const ch = style.canvasH; // 405pt
  const logoW = Math.round(cw * DEFAULT_LOGO_SCALE);
  const logoH = Math.round((logoW * 180) / 640);
  const x = DEFAULT_LOGO_HALIGN === 'right' ? cw - logoW : 0;
  const y = DEFAULT_LOGO_VALIGN === 'bottom' ? ch - logoH : 0;
  const logoRed = 'D31B29';

  // 红色四边形：4 个顶点 (xCol, yCol) 使用 custGeom
  // 顶点坐标相对于 shape 自身 (0,0) 到 (logoW, logoH)
  const x0 = 0;
  const y0 = 0;
  const x1 = Math.round(logoW * 0.195);
  const y1 = Math.round(logoH * 0.02);
  const x2 = Math.round(logoW * 0.17);
  const y2 = logoH;
  const x3 = 0;
  const y3 = logoH;

  const redShape =
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="LogoRed"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(x)}" y="${emu(y)}"/><a:ext cx="${emu(logoW)}" cy="${emu(logoH)}"/></a:xfrm>` +
    '<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="l" t="t" r="r" b="b"/>' +
    `<a:pathLst><a:path w="${emu(logoW)}" h="${emu(logoH)}">` +
    `<a:moveTo><a:pt x="${emu(x0)}" y="${emu(y0)}"/></a:moveTo>` +
    `<a:lnTo><a:pt x="${emu(x1)}" y="${emu(y1)}"/></a:lnTo>` +
    `<a:lnTo><a:pt x="${emu(x2)}" y="${emu(y2)}"/></a:lnTo>` +
    `<a:lnTo><a:pt x="${emu(x3)}" y="${emu(y3)}"/></a:lnTo>` +
    '<a:close/></a:path></a:pathLst></a:custGeom>' +
    `<a:solidFill><a:srgbClr val="${logoRed}"/></a:solidFill></p:spPr>` +
    '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>';

  // LAWYER.C 文字：白色，字号按比例缩放
  const textFontSize = Math.round((logoW * 104) / 640); // ~23pt
  const textX = x + Math.round(logoW * 0.21);
  const textY = y + Math.round(logoH * 0.14);
  const textW = Math.round(logoW * 0.58); // 文字区域宽
  const textH = Math.round(logoH * 0.72); // 文字区域高

  const textShape =
    `<p:sp><p:nvSpPr><p:cNvPr id="${id + 1}" name="LogoText"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(textX)}" y="${emu(textY)}"/><a:ext cx="${emu(textW)}" cy="${emu(textH)}"/></a:xfrm>` +
    '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>' +
    '<p:txBody><a:bodyPr wrap="none" rtlCol="0" anchor="t"/>' +
    `<a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="${textFontSize * 100}" b="1" dirty="0">` +
    '<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>' +
    '<a:latin typeface="Arial"/></a:rPr><a:t>LAWYER.C</a:t></a:r></a:p>' +
    '</p:txBody></p:sp>';

  return redShape + textShape;
}

/** 引用块浅色圆角背景（roundRect，圆角 adj 取值约 20000 = 约 1/5 短边，柔和圆角卡片效果）。 */
function roundRectShapeXml(rect: Rect, color: string, id: number): string {
  return (
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="QuoteBg ${id}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(rect.x)}" y="${emu(rect.y)}"/><a:ext cx="${emu(rect.w)}" cy="${emu(rect.h)}"/></a:xfrm>` +
    '<a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 20000"/></a:avLst></a:prstGeom>' +
    `<a:solidFill><a:srgbClr val="${color}"/></a:solidFill></p:spPr>` +
    '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>'
  );
}

/**
 * 底部波浪装饰（自定义几何路径）。
 * 使用 OOXML <a:custGeom> 绘制闭合填充区域，每层波浪一个独立 shape。
 * 注意：三次贝塞尔曲线在 DrawingML 中的正确元素名是 <a:cubicBezTo>（含 3 个 <a:pt>），
 * 坐标统一用 EMU，与 <a:ext> 形状尺寸保持一致，C1 连续保证波峰波谷圆润无折角。
 */
function waveShapeXml(layer: WaveLayer, style: PptStyleSheet, id: number): string {
  const w = style.canvasW; // 720pt
  const h = style.canvasH; // 405pt
  const EW = emu(w); // 路径坐标空间（EMU）
  const EH = emu(h);
  const pts = layer.controlPoints;

  let pathSb = '';
  if (pts.length >= 6) {
    // moveTo 起点（左下角，归一化 (0,1)）
    pathSb += `<a:moveTo><a:pt x="${Math.round(pts[0] * EW)}" y="${Math.round(pts[1] * EH)}"/></a:moveTo>`;

    // 三次贝塞尔弧线段：每 6 个值 = (c1, c2, 终点)，C1 连续更平滑
    let i = 2;
    while (i + 5 < pts.length - 4) {
      // 保留末尾 4 个值给闭合线段
      const c1x = Math.round(pts[i] * EW);
      const c1y = Math.round(pts[i + 1] * EH);
      const c2x = Math.round(pts[i + 2] * EW);
      const c2y = Math.round(pts[i + 3] * EH);
      const ex = Math.round(pts[i + 4] * EW);
      const ey = Math.round(pts[i + 5] * EH);
      pathSb += `<a:cubicBezTo><a:pt x="${c1x}" y="${c1y}"/><a:pt x="${c2x}" y="${c2y}"/><a:pt x="${ex}" y="${ey}"/></a:cubicBezTo>`;
      i += 6;
    }

    // 闭合：右下角 → 左下角
    if (i + 1 < pts.length) {
      pathSb += `<a:lnTo><a:pt x="${Math.round(pts[i] * EW)}" y="${Math.round(pts[i + 1] * EH)}"/></a:lnTo>`;
      i += 2;
    }
    if (i + 1 < pts.length) {
      pathSb += `<a:lnTo><a:pt x="${Math.round(pts[i] * EW)}" y="${Math.round(pts[i + 1] * EH)}"/></a:lnTo>`;
    }
    pathSb += '<a:close/>';
  }

  // 透明度：OOXML srgbClr 的 alpha 子元素单位为「千分之一百分比」，0.40 → 40000
  const alphaChild = layer.alpha < 0.999 ? `<a:alpha val="${Math.round(layer.alpha * 100000)}"/>` : '';
  return (
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Wave ${id}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${emu(w)}" cy="${emu(h)}"/></a:xfrm>` +
    '<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/>' +
    `<a:pathLst><a:path w="${Math.round(EW)}" h="${Math.round(EH)}">${pathSb}</a:path></a:pathLst>` +
    '</a:custGeom>' +
    `<a:solidFill><a:srgbClr val="${layer.color}">${alphaChild}</a:srgbClr></a:solidFill>` +
    '</p:spPr>' +
    '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>'
  );
}

// ── 表格（矩形 + 文本框手工绘制，保证矢量可编辑）──
function tableShapesXml(
  sb: string[],
  unit: LaidOutUnit,
  cover: boolean,
  accentBg: boolean,
  theme: PptTheme,
  style: PptStyleSheet,
  startId: number
): number {
  const tr = unit.table;
  if (tr == null) return startId;
  let id = startId;
  const x0 = unit.x;
  const y0 = unit.y;
  const w = unit.w;
  const colW = tr.colW;
  const xs: number[] = [x0];
  for (const cw of colW) xs.push(xs[xs.length - 1] + cw);
  const cellColor = accentBg ? 'FFFFFF' : cover ? (isLight(theme.coverBg) ? '222222' : 'FFFFFF') : theme.bodyColor;
  const gridColor = 'C8C8C8';

  // 表头填充（强调色）
  if (tr.header.length > 0) {
    sb.push(barShapeXml(new Rect(x0, y0, w, tr.headerH), theme.accent, id++));
  }

  // 单元格文本（表头白字加粗，正文按规定色与对齐）
  let y = y0;
  const drawRow = (cells: InlineFragment[][], h: number, isHeader: boolean): void => {
    for (let j = 0; j < cells.length; j++) {
      const cw = colW[j] ?? colW[colW.length - 1] ?? 0;
      const cx = xs[j] ?? xs[xs.length - 1] ?? x0;
      const color = isHeader ? 'FFFFFF' : cellColor;
      const fs = isHeader ? tr.headerFs : tr.cellFs;
      const align = tr.colAlign[j] ?? TableAlign.LEFT;
      sb.push(cellTextXml(cx, y, cw, h, cells[j], color, fs, align, style, id++));
    }
    y += h;
  };
  if (tr.header.length > 0) drawRow(tr.header, tr.headerH, true);
  tr.rows.forEach((row, i) => drawRow(row, tr.rowHs[i], false));

  // 网格线（横向 + 纵向 + 外框）
  const lineYs: number[] = [];
  if (tr.header.length > 0) lineYs.push(y0 + tr.headerH);
  let yy = y0 + tr.headerH;
  for (const hh of tr.rowHs) {
    yy += hh;
    lineYs.push(yy);
  }
  for (const ly of lineYs) sb.push(barShapeXml(new Rect(x0, ly - 1, w, 1), gridColor, id++));
  for (let j = 1; j < xs.length - 1; j++) sb.push(barShapeXml(new Rect(xs[j], y0, 1, unit.h), gridColor, id++));
  sb.push(barShapeXml(new Rect(x0, y0, w, 1), gridColor, id++));
  sb.push(barShapeXml(new Rect(x0, y0 + unit.h - 1, w, 1), gridColor, id++));
  sb.push(barShapeXml(new Rect(x0, y0, 1, unit.h), gridColor, id++));
  sb.push(barShapeXml(new Rect(x0 + w - 1, y0, 1, unit.h), gridColor, id++));

  return id;
}

/** 单个单元格文本框（带内边距，垂直居中，按列对齐）。 */
function cellTextXml(
  cx: number,
  y: number,
  cw: number,
  h: number,
  frags: InlineFragment[],
  color: string,
  fs: number,
  align: TableAlign,
  style: PptStyleSheet,
  id: number
): string {
  const a = align === TableAlign.CENTER ? Align.CENTER : align === TableAlign.RIGHT ? Align.RIGHT : Align.LEFT;
  const bodyPr =
    `<a:bodyPr wrap="square" lIns="${emu(style.tablePad)}" tIns="${emu(style.tablePad)}" ` +
    `rIns="${emu(style.tablePad)}" bIns="0" anchor="ctr"><a:noAutofit/></a:bodyPr><a:lstStyle/>`;
  const paras = paragraphsFromFrags(frags, fs, color, a, false, style.bodyFont, 0, style.latinFont);
  return textBoxXml(cx, y, cw, h, bodyPr, paras, id);
}

function textBoxXml(cx: number, y: number, cw: number, h: number, bodyPr: string, paras: string, id: number): string {
  return (
    `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Cell ${id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>` +
    `<p:spPr><a:xfrm><a:off x="${emu(cx)}" y="${emu(y)}"/><a:ext cx="${emu(cw)}" cy="${emu(h)}"/></a:xfrm>` +
    '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>' +
    `<p:txBody>${bodyPr}${paras}</p:txBody></p:sp>`
  );
}

// ── 文本片段 → 段落 ──
function paragraphsFromFrags(
  frags: InlineFragment[],
  fs: number,
  color: string,
  align: Align,
  isCode: boolean,
  fontFamily: string = '',
  gapAfter: number = 0,
  latinFont: string = ''
): string {
  const paras: string[] = [];
  let cur: InlineFragment[] = [];
  for (const f of frags) {
    const parts = f.text.split('\n');
    parts.forEach((part, i) => {
      if (i > 0) {
        // 段间（非块尾）施加段后距
        paras.push(buildPara(cur, fs, color, align, isCode, fontFamily, gapAfter, latinFont));
        cur = [];
      }
      if (part !== '') cur.push({ ...f, text: part });
    });
  }
  paras.push(buildPara(cur, fs, color, align, isCode, fontFamily, 0, latinFont));
  return paras.join('');
}

function buildPara(
  frags: InlineFragment[],
  fs: number,
  color: string,
  align: Align,
  isCode: boolean,
  fontFamily: string = '',
  gapAfter: number = 0,
  latinFont: string = ''
): string {
  const algn = align === Align.CENTER ? 'ctr' : align === Align.RIGHT ? 'r' : 'l';
  // 段后距：OOXML <a:spcAft><a:spcPts val> 单位为 1/100 磅，故 pt 值 ×100
  const spcAft = gapAfter > 0 ? `<a:spcAft><a:spcPts val="${gapAfter * 100}"/></a:spcAft>` : '';
  const pPr = `<a:pPr algn="${algn}">${spcAft}</a:pPr>`;
  const runs = frags.length === 0 ? '' : frags.map((f) => runXml(f, fs, color, isCode, fontFamily, latinFont)).join('');
  return `<a:p>${pPr}${runs}</a:p>`;
}

function runXml(f: InlineFragment, fs: number, color: string, isCode: boolean, fontFamily: string = '', latinFont: string = ''): string {
  // 西文字槽 <a:latin>/<a:cs> 用西文字体（代码固定 Consolas 等宽，其余默认 Arial 无衬线）；
  // 东亚字槽 <a:ea> 用中文/东亚字体（默认微软雅黑 无衬线）。代码块的 CJK 部分仍使用微软雅黑
  // 等宽的无衬线字体，避免把 Consolas（拉丁等宽）写入东亚槽导致中文回落到衬线默认字体。
  const latin = isCode ? 'Consolas' : latinFont !== '' ? latinFont : 'Arial';
  const ea = fontFamily !== '' ? fontFamily : '微软雅黑';
  const attrs: string[] = [];
  attrs.push('lang="zh-CN"');
  attrs.push('altLang="en-US"');
  attrs.push(`sz="${fs * 100}"`);
  if (f.bold) attrs.push('b="1"');
  if (f.italic) attrs.push('i="1"');
  if (f.strike) attrs.push('strike="s"');
  if (f.link != null) attrs.push('u="s"');
  const rPr =
    `<a:rPr ${attrs.join(' ')} dirty="1"><a:solidFill><a:srgbClr val="${color}"/></a:solidFill>` +
    `<a:latin typeface="${latin}"/><a:ea typeface="${ea}"/><a:cs typeface="${latin}"/></a:rPr>`;
  return `<a:r>${rPr}<a:t>${xmlEsc(f.text)}</a:t></a:r>`;
}

// ── 列表 ──
/** 每级缩进的 PPTX 左边距（EMU），约 0.5in = 457200 EMU。 */
const INDENT_EMU = 457200;

function listParas(unit: LaidOutUnit, color: string, fontFamily: string = '', gapAfter: number = 0, latinFont: string = ''): string {
  const sb: string[] = [];
  unit.listItems.forEach((item, idx) => {
    // 前缀：顶层用列表类型前缀（"1. " / "•  "）；嵌套层用缩进+短横线
    // 有序列表：优先使用 MD 原文编号（item.number），无则 fallback 到自动编号
    const prefix =
      item.indent === 0 && unit.ordered
        ? `${item.number ?? unit.listStart + idx + 1}. `
        : item.indent === 0
          ? '•  '
          : `  ${'  '.repeat(item.indent - 1)}- `;
    const all = [fragment(prefix), ...item.fragments];
    // 项间（非块尾）施加段后距；块尾间距由布局负责，避免双重
    const gap = idx === unit.listItems.length - 1 ? 0 : gapAfter;
    const para = buildPara(all, unit.fontSize, color, Align.LEFT, false, fontFamily, gap, latinFont);
    // 嵌套项追加 PPTX 段落左缩进（<a:pPr><a:marL>）
    if (item.indent > 0) {
      // buildPara 输出 <a:pPr algn="l">…</a:pPr>，需在标签内插入 <a:marL>
      const indented = para.replace('<a:pPr ', `<a:pPr><a:marL val="${item.indent * INDENT_EMU}"/> `);
      sb.push(indented);
    } else {
      sb.push(para);
    }
  });
  return sb.join('');
}

// ── 代码块 ──
function codeParas(raw: string, color: string, style: PptStyleSheet, fontFamily: string = '', gapAfter: number = 0, latinFont: string = ''): string {
  // 代码块内部行间距由行距(lineMult)决定，不叠加 spcAft；块尾间距由布局 y 步进负责
  const sb: string[] = [];
  const lines = raw === '' ? [' '] : raw.split('\n');
  for (const line of lines) {
    sb.push(buildPara([fragment(line === '' ? ' ' : line)], style.fsCode, color, Align.LEFT, true, fontFamily, 0, latinFont));
  }
  return sb.join('');
}
