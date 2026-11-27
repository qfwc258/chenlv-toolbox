package com.wb.mdgw.elementdoc

/**
 * 要素式文书「官方 A4 打印预览」HTML 构建器（移植参照仓库 buildPrintableHtml，Apache-2.0）。
 *
 * 生成一页一张 A4 纸的 HTML：@page A4 20mm 20mm 20mm 24mm、仿宋 14px/1.65、
 * 宋体标题、表格 1px 边框、附件 page-break-before:always。
 * 与 SpcDocxBuilder 使用同一份 ResolvedSpcDocument，保证预览与导出 Word 版式一致。
 * 纯 JVM，便于单元测试。
 */
object ElementDocHtmlBuilder {

    fun build(doc: ResolvedSpcDocument): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
        append("<meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=4.0,user-scalable=yes'>")
        append("<style>")
        append("@page{size:A4;margin:20mm 20mm 20mm 24mm;}")
        append("*{box-sizing:border-box;margin:0;padding:0;}")
        append("body{font-family:'FangSong','仿宋','STFangsong',serif;font-size:14px;line-height:1.65;color:#000;")
        append("background:#E8E8E8;-webkit-text-size-adjust:100%;}")
        append(".paper{background:#fff;width:210mm;min-height:297mm;margin:0 auto 16px auto;")
        append("padding:20mm 20mm 20mm 24mm;box-shadow:0 4px 20px rgba(0,0,0,.18);}")
        append("h1{font-family:'SimSun','宋体',serif;font-size:22px;text-align:center;font-weight:700;margin-bottom:8px;}")
        append("h2{font-family:'SimSun','宋体',serif;font-size:16px;text-align:center;font-weight:700;margin-bottom:18px;}")
        append("table{border-collapse:collapse;width:100%;table-layout:fixed;}")
        append("td,th{border:1px solid #000;padding:6px 8px;vertical-align:middle;font-size:14px;line-height:1.65;word-break:break-word;}")
        append("td.label{font-family:'SimSun','宋体',serif;font-weight:700;text-align:center;}")
        append("td.label.left{text-align:left;}")
        append("td.grid-h{font-family:'SimSun','宋体',serif;font-weight:700;text-align:center;}")
        append(".notice p{margin:2px 0;}.notice .star{font-family:'SimSun','宋体',serif;font-weight:700;}")
        append(".section{font-family:'SimSun','宋体',serif;font-weight:700;text-align:center;margin:8px 0;}")
        append(".guide{white-space:pre-line;}")
        append(".signer{text-align:right;margin-top:28px;font-family:'SimSun','宋体',serif;font-size:16px;}")
        append(".date{text-align:right;margin-top:6px;font-family:'SimSun','宋体',serif;font-size:16px;}")
        append(".footnote{font-size:12px;margin-top:10px;}")
        append(".attachment{page-break-before:always;}")
        append(".attachment h3{text-align:center;font-family:'SimSun','宋体',serif;font-size:18px;margin:8px 0 14px;}")
        append(".toolbar{position:sticky;top:0;background:#faf6ec;padding:8px;text-align:center;font-size:13px;}")
        append("</style></head><body>")

        append("<div class='paper'>")
        append("<h1>${esc(doc.docTitle)}</h1>")
        if (doc.docSubtitle.isNotBlank()) append("<h2>${esc(doc.docSubtitle)}</h2>")

        append("<table>")
        for (r in doc.rows) {
            when (r.kind) {
                "notice" -> {
                    append("<tr><td colspan='2' class='notice'>")
                    for (line in r.value.split('\n')) {
                        if (line.isBlank()) continue
                        val cls = if (line.trim().startsWith("★") && line.trim().endsWith("★")) " class='star'" else ""
                        append("<p$cls>${esc(line)}</p>")
                    }
                    append("</td></tr>")
                }
                "section" -> {
                    append("<tr><td colspan='2'>")
                    for (line in r.value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
                        append("<div class='section'>${esc(line)}</div>")
                    }
                    append("</td></tr>")
                }
                "guide", "full_input" -> {
                    append("<tr><td colspan='2' class='guide'>${esc(r.value)}</td></tr>")
                }
                "grid" -> {
                    append("<tr>")
                    val n = r.cells.size.coerceAtLeast(1)
                    r.cells.forEachIndexed { i, cell ->
                        append("<td${if (i == 0) " class='grid-h'" else ""}>${esc(cell)}</td>")
                    }
                    if (n == 1) repeat(1) { append("<td></td>") }
                    append("</tr>")
                }
                else -> {
                    val leftCls = if (labelAlign(r.label)) "label left" else "label"
                    append("<tr><td class='$leftCls'>${esc(r.label)}</td><td>${esc(r.value)}</td></tr>")
                }
            }
        }
        append("</table>")

        append("<div class='signer'>${esc(doc.signerLine)} ${esc(doc.signerName.ifBlank { "                    " })}</div>")
        append("<div class='date'>${esc(doc.dateLine)} ${esc(doc.signDate.ifBlank { "      年    月    日" })}</div>")
        for (fn in doc.footnotes) append("<div class='footnote'>${esc(fn)}</div>")
        append("</div>")

        for (att in doc.attachments) append(attachmentHtml(att))
        append("</body></html>")
    }

    private fun attachmentHtml(att: ResolvedAttachment): String = buildString {
        append("<div class='paper attachment'>")
        append("<div>${esc(att.label.ifBlank { "附件" })}</div>")
        append("<h3>${esc(att.title)}</h3>")
        if (att.subtitle.isNotBlank()) append("<div style='text-align:center;margin-bottom:14px;'>${esc(att.subtitle)}</div>")
        append("<table>")
        if (att.columns.isNotEmpty()) {
            append("<tr>")
            att.columns.forEach { append("<th>${esc(it)}</th>") }
            append("</tr>")
        }
        for (row in att.rows) {
            append("<tr>")
            if (row.size == 1 && att.columns.size > 1) {
                append("<td colspan='${att.columns.size}'>${esc(row[0])}</td>")
            } else {
                row.forEach { append("<td>${esc(it)}</td>") }
            }
            append("</tr>")
        }
        append("</table>")
        for (fl in att.footerLines) append("<div class='signer'>${esc(fl)}</div>")
        append("</div>")
    }

    /** 与 SpcDocxBuilder.labelAlign 一致：数字小标题/调解行左对齐 */
    private fun labelAlign(label: String): Boolean {
        val t = label.trimStart()
        return Regex("^\\d+\\s*[.．、]").containsMatchIn(t) || t.contains("调解") || t.contains("纠纷解决方式")
    }

    private fun esc(raw: String): String = buildString(raw.length + 8) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("<br/>")
                else -> append(ch)
            }
        }
    }
}
