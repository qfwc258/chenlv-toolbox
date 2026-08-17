package com.wb.mdgw.pptx

import kotlin.math.roundToInt

/**
 * 轻量 CSS 解析器（公众号式样式编辑器原型）。
 *
 * 仅支持「子集」语法，足以覆盖行距 / 段距 / 颜色 / 字体 / 字号 / 画布 的调整：
 *
 *   *      全局（行距、默认字体）
 *   h1 h2 h3   各级标题（字号、颜色、段后距）
 *   p .paragraph 正文（字号、颜色、段后距）
 *   .quote   引用（字号、颜色、段前距 margin-top）
 *   blockquote  —— .quote 的别名，二者等价（引用块 = md > 备注版块）
 *   .code    代码块（字号、字体、背景）
 *   .accent  主题强调色（accent / 引用条）
 *   .cover   封面底色
 *   .slide   画布与边距
 *
 * 支持属性：font-size( pt|px )、color、line-height、margin、margin-top、
 *           margin-bottom、font-family、background、width、height。
 * 其中 .quote / blockquote 的 margin-top 表示「引用块与上方文本的段前距」，
 * 对应 PptStyleSheet.quoteGapBefore。
 * 单位：pt 原样；px 按 0.75 折算为 pt（96dpi，公众号习惯用 px）。
 *
 * 用法：
 *   val css = """
 *     * { line-height: 1.5; font-family: "微软雅黑"; }
 *     h1 { font-size: 30pt; color: #9E2A2B; margin-bottom: 12pt; }
 *     p  { font-size: 16pt; color: #333333; margin-bottom: 8pt; }
 *     .accent { color: #C0392B; }
 *     .slide  { width: 720pt; height: 405pt; margin: 40pt 30pt; }
 *   """
 *   val sheet = PptCssParser.parse(css)   // 以默认样式为基底叠加覆盖
 *
 * 解析结果除返回 [PptStyleSheet] 外，还会在 [PptStyleSheet.overrides] 中记录
 * 「被 CSS 显式声明的字段名」——UI 层据此实现「仅当用户写了某颜色字段，才覆盖主题配色」，
 * 避免默认样式误覆盖用户已选的主题配色。
 */
object PptCssParser {

    /** 解析 CSS 文本，返回叠加在 [base] 之上的新样式表（含被覆盖字段集合）。 */
    fun parse(css: String, base: PptStyleSheet = PptStyleSheet()): PptStyleSheet {
        // 0) 去除 CSS 注释 /* ... */（避免注释中的示例规则被误解析）
        val cleaned = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        // 1) 提取规则：selector { body }
        val rules = mutableMapOf<String, MutableMap<String, String>>()
        val ruleRegex = Regex("""([^{}]+)\s*\{([^}]*)\}""")
        for (m in ruleRegex.findAll(cleaned)) {
            val selectors = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val props = parseDeclarations(m.groupValues[2])
            for (sel in selectors) {
                val bucket = rules.getOrPut(sel) { mutableMapOf() }
                bucket.putAll(props)
            }
        }

        // 2) 按优先级顺序叠加：* < 具体选择器
        val ov = mutableSetOf<String>()
        var s = base
        rules["*"]?.let { s = applyUniversal(s, it, ov) }
        rules["h1"]?.let { s = applyHeading(s, it, 1, ov) }
        rules["h2"]?.let { s = applyHeading(s, it, 2, ov) }
        rules["h3"]?.let { s = applyHeading(s, it, 3, ov) }
        rules["p"]?.let { s = applyBody(s, it, ov) }
        rules[".paragraph"]?.let { s = applyBody(s, it, ov) }
        rules[".quote"]?.let { s = applyQuote(s, it, ov) }
        rules["blockquote"]?.let { s = applyQuote(s, it, ov) }
        rules[".code"]?.let { s = applyCode(s, it, ov) }
        rules[".accent"]?.let { s = applyAccent(s, it, ov) }
        rules[".cover"]?.let { s = applyCover(s, it, ov) }
        rules[".slide"]?.let { s = applySlide(s, it, ov) }
        return s.copy(overrides = ov)
    }

