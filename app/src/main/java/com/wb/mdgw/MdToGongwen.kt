package com.wb.mdgw

import kotlin.math.roundToInt

/**
 * Markdown -> 法律文书排版（参照党政机关公文 GB/T 9704 格式，无红头）。
 *
 * 排版规则（与参考实现保持一致）：
 *  - 一级标题 `#`     : 小标宋体 二号(22pt) 居中，后加一个空行
 *  - 二级标题 `##`    : 黑体 三号(16pt)，首行缩进 2 字符
 *  - 三级标题 `###`   : 楷体_GB2312 三号，首行缩进 2 字符
 *  - 四级标题 `####`  : 仿宋_GB2312 三号，首行缩进 2 字符
 *  - 正文             : 仿宋_GB2312 三号，首行缩进 2 字符(32pt)，固定行距 28pt
 *  - `ll ` 前缀       : 顶格（不缩进）
 *  - `rr ` 前缀       : 右对齐（落款用，如具状人 / 日期）
 *  - `tab ` / `tab.` / `tab-` 前缀：制表位前导符填空线（公文填空下划线 / 目录点线）。
 *    可选 `@Ncm` 指定制表位位置（缺省=右侧页边距）；内容可用 `::` 分隔左 / 右文字。
 *    例：`tab 甲方（盖章）：`、`tab. 第一章 总则::1`、`tab@12cm 乙方（签字）：`
 *  - `---`            : 正常 Markdown 水平线（渲染为空行分隔），不再触发落款右对齐
 *  - 表格             : 三线/全框线表格，单元格不缩进
 *  - `**粗体**` `*斜体*` 行内样式
 *  - 主送机关 / 受文法院：自动顶格（如「尊敬的审判员：」「XX人民法院」）
 *  - 结束语「此致」：缩进两字，其下一行（受文法院名称）顶格
 */

/**
 * 公文排版规范抽象层 - 支持多套预设配置一键切换
 * 内置：国标通用版、法院诉讼文书版、行政机关版
 */
data class GovDocSpec(
    val specName: String,
    val mainTitleFont: String,
    val mainTitleSizePt: Double,
    val level2TitleFont: String,
    val level2TitleSizePt: Double,
    val level3TitleFont: String,
    val level3TitleSizePt: Double,
    val bodyFont: String,
    val bodySizePt: Double,
    val lineSpacingPt: Double,
    val indentPt: Double,
    val page: PageSetup,
    val justify: Boolean = true,
    val autoSalutation: Boolean = true,
    val smartQuotes: Boolean = true,
    val pageNumber: Boolean = false
) {
    companion object {
        /** 国标通用公文规范 - 默认 */
        val GB_STANDARD = GovDocSpec(
            specName = "国标通用",
            mainTitleFont = "方正小标宋简体",
            mainTitleSizePt = 22.0,
            level2TitleFont = "黑体",
            level2TitleSizePt = 16.0,
            level3TitleFont = "楷体_GB2312",
            level3TitleSizePt = 16.0,
            bodyFont = "仿宋_GB2312",
            bodySizePt = 16.0,
            lineSpacingPt = 28.0,
            indentPt = 32.0,
            page = PageSetup()
        )

        /**
         * 法院诉讼文书专用规范（严格参照 Python 参考实现 md_to_official_word）：
         *  - 主标题 `#` : 小标宋体 二号(22pt) 居中
         *  - 正文 / 二三四级标题 / 列表 : 仿宋_GB2312 四号(14pt)，首行缩进 2 字符(32pt)
         *  - 固定行距(精确值) 25pt，段前段后 0
         *  - 页面 A4，页边距 上3 / 下2.8 / 左2.5 / 右2.5 cm
         * 注：参考实现正文为左对齐，本工具箱按公文规范与历史要求保持两端对齐。
         */
        val COURT_DOC = GovDocSpec(
            specName = "诉讼文书",
            mainTitleFont = "小标宋体",
            mainTitleSizePt = 22.0,
            level2TitleFont = "黑体",
            level2TitleSizePt = 16.0,
            level3TitleFont = "楷体_GB2312",
            level3TitleSizePt = 16.0,
            bodyFont = "仿宋_GB2312",
            bodySizePt = 14.0,
            lineSpacingPt = 25.0,
            indentPt = 32.0,
            page = PageSetup()
        )

        /** 行政机关公文规范 */
        val GOV_OFFICIAL = GovDocSpec(
            specName = "行政机关",
            mainTitleFont = "方正小标宋简体",
            mainTitleSizePt = 22.0,
            level2TitleFont = "黑体",
            level2TitleSizePt = 16.0,
            level3TitleFont = "楷体_GB2312",
            level3TitleSizePt = 16.0,
            bodyFont = "仿宋_GB2312",
            bodySizePt = 16.0,
            lineSpacingPt = 30.0,
            indentPt = 32.0,
            page = PageSetup(topCm = 3.7, bottomCm = 3.5, leftCm = 2.8, rightCm = 2.5)
        )

        val ALL_PRESETS = listOf(COURT_DOC, GB_STANDARD, GOV_OFFICIAL)

        /** 默认规范：诉讼文书（本工具箱面向法律实务） */
        val DEFAULT = COURT_DOC

        /** 按名称回查规范；未知名称回退到默认（诉讼文书） */
        fun byName(name: String?): GovDocSpec =
            ALL_PRESETS.firstOrNull { it.specName == name } ?: DEFAULT
    }
}

