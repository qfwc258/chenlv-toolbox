package com.wb.mdgw.pptx

import com.wb.mdgw.pptx.PptLayoutEngine.Align
import com.wb.mdgw.pptx.PptLayoutEngine.LaidOutSlide
import com.wb.mdgw.pptx.PptLayoutEngine.LaidOutUnit
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PPTX 原生导出引擎（手写 OOXML，零第三方依赖）。
 *
 * 输出标准 .pptx（zip 包内 XML），每个块渲染为一个原生可编辑文本框 Shape，
 * WPS / Office 完全可二次编辑，保留加粗/斜体/删除线/链接色/列表编号。
 * 不依赖图片或渲染快照，100% 矢量可编辑。
 */
object PptExportEngine {
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val RP = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val REL_SLIDE = "$R/slide"
    private const val REL_SLIDEMASTER = "$R/slideMaster"
    private const val REL_SLIDELAYOUT = "$R/slideLayout"
    private const val REL_THEME = "$R/theme"
    private const val REL_PREPSPROPS = "$R/presProps"

    /** 1pt = 12700 EMU */
    private fun emu(pt: Int): Long = pt * 12700L

    private fun xmlEsc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ────────────────────────────────────────────────
    // 入口
    // ────────────────────────────────────────────────

    fun exportPptx(slides: List<LaidOutSlide>, theme: PptTheme, style: PptStyleSheet, out: OutputStream) {
        val n = slides.size.coerceAtLeast(1)
        ZipOutputStream(out).use { zip ->
            entry(zip, "[Content_Types].xml") { contentTypesXml(n) }
            entry(zip, "_rels/.rels") { relsDotRels() }
            entry(zip, "docProps/core.xml") { coreXml() }
            entry(zip, "docProps/app.xml") { appXml(n) }
            entry(zip, "ppt/presentation.xml") { presentationXml(n, style) }
            entry(zip, "ppt/_rels/presentation.xml.rels") { presentationRels(n) }
            entry(zip, "ppt/presProps.xml") { presPropsXml() }
            entry(zip, "ppt/slideMasters/slideMaster1.xml") { slideMasterXml() }
            entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels") { slideMasterRels() }
            entry(zip, "ppt/slideLayouts/slideLayout1.xml") { slideLayoutXml() }
            entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels") { slideLayoutRels() }
            entry(zip, "ppt/theme/theme1.xml") { themeXml(theme) }
            entry(zip, "ppt/theme/_rels/theme1.xml.rels") { themeRels() }
            for (i in 1..n) {
                entry(zip, "ppt/slides/slide$i.xml") { slideXml(slides[i - 1], theme, style) }
                entry(zip, "ppt/slides/_rels/slide$i.xml.rels") { slideRels() }
            }
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, body: () -> String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ────────────────────────────────────────────────
    // 包级 XML
    // ────────────────────────────────────────────────

    private fun contentTypesXml(n: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        sb.append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        sb.append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        sb.append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        sb.append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        sb.append("""<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>""")
        sb.append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        sb.append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        for (i in 1..n) {
            sb.append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        }
        sb.append("""</Types>""")
        return sb.toString()
    }

    private fun relsDotRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="$RP">""" +
            """<Relationship Id="rId1" Type="$R/officeDocument" Target="ppt/presentation.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>""" +
            """<Relationship Id="rId3" Type="$R/extended-properties" Target="docProps/app.xml"/>""" +
            """</Relationships>"""

    private fun coreXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:creator>陈律工具箱</dc:creator><cp:lastModifiedBy>陈律工具箱</cp:lastModifiedBy>""" +
            """</cp:coreProperties>"""

    private fun appXml(n: Int): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">""" +
            """<Slides>$n</Slides><Company>陈律工具箱</Company></Properties>"""

    private fun presentationXml(n: Int, style: PptStyleSheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<p:presentation xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">""")
        sb.append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
        sb.append("""<p:sldIdLst>""")
        for (i in 1..n) {
            sb.append("""<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>""")
        }
        sb.append("""</p:sldIdLst>""")
        sb.append("""<p:sldSz cx="${emu(style.canvasW)}" cy="${emu(style.canvasH)}" type="screen16x9"/>""")
        sb.append("""<p:notesSz cx="6858000" cy="9144000"/>""")
        sb.append("""</p:presentation>""")
        return sb.toString()
    }

    private fun presentationRels(n: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<Relationships xmlns="$RP">""")
        sb.append("""<Relationship Id="rId1" Type="$REL_SLIDEMASTER" Target="slideMasters/slideMaster1.xml"/>""")
        for (i in 1..n) {
            sb.append("""<Relationship Id="rId${i + 1}" Type="$REL_SLIDE" Target="slides/slide$i.xml"/>""")
        }
        sb.append("""<Relationship Id="rId${n + 2}" Type="$REL_PREPSPROPS" Target="presProps.xml"/>""")
        sb.append("""</Relationships>""")
        return sb.toString()
    }

    private fun presPropsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:presentationPr xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"/>"""

    private fun slideMasterXml(): String {
        val spTree = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
                """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<p:sldMaster xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">""" +
                """<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>$spTree</p:cSld>""" +
                """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
                """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
                """<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>""" +
                """</p:sldMaster>"""
    }

    private fun slideMasterRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="$RP">""" +
            """<Relationship Id="rId1" Type="$REL_SLIDELAYOUT" Target="../slideLayouts/slideLayout1.xml"/>""" +
            """<Relationship Id="rId2" Type="$REL_THEME" Target="../theme/theme1.xml"/>""" +
            """</Relationships>"""

    private fun slideLayoutXml(): String {
        val spTree = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
                """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<p:sldLayout xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" type="blank" preserve="1">""" +
                """<p:cSld name="Blank">$spTree</p:cSld>""" +
                """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>""" +
                """</p:sldLayout>"""
    }

    private fun slideLayoutRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="$RP"><Relationship Id="rId1" Type="$REL_SLIDEMASTER" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private fun themeXml(theme: PptTheme): String {
        // 注意：fmtScheme 必须包含 4 个必需子元素（fillStyleLst/lnStyleLst/effectStyleLst/bgFillStyleLst），
        // 且均使用 phClr 占位色（由具体形状的实际填充决定），与主题配色解耦，对任何主题都安全。
        // 之前为空 <a:fmtScheme/> 属 schema 违例，WPS 严格校验会拒载主题 → 整页渲染链断裂 → 全部页空白。
        val fmtScheme = """<a:fmtScheme name="Office">""" +
                """<a:fillStyleLst>""" +
                """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
                """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="50000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="35000"><a:schemeClr val="phClr"><a:tint val="37000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="15000"/><a:satMod val="350000"/></a:schemeClr></a:gs></a:gsLst><a:lin ang="16200000" scaled="1"/></a:gradFill>""" +
                """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="100000"/><a:shade val="100000"/><a:satMod val="130000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:tint val="50000"/><a:shade val="100000"/><a:satMod val="350000"/></a:schemeClr></a:gs></a:gsLst><a:lin ang="16200000" scaled="0"/></a:gradFill>""" +
                """</a:fillStyleLst>""" +
                """<a:lnStyleLst>""" +
                """<a:ln w="9525" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"><a:shade val="95000"/><a:satMod val="105000"/></a:schemeClr></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
                """<a:ln w="25400" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
                """<a:ln w="38100" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln>""" +
                """</a:lnStyleLst>""" +
                """<a:effectStyleLst>""" +
                """<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="20000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="38000"/></a:srgbClr></a:outerShdw></a:effectLst></a:effectStyle>""" +
                """<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="23000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="35000"/></a:srgbClr></a:outerShdw></a:effectLst></a:effectStyle>""" +
                """<a:effectStyle><a:effectLst><a:outerShdw blurRad="40000" dist="23000" dir="5400000" rotWithShape="0"><a:srgbClr val="000000"><a:alpha val="35000"/></a:srgbClr></a:outerShdw></a:effectLst><a:scene3d><a:camera prst="orthographicFront"><a:rot lat="0" lon="0" rev="0"/></a:camera><a:lightRig rig="threePt" dir="t"><a:rot lat="0" lon="0" rev="1200000"/></a:lightRig></a:scene3d><a:sp3d><a:bevelT w="63500" h="25400"/></a:sp3d></a:effectStyle>""" +
                """</a:effectStyleLst>""" +
                """<a:bgFillStyleLst>""" +
                """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
                """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="40000"/><a:satMod val="350000"/></a:schemeClr></a:gs><a:gs pos="40000"><a:schemeClr val="phClr"><a:tint val="45000"/><a:shade val="99000"/><a:satMod val="350000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="20000"/><a:satMod val="255000"/></a:schemeClr></a:gs></a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="-80000" r="50000" b="180000"/></a:path></a:gradFill>""" +
                """<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="80000"/><a:satMod val="300000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="30000"/><a:satMod val="200000"/></a:schemeClr></a:gs></a:gsLst><a:path path="circle"><a:fillToRect l="50000" t="50000" r="50000" b="50000"/></a:path></a:gradFill>""" +
                """</a:bgFillStyleLst>""" +
                """</a:fmtScheme>"""
        val objectDefaults = """<a:objectDefaults><a:spDef><a:spPr/><a:bodyPr/><a:lstStyle/><a:style><a:lnRef idx="1"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="3"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="2"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></a:style></a:spDef><a:lnDef><a:spPr/><a:bodyPr/><a:lstStyle/><a:style><a:lnRef idx="2"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="0"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="1"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="tx1"/></a:fontRef></a:style></a:lnDef></a:objectDefaults>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<a:theme xmlns:a="$A" name="Office Theme"><a:themeElements>""" +
                """<a:clrScheme name="Custom"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>""" +
                """<a:dk2><a:srgbClr val="${theme.titleColor}"/></a:dk2><a:lt2><a:srgbClr val="F2F2F2"/></a:lt2>""" +
                """<a:accent1><a:srgbClr val="${theme.accent}"/></a:accent1><a:accent2><a:srgbClr val="${theme.accent}"/></a:accent2>""" +
                """<a:accent3><a:srgbClr val="${theme.accent}"/></a:accent3><a:accent4><a:srgbClr val="${theme.accent}"/></a:accent4>""" +
                """<a:accent5><a:srgbClr val="${theme.accent}"/></a:accent5><a:accent6><a:srgbClr val="${theme.accent}"/></a:accent6>""" +
                """<a:hlink><a:srgbClr val="${theme.accent}"/></a:hlink><a:folHlink><a:srgbClr val="${theme.accent}"/></a:folHlink></a:clrScheme>""" +
                """<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:majorFont>""" +
                """<a:minorFont><a:latin typeface="Arial"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme>""" +
                fmtScheme +
                objectDefaults +
                """<a:extraClrSchemeLst/></a:themeElements></a:theme>"""
    }

    private fun themeRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="$RP"></Relationships>"""

    private fun slideRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="$RP"><Relationship Id="rId1" Type="$REL_SLIDELAYOUT" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""

    // ────────────────────────────────────────────────
    // 幻灯片内容
    // ────────────────────────────────────────────────

    private fun slideXml(slide: LaidOutSlide, theme: PptTheme, style: PptStyleSheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P">""")
        sb.append("""<p:cSld>""")
        val bg = when {
            slide.cover -> theme.coverBg
            slide.deco?.accentBg == true -> theme.accent
            else -> theme.bg
        }
        sb.append("""<p:bg><p:bgPr><a:solidFill><a:srgbClr val="$bg"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>""")
        sb.append("""<p:spTree>""")
        sb.append("""<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""")
        sb.append("""<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""")
        var id = 2
        // 底部波浪装饰（内容页、目录页、结尾页，开关打开时）——置于最底层，透明度叠加出柔和层次
        if (slide.deco?.wave == true) {
            val waveColor = slide.deco.waveColor ?: theme.accent
            val layers = PptLayoutEngine.generateWaveLayers(waveColor)
            for (layer in layers) {
                sb.append(waveShapeXml(layer, style, id++))
            }
        }
        // 底部直线色块装饰（与波浪并列、可独立开关）：满屏宽、贴齐页底、高度 = 画布高 1/N（默认 1/60，可在设置中调），
        // 颜色固定跟随主题主色调（theme.accent），置于波浪之上、内容之下
        if (slide.deco?.bottomBar == true) {
            val bc = theme.accent
            val barH = (slide.deco.bottomBarH.takeIf { it > 0 } ?: (style.canvasH / 60)).coerceAtLeast(1)
            sb.append(barShapeXml(Rect(0, style.canvasH - barH, style.canvasW, barH), bc, id++))
        }
        // Logo 装饰（右下角）：红色斜角块 + LAWYER.C 文字
        if (slide.deco?.logo == true) {
            sb.append(logoShapeXml(style, id++))
            id++ // 红块 + 文字两个 shape 各占一个 id
        }
        // 标题/引用左侧强调竖条、封面色条、强调线等装饰矩形
        val barColor = slide.deco?.barColor ?: theme.accent
        slide.deco?.bars?.forEach { b -> sb.append(barShapeXml(b, barColor, id++)) }
        // 居中对齐的 H3：竖线以内联方式随文本框一起绘制（与预览端一致，避免与文字分离）。
        // 竖线 x 坐标根据文本内容估算位置（文本框内居中 → 文本起始 ≈ 框左 + (框宽-文宽)/2），
        // 使竖线紧贴文本左侧而非文本框左边界。
        for (unit in slide.units) {
            if (unit.type == BlockType.H3 && unit.align == PptLayoutEngine.Align.CENTER) {
                val barW = 3
                val gapPt = 6   // 竖线与文字间距（pt，与预览端一致）
                val barH = unit.h
                // 估算文本像素宽度：字符数 × 字号 × 0.6（中西文混排平均字符宽系数）
                val estTextW = (unit.fragments.sumOf { it.text.length } * unit.fontSize * 0.6).toInt().coerceAtLeast(40)
                // 文本在框内居中时的起始 x（留出竖线+间距的空间）
                val totalInlineW = barW + gapPt + estTextW
                val barX = (unit.x + (unit.w - totalInlineW) / 2).coerceAtLeast(unit.x)
                sb.append(barShapeXml(Rect(barX, unit.y, barW, barH), barColor, id++))
            }
        }
        // 引用块（Markdown `>`）浅色圆角背景底色，用主题 quoteBg 色（与 H3 竖条 accent 区分）
        slide.deco?.quoteBg?.forEach { b -> sb.append(roundRectShapeXml(b, theme.quoteBg, id++)) }
        for (unit in slide.units) {
            if (unit.type == BlockType.TABLE && unit.table != null) {
                id = tableShapesXml(sb, unit, slide.cover, slide.deco?.accentBg == true, theme, style, id)
                } else {
                    sb.append(shapeXml(unit, slide.cover, slide.deco?.accentBg == true, theme, style, id++))
            }
        }
        sb.append("""</p:spTree></p:cSld>""")
        sb.append("""<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>""")
        sb.append("""</p:sld>""")
        return sb.toString()
    }

    private fun shapeXml(unit: LaidOutUnit, cover: Boolean, accentBg: Boolean, theme: PptTheme, style: PptStyleSheet, id: Int): String {
        // 封面背景可能偏亮（如简约灰白），按背景明暗自适应前景色；强调背景始终白字
        val coverText = if (isLight(theme.coverBg)) "222222" else "FFFFFF"
        val color = when {
            unit.color != null -> unit.color         // 显式颜色覆盖（如目录标题反白）
            accentBg -> "FFFFFF"
            cover -> coverText
            unit.type == BlockType.H1 || unit.type == BlockType.H2 || unit.type == BlockType.H3 ||
            unit.type == BlockType.H4 || unit.type == BlockType.H5 || unit.type == BlockType.H6 -> theme.titleColor
            else -> theme.bodyColor
        }
        // 整单元加粗（如金句）：把片段统一标记为 bold，保证导出与预览一致
        val boldFrags = if (unit.bold) unit.fragments.map { it.copy(bold = true) } else unit.fragments
        val bodyPr = """<a:bodyPr wrap="square" lIns="0" tIns="0" rIns="0" bIns="0" anchor="t"><a:normAutofit fontScale="95000"/></a:bodyPr><a:lstStyle/>"""
        val paras = when (unit.type) {
            // 颜色统一用已算好的 color（含遇色块反色）：COVER 页/accentBg 上 CODE/LIST 也反色，与预览端一致
            BlockType.CODE -> codeParas(unit.rawText(), color, style, unit.fontFamily, unit.gapAfter, unit.latinFont)
            BlockType.BULLET_LIST, BlockType.ORDERED_LIST -> listParas(unit, color, unit.fontFamily, unit.gapAfter, unit.latinFont)
            else -> paragraphsFromFrags(
                boldFrags, unit.fontSize, color, unit.align, isCode = false, unit.fontFamily,
                gapAfter = unit.gapAfter,
                latinFont = unit.latinFont
            )
        }
        val fill = if (unit.type == BlockType.CODE) """<a:solidFill><a:srgbClr val="${theme.codeBg}"/></a:solidFill>""" else ""
        // 文本框高度留充足余量（20% 或至少 16pt），避免 CJK 混排行高估算误差导致 PPTX 溢出下边距
        val h = maxOf(unit.h + 16, (unit.h * 1.2).toInt())
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="TextBox $id"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(unit.x)}" y="${emu(unit.y)}"/><a:ext cx="${emu(unit.w)}" cy="${emu(h)}"/></a:xfrm>""" +
                """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>$fill</p:spPr>""" +
                """<p:txBody>$bodyPr$paras</p:txBody></p:sp>"""
    }

    /** 标题/引用左侧强调竖条（矩形形状）。 */
    private fun barShapeXml(rect: Rect, color: String, id: Int): String {
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Deco $id"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(rect.x)}" y="${emu(rect.y)}"/><a:ext cx="${emu(rect.w)}" cy="${emu(rect.h)}"/></a:xfrm>""" +
                """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$color"/></a:solidFill></p:spPr>""" +
                """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>"""
    }

    /**
     * Logo 装饰（右下角）：红色斜角四边形 + LAWYER.C 文字。
     * 占画布宽 20%，等比缩放，红块用 custGeom 绘制自定义四边形。
     */
    private fun logoShapeXml(style: PptStyleSheet, id: Int): String {
        val cw = style.canvasW   // 720pt
        val ch = style.canvasH   // 405pt
        val ls = PptLayoutEngine.logoScale
        val logoW = (cw * ls).toInt()
        val logoH = (logoW * 180f / 640f).toInt()
        val lh = PptLayoutEngine.logoHAlign
        val lv = PptLayoutEngine.logoVAlign
        val x = if (lh == "right") cw - logoW else 0
        val y = if (lv == "bottom") ch - logoH else 0
        val logoRed = "D31B29"

        // 红色四边形：4 个顶点 (xCol, yCol) 使用 custGeom
        // 顶点坐标相对于 shape 自身 (0,0) 到 (logoW, logoH)
        val x0 = 0;                          val y0 = 0
        val x1 = (logoW * 0.195).toInt();    val y1 = (logoH * 0.02).toInt()
        val x2 = (logoW * 0.170).toInt();    val y2 = logoH
        val x3 = 0;                          val y3 = logoH

        val redShape = """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="LogoRed"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(x)}" y="${emu(y)}"/><a:ext cx="${emu(logoW)}" cy="${emu(logoH)}"/></a:xfrm>""" +
                """<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="l" t="t" r="r" b="b"/>""" +
                """<a:pathLst><a:path w="${emu(logoW)}" h="${emu(logoH)}">""" +
                """<a:moveTo><a:pt x="${emu(x0)}" y="${emu(y0)}"/></a:moveTo>""" +
                """<a:lnTo><a:pt x="${emu(x1)}" y="${emu(y1)}"/></a:lnTo>""" +
                """<a:lnTo><a:pt x="${emu(x2)}" y="${emu(y2)}"/></a:lnTo>""" +
                """<a:lnTo><a:pt x="${emu(x3)}" y="${emu(y3)}"/></a:lnTo>""" +
                """<a:close/></a:path></a:pathLst></a:custGeom>""" +
                """<a:solidFill><a:srgbClr val="$logoRed"/></a:solidFill></p:spPr>""" +
                """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>"""

        // LAWYER.C 文字：白色，字号按比例缩放
        val textFontSize = (logoW * 104f / 640f).toInt()   // ~23pt
        val textX = x + (logoW * 0.21).toInt()
        val textY = y + (logoH * 0.14).toInt()
        val textW = (logoW * 0.58).toInt()   // 文字区域宽
        val textH = (logoH * 0.72).toInt()   // 文字区域高

        val textShape = """<p:sp><p:nvSpPr><p:cNvPr id="${id + 1}" name="LogoText"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(textX)}" y="${emu(textY)}"/><a:ext cx="${emu(textW)}" cy="${emu(textH)}"/></a:xfrm>""" +
                """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>""" +
                """<p:txBody><a:bodyPr wrap="none" rtlCol="0" anchor="t"/>""" +
                """<a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="${textFontSize * 100}" b="1" dirty="0">""" +
                """<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>""" +
                """<a:latin typeface="Arial"/></a:rPr><a:t>LAWYER.C</a:t></a:r></a:p>""" +
                """</p:txBody></p:sp>"""

        return redShape + textShape
    }

    /** 引用块浅色圆角背景（roundRect，圆角 adj 取值约 20000 = 约 1/5 短边，柔和圆角卡片效果）。 */
    private fun roundRectShapeXml(rect: Rect, color: String, id: Int): String {
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="QuoteBg $id"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(rect.x)}" y="${emu(rect.y)}"/><a:ext cx="${emu(rect.w)}" cy="${emu(rect.h)}"/></a:xfrm>""" +
                """<a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 20000"/></a:avLst></a:prstGeom>""" +
                """<a:solidFill><a:srgbClr val="$color"/></a:solidFill></p:spPr>""" +
                """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>"""
    }

    /**
     * 底部波浪装饰（自定义几何路径）。
     * 使用 OOXML <a:custGeom> 绘制闭合填充区域，每层波浪一个独立 shape。
     * 注意：三次贝塞尔曲线在 DrawingML 中的正确元素名是 <a:cubicBezTo>（含 3 个 <a:pt>），
     * 坐标统一用 EMU，与 <a:ext> 形状尺寸保持一致，C1 连续保证波峰波谷圆润无折角。
     */
    private fun waveShapeXml(layer: WaveLayer, style: PptStyleSheet, id: Int): String {
        val w = style.canvasW   // 720pt
        val h = style.canvasH   // 405pt
        val EW = emu(w).toDouble()   // 路径坐标空间（EMU）
        val EH = emu(h).toDouble()
        val pts = layer.controlPoints

        val pathSb = StringBuilder()
        if (pts.size >= 6) {
            // moveTo 起点（左下角，归一化 (0,1)）
            pathSb.append("""<a:moveTo><a:pt x="${(pts[0] * EW).toLong()}" y="${(pts[1] * EH).toLong()}"/></a:moveTo>""")

            // 三次贝塞尔弧线段：每 6 个值 = (c1, c2, 终点)，C1 连续更平滑
            var i = 2
            while (i + 5 < pts.size - 4) {   // 保留末尾 4 个值给闭合线段
                val c1x = (pts[i] * EW).toLong();     val c1y = (pts[i + 1] * EH).toLong()
                val c2x = (pts[i + 2] * EW).toLong(); val c2y = (pts[i + 3] * EH).toLong()
                val ex = (pts[i + 4] * EW).toLong();  val ey = (pts[i + 5] * EH).toLong()
                pathSb.append("""<a:cubicBezTo><a:pt x="$c1x" y="$c1y"/><a:pt x="$c2x" y="$c2y"/><a:pt x="$ex" y="$ey"/></a:cubicBezTo>""")
                i += 6
            }

            // 闭合：右下角 → 左下角
            if (i + 1 < pts.size) {
                pathSb.append("""<a:lnTo><a:pt x="${(pts[i] * EW).toLong()}" y="${(pts[i + 1] * EH).toLong()}"/></a:lnTo>""")
                i += 2
            }
            if (i + 1 < pts.size) {
                pathSb.append("""<a:lnTo><a:pt x="${(pts[i] * EW).toLong()}" y="${(pts[i + 1] * EH).toLong()}"/></a:lnTo>""")
            }
            pathSb.append("""<a:close/>""")
        }

        // 透明度：OOXML srgbClr 的 alpha 子元素单位为「千分之一百分比」，0.40 → 40000
        val alphaChild = if (layer.alpha < 0.999f) {
            """<a:alpha val="${(layer.alpha * 100000f).toInt()}"/>"""
        } else ""
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Wave $id"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${emu(w)}" cy="${emu(h)}"/></a:xfrm>""" +
                """<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/>""" +
                """<a:pathLst><a:path w="${EW.toLong()}" h="${EH.toLong()}">$pathSb</a:path></a:pathLst>""" +
                """</a:custGeom>""" +
                """<a:solidFill><a:srgbClr val="${layer.color}">$alphaChild</a:srgbClr></a:solidFill>""" +
                """</p:spPr>""" +
                """<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>"""
    }

    // ── 表格（矩形 + 文本框手工绘制，保证矢量可编辑）──
    private fun tableShapesXml(
        sb: StringBuilder,
        unit: LaidOutUnit,
        cover: Boolean,
        accentBg: Boolean,
        theme: PptTheme,
        style: PptStyleSheet,
        startId: Int
    ): Int {
        val tr = unit.table ?: return startId
        var id = startId
        val x0 = unit.x
        val y0 = unit.y
        val w = unit.w
        val colW = tr.colW
        val xs = mutableListOf(x0).apply { for (cw in colW) add(last() + cw) }
        val cellColor = if (accentBg) "FFFFFF" else if (cover) (if (isLight(theme.coverBg)) "222222" else "FFFFFF") else theme.bodyColor
        val gridColor = "C8C8C8"

        // 表头填充（强调色）
        if (tr.header.isNotEmpty()) {
            sb.append(barShapeXml(Rect(x0, y0, w, tr.headerH), theme.accent, id++))
        }

        // 单元格文本（表头白字加粗，正文按规定色与对齐）
        var y = y0
        val drawRow: (List<List<InlineFragment>>, Int, Boolean) -> Unit = { cells, h, isHeader ->
            for (j in cells.indices) {
                val cw = colW.getOrNull(j) ?: colW.lastOrNull() ?: 0
                val cx = xs[j]
                val color = if (isHeader) "FFFFFF" else cellColor
                val fs = if (isHeader) tr.headerFs else tr.cellFs
                val align = tr.colAlign.getOrNull(j) ?: TableAlign.LEFT
                sb.append(cellTextXml(cx, y, cw, h, cells[j], color, fs, align, style, id++))
            }
            y += h
        }
        if (tr.header.isNotEmpty()) drawRow(tr.header, tr.headerH, true)
        tr.rows.forEachIndexed { i, row -> drawRow(row, tr.rowHs[i], false) }

        // 网格线（横向 + 纵向 + 外框）
        val lineYs = mutableListOf<Int>()
        if (tr.header.isNotEmpty()) lineYs.add(y0 + tr.headerH)
        var yy = y0 + tr.headerH
        for (hh in tr.rowHs) { yy += hh; lineYs.add(yy) }
        for (ly in lineYs) sb.append(barShapeXml(Rect(x0, ly - 1, w, 1), gridColor, id++))
        for (j in 1 until xs.size - 1) sb.append(barShapeXml(Rect(xs[j], y0, 1, unit.h), gridColor, id++))
        sb.append(barShapeXml(Rect(x0, y0, w, 1), gridColor, id++))
        sb.append(barShapeXml(Rect(x0, y0 + unit.h - 1, w, 1), gridColor, id++))
        sb.append(barShapeXml(Rect(x0, y0, 1, unit.h), gridColor, id++))
        sb.append(barShapeXml(Rect(x0 + w - 1, y0, 1, unit.h), gridColor, id++))

        return id
    }

    /** 单个单元格文本框（带内边距，垂直居中，按列对齐）。 */
    private fun cellTextXml(
        cx: Int, y: Int, cw: Int, h: Int,
        frags: List<InlineFragment>, color: String, fs: Int, align: TableAlign, style: PptStyleSheet, id: Int
    ): String {
        val a = when (align) {
            TableAlign.CENTER -> Align.CENTER
            TableAlign.RIGHT -> Align.RIGHT
            else -> Align.LEFT
        }
        val bodyPr = """<a:bodyPr wrap="square" lIns="${emu(style.tablePad)}" tIns="${emu(style.tablePad)}" """ +
                """rIns="${emu(style.tablePad)}" bIns="0" anchor="ctr"><a:noAutofit/></a:bodyPr><a:lstStyle/>"""
        val paras = paragraphsFromFrags(frags, fs, color, a, isCode = false, style.bodyFont, 0, style.latinFont)
        return textBoxXml(cx, y, cw, h, bodyPr, paras, id)
    }

    private fun textBoxXml(cx: Int, y: Int, cw: Int, h: Int, bodyPr: String, paras: String, id: Int): String {
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Cell $id"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(cx)}" y="${emu(y)}"/><a:ext cx="${emu(cw)}" cy="${emu(h)}"/></a:xfrm>""" +
                """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""" +
                """<p:txBody>$bodyPr$paras</p:txBody></p:sp>"""
    }

    // ── 文本片段 → 段落 ──
    private fun paragraphsFromFrags(
        frags: List<InlineFragment>,
        fs: Int,
        color: String,
        align: Align,
        isCode: Boolean,
        fontFamily: String = "",
        gapAfter: Int = 0,
        latinFont: String = ""
    ): String {
        val paras = mutableListOf<String>()
        val cur = mutableListOf<InlineFragment>()
        for (f in frags) {
            val parts = f.text.split("\n")
            parts.forEachIndexed { i, part ->
                if (i > 0) {
                    // 段间（非块尾）施加段后距
                    paras.add(buildPara(cur, fs, color, align, isCode, fontFamily, gapAfter, latinFont))
                    cur.clear()
                }
                if (part.isNotEmpty()) cur.add(f.copy(text = part))
            }
        }
        paras.add(buildPara(cur, fs, color, align, isCode, fontFamily, 0, latinFont))
        return paras.joinToString("")
    }

    private fun buildPara(frags: List<InlineFragment>, fs: Int, color: String, align: Align, isCode: Boolean, fontFamily: String = "", gapAfter: Int = 0, latinFont: String = ""): String {
        val algn = when (align) {
            Align.CENTER -> "ctr"
            Align.RIGHT -> "r"
            else -> "l"
        }
        // 段后距：OOXML <a:spcAft><a:spcPts val> 单位为 1/100 磅，故 pt 值 ×100
        val spcAft = if (gapAfter > 0) """<a:spcAft><a:spcPts val="${gapAfter * 100}"/></a:spcAft>""" else ""
        val pPr = """<a:pPr algn="$algn">$spcAft</a:pPr>"""
        val runs = if (frags.isEmpty()) "" else frags.joinToString("") { runXml(it, fs, color, isCode, fontFamily, latinFont) }
        return """<a:p>$pPr$runs</a:p>"""
    }

    private fun runXml(f: InlineFragment, fs: Int, color: String, isCode: Boolean, fontFamily: String = "", latinFont: String = ""): String {
        // 西文字槽 <a:latin>/<a:cs> 用西文字体（代码固定 Consolas 等宽，其余默认 Arial 无衬线）；
        // 东亚字槽 <a:ea> 用中文/东亚字体（默认微软雅黑 无衬线）。代码块的 CJK 部分仍使用微软雅黑
        // 等宽的无衬线字体，避免把 Consolas（拉丁等宽）写入东亚槽导致中文回落到衬线默认字体。
        val latin = if (isCode) "Consolas" else latinFont.ifEmpty { "Arial" }
        val ea = if (fontFamily.isNotEmpty()) fontFamily else "微软雅黑"
        val attrs = mutableListOf<String>()
        attrs.add("""lang="zh-CN"""")
        attrs.add("""altLang="en-US"""")
        attrs.add("""sz="${fs * 100}"""")
        if (f.bold) attrs.add("""b="1"""")
        if (f.italic) attrs.add("""i="1"""")
        if (f.strike) attrs.add("""strike="s"""")
        if (f.link != null) attrs.add("""u="s"""")
        val rPr = """<a:rPr ${attrs.joinToString(" ")} dirty="1"><a:solidFill><a:srgbClr val="$color"/></a:solidFill>""" +
                """<a:latin typeface="$latin"/><a:ea typeface="$ea"/><a:cs typeface="$latin"/></a:rPr>"""
        return """<a:r>$rPr<a:t>${xmlEsc(f.text)}</a:t></a:r>"""
    }

    // ── 列表 ──
    /** 每级缩进的 PPTX 左边距（EMU），约 0.5in = 457200 EMU。 */
    private val INDENT_EMU = 457200

    private fun listParas(unit: LaidOutUnit, color: String, fontFamily: String = "", gapAfter: Int = 0, latinFont: String = ""): String {
        val sb = StringBuilder()
        unit.listItems.forEachIndexed { idx, item ->
            // 前缀：顶层用列表类型前缀（"1. " / "•  "）；嵌套层用缩进+短横线
            // 有序列表：优先使用 MD 原文编号（item.number），无则 fallback 到自动编号
            val prefix = when {
                item.indent == 0 && unit.ordered -> "${item.number ?: (unit.listStart + idx + 1)}. "
                item.indent == 0 -> "•  "
                else -> "  ${"  ".repeat(item.indent - 1)}- "
            }
            val all = listOf(InlineFragment(prefix)) + item.fragments
            // 项间（非块尾）施加段后距；块尾间距由布局负责，避免双重
            val gap = if (idx == unit.listItems.lastIndex) 0 else gapAfter
            val para = buildPara(all, unit.fontSize, color, Align.LEFT, isCode = false, fontFamily, gap, latinFont)
            // 嵌套项追加 PPTX 段落左缩进（<a:pPr><a:marL>）
            if (item.indent > 0) {
                // buildPara 输出 <a:pPr algn="l">…</a:pPr>，需在标签内插入 <a:marL>
                val indented = para.replace(
                    "<a:pPr ",
                    "<a:pPr><a:marL val=\"${item.indent * INDENT_EMU}\"/> "
                )
                sb.append(indented)
            } else {
                sb.append(para)
            }
        }
        return sb.toString()
    }

    // ── 代码块 ──
    private fun codeParas(raw: String, color: String, style: PptStyleSheet, fontFamily: String = "", gapAfter: Int = 0, latinFont: String = ""): String {
        // 代码块内部行间距由行距(lineMult)决定，不叠加 spcAft；块尾间距由布局 y 步进负责
        val sb = StringBuilder()
        val lines = if (raw.isEmpty()) listOf(" ") else raw.split("\n")
        lines.forEach { line ->
            sb.append(buildPara(listOf(InlineFragment(if (line.isEmpty()) " " else line)), style.fsCode, color, Align.LEFT, isCode = true, fontFamily, 0, latinFont))
        }
        return sb.toString()
    }

    // 取代码块原始文本（LaidOutUnit 未直接存 raw，从 fragments 重建）
    private fun LaidOutUnit.rawText(): String = fragments.joinToString("") { it.text }
}
