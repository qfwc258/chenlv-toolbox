package com.wb.mdgw.wechat

import android.content.Context
import kotlin.math.roundToInt
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 公众号排版核心引擎。提供**两路**输出，彻底区分「屏幕预览」与「剪贴板粘贴」：
 *
 *   1) [convertForPreview] —— 完整 HTML 文档（含 `<head><style>`），**仅供 App 内 WebView 预览**。
 *      预览可用 class / `<style>` / 后代选择器，渲染最接近设计稿，但**不参与复制**。
 *
 *   2) [convertForCopy] —— 100% 内联、**零 `<head>`/`<style>`/class** 的纯净片段，**仅供剪贴板粘贴**。
 *
 * 为什么必须拆分？
 *   微信公众号编辑器（手机助手 / 网页后台）在粘贴时会**彻底丢弃整个 `<html><head>` 区块**，
 *   包括其中的 `<style>` 标签与所有 class 选择器，只保留「标签裸结构 + 行内 style」。
 *   因此粘贴内容必须是纯内联片段；若把依赖 `<style>`/class 的文档拿去粘贴，所有样式都会丢失，
 *   只剩纯文本表格（缝隙、断裂、无边框），并叠加后台默认段间距（行距变宽）。
 *
 * 处理流程（[convertForCopy]）：
 *   1) commonmark + 扩展（表格/删除线/任务列表）把 Markdown 解析为语义化 HTML 片段；
 *   2) Jsoup 清理外部属性（class/id/原有 style/外链标签），仅留内容语义；
 *   3) 主题 CSS 逐元素**内联**到 style 属性（按标签选择器匹配，不依赖 class）；
 *   4) **硬兜底**：把 body 级排版属性（line-height / font-size / font-family / color）直接落到
 *      每一个文本元素（含 strong/em/span/a 等行内元素），即使外层 `<section>` 被公众号剥离，
 *      子元素仍自带行内约束，不会出现超大空白行距；
 *   5) **表格硬兜底**：补 `border/cellspacing/cellpadding` HTML 属性（后台会保留），并内联
 *      `border-collapse:collapse;border-spacing:0`，彻底消除单元格缝隙与断裂。
 */

/**
 * 内联样式是否已包含某属性：预编译各 CSS 属性的正则（热路径逐元素调用，避免反复编译 Regex）。
 * 语义与原实现一致——匹配「属性声明以行首或分号开始」。
 */
private val cssPropertyRegexes: Map<String, Regex> = mapOf(
    "line-height" to Regex("""(^|;)\s*line-height\s*:"""),
    "font-size" to Regex("""(^|;)\s*font-size\s*:"""),
    "font-family" to Regex("""(^|;)\s*font-family\s*:"""),
    "color" to Regex("""(^|;)\s*color\s*:"""),
    "width" to Regex("""(^|;)\s*width\s*:"""),
    "word-break" to Regex("""(^|;)\s*word-break\s*:"""),
    "table-layout" to Regex("""(^|;)\s*table-layout\s*:"""),
    "border-collapse" to Regex("""(^|;)\s*border-collapse\s*:"""),
    "border-spacing" to Regex("""(^|;)\s*border-spacing\s*:"""),
    "box-sizing" to Regex("""(^|;)\s*box-sizing\s*:"""),
    "border" to Regex("""(^|;)\s*border\s*:"""),
    "padding" to Regex("""(^|;)\s*padding\s*:""")
)

private fun cssHasProp(style: String, prop: String): Boolean =
    cssPropertyRegexes[prop]?.containsMatchIn(style) ?: false

/**
 * 单张表格的列宽方案（所有路径共享，保证「预览」与「粘贴」两端列宽完全一致）。
 * @param explicit 是否来自 Markdown 内的显式列宽指令
 * @param widths   每列宽度（CSS 长度字符串，如 "30%" / "150px" / "auto"）；恒有 [cellCount] 个
 */
private data class TableColumnPlan(
    val widths: List<String>,
    val explicit: Boolean
) {
    val cellCount: Int get() = widths.size
    val isExplicit: Boolean get() = explicit
}

/** 是否形如 `%` / px / em 结尾——CSS 长度，视为可透传的显式宽度 */
private fun isCssWidth(s: String): Boolean =
    s.endsWith("%") || s.endsWith("px") || s.endsWith("em") || s.endsWith("rem")