object MdToGongwen {

    const val FONT_XIAOBIAO = "方正小标宋简体"
    const val FONT_HEI = "黑体"
    const val FONT_KAI = "楷体_GB2312"
    const val FONT_FANG = "仿宋_GB2312"

    const val SIZE_MAIN_TITLE = 22.0   // 二号
    const val SIZE_NORMAL = 16.0       // 三号
    const val INDENT_2 = 32.0          // 二字符缩进
    const val LINE_SPACING = 28.0      // 固定行距

    /** 转换配置，供界面调节 */
    data class Options(
        val spec: GovDocSpec = GovDocSpec.DEFAULT,
        val mainTitleFont: String = spec.mainTitleFont,
        val bodyFont: String = spec.bodyFont,
        val mainTitleSizePt: Double = spec.mainTitleSizePt,
        val bodySizePt: Double = spec.bodySizePt,
        val lineSpacingPt: Double = spec.lineSpacingPt,
        val indentPt: Double = spec.indentPt,
        /** 中文直引号转弯引号 */
        val smartQuotes: Boolean = spec.smartQuotes,
        /** 正文两端对齐（公文规范：右边界齐整） */
        val justify: Boolean = spec.justify,
        /** 自动识别称呼语（主送机关）并顶格 */
        val autoSalutation: Boolean = spec.autoSalutation,
        /** 是否添加页码：Word 写入页脚、PDF 底部居中阿拉伯数字 */
        val pageNumber: Boolean = spec.pageNumber
    )