    private fun parseDeclarations(body: String): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        for (decl in body.split(';')) {
            val kv = decl.split(':', limit = 2)
            if (kv.size == 2) out[kv[0].trim().lowercase()] = kv[1].trim()
        }
        return out
    }

    // ── 各选择器映射 ──

    private fun applyUniversal(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["line-height"]?.toDoubleOrNull()?.let { r = r.copy(lineMult = it); ov += "lineMult" }
        p["font-family"]?.let { val (latin, ea) = splitFontFamily(it)
            r = r.copy(latinFont = latin, bodyFont = ea, titleFont = ea)
            ov += "latinFont"; ov += "bodyFont"; ov += "titleFont"
        }
        return r
    }

    private fun applyHeading(s: PptStyleSheet, p: Map<String, String>, level: Int, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["font-size"]?.let { v ->
            val size = parsePt(v)
            r = when (level) {
                1 -> r.copy(fsH1 = size)
                2 -> r.copy(fsH2 = size)
                3 -> r.copy(fsH3 = size)
                4 -> r.copy(fsH4 = size)
                5 -> r.copy(fsH5 = size)
                else -> r.copy(fsH6 = size)
            }
            ov += "fsH$level"
        }
        p["color"]?.let { r = r.copy(titleColor = parseColor(it)); ov += "titleColor" }
        p["margin-bottom"]?.let { r = r.copy(headGap = parsePt(it)); ov += "headGap" }
        p["font-family"]?.let { val (latin, ea) = splitFontFamily(it)
            r = r.copy(titleFont = ea, latinFont = latin)
            ov += "titleFont"; ov += "latinFont"
        }
        return r
    }

    private fun applyBody(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["font-size"]?.let { r = r.copy(fsBody = parsePt(it)); ov += "fsBody" }
        p["color"]?.let { r = r.copy(bodyColor = parseColor(it)); ov += "bodyColor" }
        p["margin-bottom"]?.let { r = r.copy(paraGap = parsePt(it)); ov += "paraGap" }
        p["font-family"]?.let { val (latin, ea) = splitFontFamily(it)
            r = r.copy(bodyFont = ea, latinFont = latin)
            ov += "bodyFont"; ov += "latinFont"
        }
        return r
    }

    private fun applyQuote(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["font-size"]?.let { r = r.copy(fsQuote = parsePt(it)); ov += "fsQuote" }
        p["color"]?.let { r = r.copy(quoteBg = parseColor(it)); ov += "quoteBg" }
        // 引用块（md >）与上方文本的段前距：对应 PptStyleSheet.quoteGapBefore
        p["margin-top"]?.let { r = r.copy(quoteGapBefore = parsePt(it)); ov += "quoteGapBefore" }
        return r
    }

    private fun applyCode(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["font-size"]?.let { r = r.copy(fsCode = parsePt(it)); ov += "fsCode" }
        p["font-family"]?.let { val (latin, ea) = splitFontFamily(it)
            // 代码块拉丁槽由 runXml 固定 Consolas（等宽）；codeFont 作为东亚槽，
            // 优先取用户指定的东亚字体，否则取 Latin 字体名（如 Consolas，CJK 回落可接受）。
            r = r.copy(codeFont = if (ea == DEFAULT_EA) latin else ea)
            ov += "codeFont"
        }
        p["background"]?.let { r = r.copy(codeBg = parseColor(it)); ov += "codeBg" }
        return r
    }

    private fun applyAccent(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["color"]?.let {
            val c = parseColor(it)
            r = r.copy(accent = c, quoteBg = c)
            ov += "accent"; ov += "quoteBg"
        }
        return r
    }

    private fun applyCover(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        // 仅影响封面底色；不改全局标题色，避免误伤内容页标题
        p["background"]?.let { r = r.copy(coverBg = parseColor(it)); ov += "coverBg" }
        return r
    }

    private fun applySlide(s: PptStyleSheet, p: Map<String, String>, ov: MutableSet<String>): PptStyleSheet {
        var r = s
        p["width"]?.let { r = r.copy(canvasW = parsePt(it)); ov += "canvasW" }
        p["height"]?.let { r = r.copy(canvasH = parsePt(it)); ov += "canvasH" }
        // margin: 40pt 30pt  →  上下 40 / 左右 30；margin: 40pt → 四边
        p["margin"]?.let { m ->
            val nums = m.split(Regex("""\s+""")).mapNotNull { parsePtOrNull(it) }
            when (nums.size) {
                1 -> { r = r.copy(marginX = nums[0], marginTop = nums[0], marginBottom = nums[0]); ov += "marginX"; ov += "marginTop"; ov += "marginBottom" }
                2 -> { r = r.copy(marginTop = nums[0], marginBottom = nums[0], marginX = nums[1]); ov += "marginTop"; ov += "marginBottom"; ov += "marginX" }
            }
        }
        p["margin-left"]?.let { r = r.copy(marginX = parsePt(it)); ov += "marginX" }
        p["margin-top"]?.let { r = r.copy(marginTop = parsePt(it)); ov += "marginTop" }
        p["margin-bottom"]?.let { r = r.copy(marginBottom = parsePt(it)); ov += "marginBottom" }
        return r
    }

    // ── 基础解析助手 ──

    /** "28pt" → 28；"16px" → 12（×0.75）；非法 → 0 */
    private fun parsePt(v: String): Int = parsePtOrNull(v) ?: 0

    private fun parsePtOrNull(v: String): Int? {
        val t = v.trim().lowercase()
        return when {
            t.endsWith("pt") -> t.removeSuffix("pt").toDoubleOrNull()?.roundToInt()
            t.endsWith("px") -> (t.removeSuffix("px").toDoubleOrNull()?.times(0.75))?.roundToInt()
            t.endsWith("em") -> (t.removeSuffix("em").toDoubleOrNull()?.times(16))?.roundToInt()
            else -> t.toDoubleOrNull()?.roundToInt()
        }
    }

    /** "#RGB" / "#RRGGBB" / 颜色名 → "RRGGBB"（大写、不带 #）。非法 → 原样去除 #。 */
    private fun parseColor(v: String): String {
        val t = v.trim().removePrefix("#").lowercase()
        return when {
            t.matches(Regex("^[0-9a-f]{6}$")) -> t.uppercase()
            t.matches(Regex("^[0-9a-f]{3}$")) ->
                t.map { c -> "$c$c" }.joinToString("").uppercase()
            NAMED.containsKey(t) -> NAMED[t]!!
            else -> v.trim().removePrefix("#").uppercase()
        }
    }

    private fun stripQuotes(v: String): String =
        v.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    /**
     * 将 CSS font-family（可能是一串逗号分隔、中英文混排）拆成 (拉丁字体, 东亚字体) 两路。
     *
     * 关键：OOXML 的 <a:latin> 与 <a:ea> 是独立字槽，必须分别填入对应的脚本字体，
     * 否则 viewer 找不到匹配字形会回落到系统默认（常常是衬线体）。
     *
     * 规则：
     * - 含 CJK 字符、或命中常见中文/日文字体英文名的 token → 候选东亚字体；
     * - 其余（Arial / Consolas / Helvetica 等，及通用关键字 sans-serif 之外）→ 候选拉丁字体；
     * - 通用关键字 sans-serif / serif / monospace 等不是真实字体名，跳过；
     * - 某路缺失时回落到默认（拉丁 Arial、东亚 微软雅黑），二者均为无衬线。
     */
    private fun splitFontFamily(value: String): Pair<String, String> {
        val tokens = value.split(',').map { stripQuotes(it) }.filter { it.isNotEmpty() }
        val generic = setOf("sans-serif", "serif", "monospace", "cursive", "fantasy")
        val cjk = mutableListOf<String>()
        val latin = mutableListOf<String>()
        for (t in tokens) {
            val low = t.lowercase()
            if (low in generic) continue
            if (t.any { it.code in 0x3000..0x9FFF } || low in KNOWN_CJK) cjk.add(t)
            else latin.add(t)
        }
        val ea = cjk.firstOrNull() ?: DEFAULT_EA
        val lat = latin.firstOrNull() ?: DEFAULT_LATIN
        return lat to ea
    }

    private val DEFAULT_LATIN = "Arial"
    private val DEFAULT_EA = "微软雅黑"
    private val KNOWN_CJK = setOf(
        "simsun", "simhei", "yahei", "microsoft yahei", "microsoft yahei ui", "microsoft jhenghei",
        "source han sans", "source han sans sc", "source han sans cn", "noto sans cjk",
        "noto sans cjk sc", "noto sans cjk jp", "noto sans sc", "pingfang", "pingfang sc",
        "stsong", "stkaiti", "stheiti", "heiti", "songti", "kaiti", "fangsong",
        "ms song", "ms mincho", "himalaya", "fzshusong", "fzyaoti"
    )

    private val NAMED = mapOf(
        "black" to "000000", "white" to "FFFFFF", "red" to "FF0000",
        "green" to "008000", "blue" to "0000FF", "gray" to "808080",
        "grey" to "808080", "orange" to "FFA500", "yellow" to "FFFF00",
        "purple" to "800080", "navy" to "000080", "teal" to "008080"
    )
}
