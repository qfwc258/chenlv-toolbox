package com.wb.mdgw.wechat

import android.content.Context
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

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
        return doc.outerHtml()
    }

    /**
     * 粘贴用：返回 100% 内联、零 `<head>`/`<style>`/class 的纯净片段。
     * 公众号只认行内 style，粘贴后样式 100% 还原、不丢。
     */
    fun convertForCopy(markdown: String, css: String): String {
        if (markdown.isBlank()) return ""

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
        val hasProp = { style: String, prop: String ->
            Regex("""(^|;)\s*$prop\s*:""").containsMatchIn(style)
        }
        body.select(
            "p, li, blockquote, h1, h2, h3, h4, h5, h6, " +
                "td, th, pre, code, strong, em, b, i, a, span, div, dt, dd"
        ).forEach { el ->
            val style = el.attr("style")
            val add = mutableListOf<String>()
            if (!hasProp(style, "line-height")) add += "line-height: $bodyLh"
            if (!hasProp(style, "font-size") && bodyFs.isNotBlank()) add += "font-size: $bodyFs"
            if (!hasProp(style, "font-family") && bodyFf.isNotBlank()) add += "font-family: $bodyFf"
            if (!hasProp(style, "color") && bodyColor.isNotBlank()) add += "color: $bodyColor"
            if (add.isNotEmpty()) {
                val merged = (style.trim().removeSuffix(";") + ";" + add.joinToString("; "))
                    .trimStart(';')
                el.attr("style", merged)
            }
        }

        // 5) 表格硬兜底：微信粘贴表格最易「断裂 / 散架 / 错位」，根因是缺 table-layout:fixed
        //    与逐列宽度，导致编辑器按内容重排、整表崩溃。md2wx 等工具同样没做这步故表格也断。
        //    修复：固定布局 + 100% 宽 + 首行逐列均分 width + 单元格边框 / 换行 + 合并边框。
        body.select("table").forEach { t ->
            val firstRow = t.selectFirst("tr")
            val colCount = firstRow?.children()?.size ?: 0
            val colWidth = if (colCount > 0) "${100 / colCount}%" else "100%"

            // 给首行每个单元格设置等宽，强制 fixed 布局真正生效（微信按首行定列宽）
            firstRow?.children()?.forEach { cell ->
                val s = cell.attr("style").trim().removeSuffix(";")
                var merged = s
                if (!hasProp(merged, "width")) merged += ";width: $colWidth"
                if (!hasProp(merged, "word-break")) merged += ";word-break: break-word"
                cell.attr("style", merged.trimStart(';'))
            }

            t.attr("border", "1")
            t.attr("cellspacing", "0")
            t.attr("cellpadding", "6")
            val s = t.attr("style").trim().removeSuffix(";")
            var merged = s
            if (!hasProp(merged, "table-layout")) merged += ";table-layout: fixed"
            if (!hasProp(merged, "width")) merged += ";width: 100%"
            if (!hasProp(merged, "border-collapse")) merged += ";border-collapse: collapse"
            if (!hasProp(merged, "border-spacing")) merged += ";border-spacing: 0"
            if (!hasProp(merged, "box-sizing")) merged += ";box-sizing: border-box"
            t.attr("style", merged.trimStart(';'))
        }

        // 5b) 所有单元格兜底边框 + 换行（thead 可能被剥离，故直接落到 th/td 自身）
        body.select("th, td").forEach { cell ->
            val s = cell.attr("style").trim().removeSuffix(";")
            var merged = s
            if (!hasProp(merged, "border")) merged += ";border: 1px solid #dfdfdf"
            if (!hasProp(merged, "padding")) merged += ";padding: 8px 10px"
            if (!hasProp(merged, "word-break")) merged += ";word-break: break-word"
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