    /**
     * 判断是否为称呼语（主送机关 / 受文者）。
     * 公文规范要求顶格书写，如「尊敬的审判长、审判员：」「XX省人民政府：」
     */
    private fun isSalutation(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..40) return false
        if (!t.endsWith("：") && !t.endsWith(":")) return false
        // 排除以数字/项目符号开头的条目式内容（如「一、事实认定：」属正文标题）
        if (ENUM_CN_RE.containsMatchIn(t)) return false
        if (ENUM_NUM_RE.containsMatchIn(t)) return false
        return true
    }

    /**
     * 是否为结束语引导词「此致」。
     * 法律文书中「此致」后换行接的是受文法院名称（如「XX人民法院」），
     * 该名称应顶格，而非「敬礼」。
     */
    private fun isThisZhi(text: String): Boolean = text.trim() == "此致"

    /** 解析行内 `**粗体**` / `*斜体*`，返回样式片段 */
    fun parseInline(text: String, font: String, sizePt: Double): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        var i = 0
        val sb = StringBuilder()

        fun flush() {
            if (sb.isNotEmpty()) {
                runs += TextRun(sb.toString(), font, sizePt)
                sb.setLength(0)
            }
        }

        while (i < text.length) {
            // 粗体 **xxx**
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    runs += TextRun(text.substring(i + 2, end), font, sizePt, bold = true)
                    i = end + 2
                    continue
                }
            }
            // 斜体 *xxx*
            if (text[i] == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    flush()
                    runs += TextRun(text.substring(i + 1, end), font, sizePt, italic = true)
                    i = end + 1
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        flush()
        return runs
    }

    private fun isTableLine(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("|") && t.endsWith("|") && t.length >= 2
    }

    private fun parseTableRow(s: String): List<String> =
        s.trim().trim('|').split("|").map { it.trim() }

    private val SEP_REGEX = Regex("^:?-{1,}:?$")

    // 预编译正则：写入时会逐行/逐块调用，避免在热路径上反复编译 Regex
    private val IMG_RE = Regex("!\\[[^\\]]*]\\([^)]*\\)")          // 图片
    private val LINK_RE = Regex("\\[([^\\]]*)]\\([^)]*\\)")        // 链接保留文字
    private val CODE_RE = Regex("`([^`]*)`")                       // 行内代码
    private val STRIKE_RE = Regex("~~([^~]*)~~")                   // 删除线
    private val ENUM_CN_RE = Regex("^[一二三四五六七八九十百]+[、.．]")   // 中文序号条目
    private val ENUM_NUM_RE = Regex("^[（(]?\\d+[）)、.．]")           // 数字条目

    /**
     * 制表位前导符填空线（扩展语法）。公文常见「填空下划线 / 目录点线」——这类第二类「下划线」
     * 由 Word 的制表位 + leader（前导符）实现，纯 Markdown 无法直接表达，故加本前缀。
     *
     * 形式：`tab` + 可选前导线 token（`.`=点线 / `-`=虚线 / 缺省=下划线）+ 可选制表位位置
     * `@Ncm`（缺省=右侧页边距，即整行撑满）+ 空格 + 内容；内容可用 `::` 把「左侧文字」与
     * 「右侧文字（制表位后右对齐，如目录页码）」分开。
     *
     * 例：
     *   `tab 甲方（盖章）：`        → 下划线填空线（公文填空 / 签名）
     *   `tab. 第一章 总则::1`       → 点线目录（页码「1」右对齐到右侧制表位）
     *   `tab- 项目::说明`          → 虚线
     *   `tab@12cm 乙方（签字）：`   → 指定制表位位置（距段落左边界 12cm）
     *
     * 解析结果：左侧文字 + 一个 `\t` run + 右侧文字；段落 props.tabs 写入对应制表位
     * （leader=underline/dot/dash），由预览 flex 前导线与导出 w:tabs 共同还原效果。
     */
    private val TAB_LINE_RE = Regex("""^tab(\.|\-|@(\d+(?:\.\d+)?)cm)?\s+(.*)$""")

    /** 智能引号：把成对的直引号转成中文弯引号 */
    private fun applySmartQuotes(line: String): String {
        val sb = StringBuilder(line.length)
        var open = true
        for (c in line) {
            if (c == '"') {
                sb.append(if (open) '\u201C' else '\u201D')
                open = !open
            } else sb.append(c)
        }
        return sb.toString()
    }

    /** 去除 Markdown 语法噪声：图片、链接、行内代码、删除线 */
    private fun stripNoise(s: String): String {
        var t = s
        t = IMG_RE.replace(t, "")
        t = LINK_RE.replace(t, "$1")
        t = CODE_RE.replace(t, "$1")
        t = STRIKE_RE.replace(t, "$1")
        return t
    }

    /**
     * 执行转换。
     * @return docx 文件字节内容
     */
    fun convert(markdown: String, options: Options = Options()): GovDoc {
        val bodyFont = options.bodyFont
        val bodySize = options.bodySizePt
        val indent = options.indentPt
        val spacing = options.lineSpacingPt
        val spec = options.spec

        // ---------- 预处理：清洗行 ----------
        val cleanLines = mutableListOf<String>()
        var inCodeFence = false
        for (rawLine in markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            var line = rawLine.trimStart().trimEnd('\n')

            // 跳过代码围栏，围栏内容按普通正文输出
            if (line.trimStart().startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (options.smartQuotes && !inCodeFence) line = applySmartQuotes(line)
            if (!inCodeFence) line = stripNoise(line)
            if (line.isBlank()) continue
            cleanLines += line
        }

        val page = spec.page
        val doc = DocxWriter(
            page = page,
            defaultFont = bodyFont,
            defaultSizePt = bodySize,
            defaultIndentPt = indent,
            defaultLineSpacingPt = spacing,
            pageNumber = options.pageNumber
        )

        // 公文正文两端对齐，右边界齐整（Word / PDF 一致）
        val bodyAlign = if (options.justify) Align.BOTH else Align.LEFT

        val bodyProps = ParaProps(
            align = bodyAlign,
            firstLineIndentPt = indent,
            lineSpacingPt = spacing
        )
        val noIndentProps = bodyProps.copy(firstLineIndentPt = 0.0, align = Align.LEFT)

        /** 是否已出现过正文段落——用于界定「称呼语只可能在开头」 */
        var bodyStarted = false
        var i = 0
        val total = cleanLines.size

        while (i < total) {
            val text = cleanLines[i]

            // 水平线（--- / *** / ___）-> 按正常 Markdown 分隔线处理，渲染为空行
            if (text.trim() == "---" || text.trim() == "***" || text.trim() == "___") {
                doc.addEmptyParagraph(bodyProps)
                i++
                continue
            }

            // 表格
            if (isTableLine(text)) {
                val tableRows = mutableListOf<String>()
                while (i < total && isTableLine(cleanLines[i])) {
                    tableRows += cleanLines[i].trim()
                    i++
                }
                val realRows = mutableListOf<List<List<TextRun>>>()
                for (r in tableRows) {
                    val cells = parseTableRow(r)
                    // 过滤 |---|---| 对齐分隔行
                    val isSep = cells.isNotEmpty() && cells.all { SEP_REGEX.matches(it) }
                    if (!isSep) {
                        realRows += cells.map { parseInline(it, bodyFont, bodySize) }
                    }
                }
                if (realRows.isNotEmpty()) doc.addTable(realRows)
                continue
            }

            // `rr ` 前缀 -> 强制右对齐、不缩进（落款行，如具状人/日期）
            if (text.startsWith("rr ")) {
                doc.addParagraph(
                    parseInline(text.substring(3), bodyFont, bodySize),
                    ParaProps(align = Align.RIGHT, firstLineIndentPt = 0.0, lineSpacingPt = spacing)
                )
                bodyStarted = true
                i++
                continue
            }

            // `ll ` 前缀 -> 强制顶格（手动控制，优先级最高）
            if (text.startsWith("ll ")) {
                doc.addParagraph(parseInline(text.substring(3), bodyFont, bodySize), noIndentProps)
                bodyStarted = true
                i++
                continue
            }

            // 制表位前导符填空线（扩展语法）：`tab` / `tab.` / `tab-` + 可选 `@Ncm` 位置。
            // 还原公文「填空下划线 / 目录点线」——这类第二类「下划线」由制表位 leader 实现。
            val tabMatch = TAB_LINE_RE.matchEntire(text)
            if (tabMatch != null) {
                val raw = tabMatch.groupValues[1]          // "." / "-" / "@Ncm" / ""
                val leader = when {
                    raw == "." -> "dot"
                    raw == "-" -> "dash"
                    else -> "underline"
                }
                val usableCm = page.widthCm - page.leftCm - page.rightCm
                val posCm = tabMatch.groupValues[2].toDoubleOrNull() ?: usableCm
                val content = tabMatch.groupValues[3]
                val (left, right) = content.split("::", limit = 2).let {
                    (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "")
                }
                val tabRun = TextRun("\t", bodyFont, bodySize)
                val runs = if (right.isNotEmpty())
                    parseInline(left, bodyFont, bodySize) + tabRun + parseInline(right, bodyFont, bodySize)
                else
                    parseInline(left, bodyFont, bodySize) + tabRun
                doc.addParagraph(
                    runs,
                    bodyProps.copy(
                        align = Align.LEFT,
                        firstLineIndentPt = 0.0,
                        lineSpacingPt = spacing,
                        tabs = listOf(
                            TabStop(posPt = cmToTwips(posCm) / 20.0, align = "right", leader = leader)
                        )
                    )
                )
                bodyStarted = true
                i++
                continue
            }

            // 称呼语（主送机关）自动顶格：仅在正文开始之前生效
            if (options.autoSalutation && !bodyStarted && !text.startsWith("#") && isSalutation(text)) {
                doc.addParagraph(parseInline(text, bodyFont, bodySize), noIndentProps)
                i++
                continue
            }

            // 结束语「此致」：缩进两字，其下一行（受文法院名称）顶格
            if (isThisZhi(text)) {
                doc.addParagraph(
                    parseInline(text.trim(), bodyFont, bodySize),
                    ParaProps(align = Align.LEFT, firstLineIndentPt = indent, lineSpacingPt = spacing)
                )
                // 紧接的一行（如「XX人民法院」）顶格；亦兼容「敬礼」等结尾语。
                if (i + 1 < total) {
                    val next = cleanLines[i + 1]
                    doc.addParagraph(parseInline(next, bodyFont, bodySize), noIndentProps)
                    i += 2
                    continue
                }
                i++
                continue
            }

            when {
                text.startsWith("# ") -> {
                    doc.addParagraph(
                        parseInline(
                            text.removePrefix("# ").trim(),
                            options.mainTitleFont,
                            options.mainTitleSizePt
                        ),
                        ParaProps(align = Align.CENTER, firstLineIndentPt = 0.0, lineSpacingPt = spacing)
                    )
                    doc.addEmptyParagraph(bodyProps)
                }
                text.startsWith("## ") -> {
                    // 各级标题不做两端对齐（避免短标题被拉伸得稀疏）
                    doc.addParagraph(
                        parseInline(text.removePrefix("## ").trim(), spec.level2TitleFont, bodySize),
                        bodyProps.copy(align = Align.LEFT)
                    )
                    bodyStarted = true
                }
                text.startsWith("### ") -> {
                    doc.addParagraph(
                        parseInline(text.removePrefix("### ").trim(), spec.level3TitleFont, bodySize),
                        bodyProps.copy(align = Align.LEFT)
                    )
                    bodyStarted = true
                }
                text.startsWith("#### ") -> {
                    doc.addParagraph(
                        parseInline(text.removePrefix("#### ").trim(), bodyFont, bodySize),
                        bodyProps.copy(align = Align.LEFT)
                    )
                    bodyStarted = true
                }
                text.startsWith("> ") -> {
                    doc.addParagraph(
                        parseInline(text.removePrefix("> ").trim(), FONT_KAI, bodySize),
                        bodyProps
                    )
                    bodyStarted = true
                }
                else -> {
                    doc.addParagraph(parseInline(text, bodyFont, bodySize), bodyProps)
                    bodyStarted = true
                }
            }
            i++
        }

        // 空文档兜底，避免生成 Word 无法打开的空 body
        if (total == 0) doc.addEmptyParagraph(bodyProps)

        val title = cleanLines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim() ?: "公文"
        return GovDoc(
            blocks = doc.blocks,
            page = page,
            title = title,
            mainTitleFont = options.mainTitleFont,
            bodyFont = options.bodyFont,
            bodySizePt = options.bodySizePt,
            lineSpacingPt = options.lineSpacingPt,
            indentPt = options.indentPt,
            pageNumber = options.pageNumber
        )
    }
}

/** 中文标准字号（磅）→ 名称，用于设置页如实展示当前规范的字号 */
fun ptToGongwenSizeName(pt: Double): String = when (pt.roundToInt()) {
    42 -> "初号"
    36 -> "小初"
    26 -> "一号"
    24 -> "小一"
    22 -> "二号"
    18 -> "小二"
    16 -> "三号"
    15 -> "小三"
    14 -> "四号"
    12 -> "小四"
    10 -> "五号"
    9 -> "小五"
    else -> "${pt.toInt()}pt"
}