/** 去掉宽度数值后的非中文首尾/中间空白并折叠多余空白；用于估算单元格文字占宽 */
private fun compactText(t: String): String =
    t.replace(Regex("""\s+"""), " ").trim()

/**
 * 估算一个单元格的「显示权重」：数字/英文按半角折算，中文按全角折算。
 * 用于按内容智能分配列宽（比例 = 该列所有行权重的平均 / 全表总平均）。
 */
private fun cellWeight(cell: Element): Double {
    val text = compactText(cell.text())
    if (text.isEmpty()) return 0.0
    val half = text.count { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '.' || it == ',' }
    val full = text.length - half
    return half * 0.55 + full
}

/**
 * 计算列宽方案：
 * 1) 指令优先——从表格前一行的引用块 / 注释中解析列宽；
 * 2) 否则按各列内容权重智能分配百分比（等权时退化为均分）。
 */
private fun columnPlan(table: Element, explicitHint: String? = null): TableColumnPlan {
    val firstRow = table.selectFirst("tr") ?: return TableColumnPlan(emptyList(), false)
    val headerCells = firstRow.children()
    val colCount = headerCells.size
    if (colCount == 0) return TableColumnPlan(emptyList(), false)

    // 1) 显式指令：`列宽：30% 40% 30%`（解析自表格前一行引用块 / 注释）
    val directive = explicitHint?.trim()
    if (!directive.isNullOrBlank()) {
        val parts = directive.split(Regex("""[\s,，|]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size == colCount) {
            val normalized = parts.map { p ->
                val num = p.removeSuffix("%").toDoubleOrNull()
                when {
                    p == "auto" -> "auto"
                    isCssWidth(p) -> p
                    num != null && num > 0 -> "${num.coerceAtMost(100)}%"
                    else -> null
                }
            }
            if (normalized.none { it == null }) {
                // 总和超过 100% 时等比压缩到 100%，避免表格溢出
                val valid = normalized.filterIsInstance<String>()
                val sumPct = valid.sumOf { s ->
                    if (s.endsWith("%")) s.removeSuffix("%").toDoubleOrNull() ?: 0.0 else 0.0
                }
                val scale = if (sumPct > 100) 100.0 / sumPct else 1.0
                val widths = valid.map { s ->
                    if (s.endsWith("%")) "${(s.removeSuffix("%").toDouble() * scale).let { it.roundToInt() }}%" else s
                }
                return TableColumnPlan(widths, true)
            }
        }
    }

    // 2) 智能按内容权重
    var totalWeight = 0.0
    val colWeights = DoubleArray(colCount)
    table.select("tr").forEach { tr ->
        tr.children().forEachIndexed { ci, cell ->
            val w = cellWeight(cell)
            colWeights[ci.coerceAtMost(colCount - 1)] += w
            totalWeight += w
        }
    }
    val widths = ArrayList<String>(colCount)
    if (totalWeight > 0) {
        for (ci in 0 until colCount) {
            val pct = (colWeights[ci] / totalWeight * 100.0).roundToInt()
            widths += "${pct.coerceAtLeast(1)}%"
        }
    } else {
        repeat(colCount) { widths += "${100 / colCount}%" }
    }
    return TableColumnPlan(widths, false)
}

/** 列宽指令行（Markdown 源码层）：`> 列宽：30% 40% 30%` 或 `<!-- 列宽：30% 40% 30% -->` */
private val colDirectiveLine = Regex(
    """^\s*(?:>\s*)?(?:<!--\s*)?列宽\s*[:：]\s*(.+?)\s*(?:-->)?\s*$"""
)

/** 从 Markdown 源码逐个提取「每个表格」的显式列宽指令（按下标对应 DOM 中的表格）。 */
private fun extractColHints(md: String): List<String?> {
    val out = ArrayList<String?>()
    var pending: String? = null
    for (line in md.lineSequence()) {
        val t = line.trim()
        val d = colDirectiveLine.find(t)
        if (d != null) { pending = d.groupValues[1].trim(); continue }
        // 表格分隔行定位：仅由 | / - / : / 空格 组成且含管道与连字符（区别于普通 - 水平线）
        if (t.contains('|') && t.contains('-') &&
            t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
        ) {
            out.add(pending); pending = null
        }
    }
    return out
}

/**
 * 把列宽方案落到 DOM：表格 fixed 定位 + 100% 宽 + 逐列宽度（写首行，微信按首行定宽）。
 * 预览与复制共用，保证两端观感一致。
 */
private fun applyTableColumns(body: Element, hints: List<String?>?) {
    body.select("table").forEachIndexed { ti, tb ->
        val firstRow = tb.selectFirst("tr")
        val plan = columnPlan(tb, hints?.getOrNull(ti))
        if (plan.widths.isEmpty() || firstRow == null) return@forEachIndexed
        // 固定布局 + 100% 宽，逐列宽才真正生效（与粘贴端 table-layout:fixed 对齐）
        var tstyle = tb.attr("style").trim().removeSuffix(";")
        if (!cssHasProp(tstyle, "table-layout")) tstyle += ";table-layout: fixed"
        if (!cssHasProp(tstyle, "width")) tstyle += ";width: 100%"
        tb.attr("style", tstyle.trimStart(';'))
        firstRow.children().forEachIndexed { ci, cell ->
            if (ci >= plan.cellCount) return@forEachIndexed
            val w = plan.widths[ci]
            if (w.isBlank()) return@forEachIndexed
            var s = cell.attr("style").trim().removeSuffix(";")
            if (!cssHasProp(s, "width")) s += ";width: $w"
            if (!cssHasProp(s, "word-break")) s += ";word-break: break-word"
            cell.attr("style", s.trimStart(';'))
        }
    }
}

class MdWechatConverter(context: Context) {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create()
    )

    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    /**
     * 预览用：返回完整 HTML 文档（含 `<head><style>`）。
     * 标签选择器在 `<style>` 中正常生效，适合屏幕渲染。不参与复制。
     */
    fun convertForPreview(markdown: String, css: String): String {
        if (markdown.isBlank()) return ""
        val colHints = extractColHints(markdown)
        val rawHtml = htmlRenderer.render(parser.parse(markdown))
        val doc: Document = Jsoup.parse(rawHtml)

        // 预览会启用 WebView JS 以执行「全选+复制」（与输入法复制同源，保证粘贴一致）。
        // 为防用户 markdown 中嵌套脚本被执行，先剥离危险标签与事件属性（仅影响预览，不改变排版）。
        doc.select("script, iframe, object, embed, frame, frameset").remove()
        doc.select("*").forEach { el ->
            el.attributes().forEach { a ->
                if (a.key.startsWith("on") && a.key.length > 2) el.removeAttr(a.key)
            }
        }

        // 手机宽度视口，使预览贴近公众号实际观感
        doc.head().appendElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1")
        // 基础 reset + 主题样式，二者都放进 <style>，仅用于预览
        val styleText = buildString {
            append("html,body{margin:0;padding:0;}\n")
            append(css)
        }
        doc.head().appendElement("style").text(styleText)
        // 表格列宽：智能按内容或 Markdown 列宽指令（预览与复制走同一套算法，观感一致）
        applyTableColumns(doc.body(), colHints)
        return doc.outerHtml()
    }

    /**
     * 粘贴用：返回 100% 内联、零 `<head>`/`<style>`/class 的纯净片段。
     * 公众号只认行内 style，粘贴后样式 100% 还原、不丢。
     */
    fun convertForCopy(markdown: String, css: String): String {
        if (markdown.isBlank()) return ""
        val colHints = extractColHints(markdown)

        // 1) Markdown -> 原始 HTML
        val rawHtml = htmlRenderer.render(parser.parse(markdown))

        // 2) 解析为 DOM 并清理（仅保留语义，移除所有网页垃圾与原有属性）
        val doc: Document = Jsoup.parseBodyFragment(rawHtml)
        val body = doc.body()
        body.select("style, script, link, meta, iframe").remove()
        body.select("*").forEach { el ->
            el.removeAttr("class")
            el.removeAttr("id")
            el.removeAttr("style")
        }

        // 3) 解析主题 CSS 并逐元素内联（标签选择器匹配，不依赖 class）
        val rules = CssParser.parse(css)
        val bodyDecls = LinkedHashMap<String, String>()
        rules.filter { it.isBodyRule }.forEach { bodyDecls.putAll(it.declarations) }

        body.select("*").forEach { el ->
            val collected = LinkedHashMap<String, String>()
            for (rule in rules) {
                if (rule.isBodyRule) continue
                for (selector in rule.selectors) {
                    if (el.`is`(selector)) collected.putAll(rule.declarations)
                }
            }
            if (collected.isNotEmpty()) {
                el.attr(
                    "style",
                    collected.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                )
            }
        }

        // 4) 硬兜底：body 级排版属性直接落到每一个文本元素（含行内元素）。
        //    公众号会丢弃最外层 <section> 的样式，若 <p>/<strong>/<span> 自身没有行内约束，
        //    后台默认段间距会叠加成超大空白行距。这里给所有文本元素补上自有行内样式。
        val bodyLh = bodyDecls["line-height"] ?: "1.6"
        val bodyFs = bodyDecls["font-size"].orEmpty()
        val bodyFf = bodyDecls["font-family"].orEmpty()
        val bodyColor = bodyDecls["color"].orEmpty()
        body.select(
            "p, li, blockquote, h1, h2, h3, h4, h5, h6, " +
                "td, th, pre, code, strong, em, b, i, a, span, div, dt, dd"
        ).forEach { el ->
            val style = el.attr("style")
            val add = mutableListOf<String>()
            if (!cssHasProp(style, "line-height")) add += "line-height: $bodyLh"
            if (!cssHasProp(style, "font-size") && bodyFs.isNotBlank()) add += "font-size: $bodyFs"
            if (!cssHasProp(style, "font-family") && bodyFf.isNotBlank()) add += "font-family: $bodyFf"
            if (!cssHasProp(style, "color") && bodyColor.isNotBlank()) add += "color: $bodyColor"
            if (add.isNotEmpty()) {
                val merged = (style.trim().removeSuffix(";") + ";" + add.joinToString("; "))
                    .trimStart(';')
                el.attr("style", merged)
            }
        }

        // 5) 表格硬兜底：微信粘贴表格最易「断裂 / 散架 / 错位」，根因是缺 table-layout:fixed
        //    与逐列宽度，导致编辑器按内容重排、整表崩溃。md2wx 等工具同样没做这步故表格也断。
        //    修复：固定布局 + 100% 宽 + 逐列 width（智能按内容或 Markdown 列宽指令）+
        //    单元格边框 / 换行 + 合并边框。
        applyTableColumns(body, colHints)
        body.select("table").forEach { t ->
            t.attr("border", "1")
            t.attr("cellspacing", "0")
            t.attr("cellpadding", "6")
            val s = t.attr("style").trim().removeSuffix(";")
            var merged = s
            if (!cssHasProp(merged, "table-layout")) merged += ";table-layout: fixed"
            if (!cssHasProp(merged, "width")) merged += ";width: 100%"
            if (!cssHasProp(merged, "border-collapse")) merged += ";border-collapse: collapse"
            if (!cssHasProp(merged, "border-spacing")) merged += ";border-spacing: 0"
            if (!cssHasProp(merged, "box-sizing")) merged += ";box-sizing: border-box"
            t.attr("style", merged.trimStart(';'))
        }

        // 5b) 所有单元格兜底边框 + 换行（thead 可能被剥离，故直接落到 th/td 自身）
        body.select("th, td").forEach { cell ->
            val s = cell.attr("style").trim().removeSuffix(";")
            var merged = s
            if (!cssHasProp(merged, "border")) merged += ";border: 1px solid #dfdfdf"
            if (!cssHasProp(merged, "padding")) merged += ";padding: 8px 10px"
            if (!cssHasProp(merged, "word-break")) merged += ";word-break: break-word"
            cell.attr("style", merged.trimStart(';'))
        }

        // 6) 压缩多余空行 / 空标签，输出干净片段
        val inner = body.html()
            .replace(Regex("""(?is)<p>\s*</p>"""), "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .trim()

        // 外层用 <section> 承载 body 级属性（背景/边距）。即使公众号剥离它，
        // 子元素也已通过上面的硬兜底自带行内约束，排版不崩。
        val wrapperStyle = bodyDecls.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "<section style=\"$wrapperStyle\">$inner</section>"
    }
}
