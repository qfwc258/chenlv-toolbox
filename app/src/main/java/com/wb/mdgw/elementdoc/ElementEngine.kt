package com.wb.mdgw.elementdoc

/**
 * 要素式文书填表引擎（Kotlin 复刻参照仓库 spcOfficialTemplates.ts 核心逻辑）。
 *
 * 纯 JVM 实现，无 Android 依赖，便于单元测试。职责：
 *  1. 勾选框 token 解析/切换（□ ↔ ☑），供界面逐项点选官方原表要素；
 *  2. 一键设置"先行调解"5 项了解/不了解；
 *  3. 把草稿中的值、勾选项、附件行、自定义行注入原表，生成 [ResolvedSpcDocument]。
 */
object ElementEngine {

    data class CheckboxToken(
        val index: Int,
        val charPos: Int,
        val label: String,
        val checked: Boolean
    )

    /**
     * 解析一段原文中的所有 □/☑，生成带索引、位置和推断标签的 token 列表。
     * 标签推断规则与参照仓库一致：行首/标点后视为前置式（□是），否则取框前最近短语（男□）。
     */
    fun parseCheckboxTokens(text: String): List<CheckboxToken> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<CheckboxToken>()
        var offset = 0
        var boxIdx = 0
        for (line in text.split('\n')) {
            for (i in line.indices) {
                val ch = line[i]
                if (ch != '□' && ch != '☑') continue
                val checked = ch == '☑'
                val charPos = offset + i
                val before = line.substring(0, i)
                val after = line.substring(i + 1)
                val beforeTrim = before.trimEnd()
                val prevChar = beforeTrim.takeLast(1)

                val prefixMarkers = charArrayOf('：', ':', '，', ',', '。', '；', ';', '（', '(', '□', '☑')
                val looksPrefix = beforeTrim.isEmpty() ||
                    (beforeTrim.isNotEmpty() && beforeTrim.last() in prefixMarkers) ||
                    (after.trimStart().isNotEmpty() && PREFIX_LABEL_REGEX.containsMatchIn(after) &&
                        (beforeTrim.isEmpty() || beforeTrim.last() !in CJK_CLOSE))

                val label = when {
                    looksPrefix -> {
                        val afterClean = after.trimStart()
                        PREFIX_CAPTURE_REGEX.find(afterClean)?.groupValues?.get(1)
                            ?.split(Regex("[ ]{2,}|[：:，,。；;]"))?.firstOrNull()?.trim()
                    }
                    else -> {
                        val m = SUFFIX_REGEX.find(beforeTrim)
                        val seg = m?.groupValues?.get(1)?.trim().orEmpty()
                        seg.split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotEmpty() } ?: seg
                    }
                }.takeIf { it.isNullOrEmpty().not() }
                    ?.let { if (it.length > 18) it.take(18) + "…" else it }
                    ?: (if (prevChar.isNotEmpty()) "选项${boxIdx + 1}" else "勾选${boxIdx + 1}")

                tokens.add(CheckboxToken(boxIdx, charPos, label, checked))
                boxIdx++
            }
            offset += line.length + 1
        }
        return tokens
    }

    /** 切换原文中第 targetIndex 个勾选框（□↔☑），不改变其他字符 */
    fun toggleCheckboxAtIndex(text: String, targetIndex: Int): String {
        var idx = 0
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (ch == '□' || ch == '☑') {
                if (idx == targetIndex) out.append(if (ch == '□') '☑' else '□') else out.append(ch)
                idx++
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 一键把"了解□ … 不了解□"整组设为 了解☑ / 不了解☑。
     * 兼容混合勾选（了解☑ 不了解□ 等）、半角/全角空格、换行分隔及无间隔写法；
     * 仅匹配成对出现的"了解/不了解"，不会误伤"了解更多内容□"等单项。
     */
    fun setAllMediationBenefitChoices(text: String, know: Boolean): String {
        val regex = Regex("了解[□☑]([\\s\\u3000]*)不了解[□☑]")
        return regex.replace(text) {
            val gap = it.groupValues[1]
            if (know) "了解☑${gap}不了解□" else "了解□${gap}不了解☑"
        }
    }

    /** 某行是否"未改动"（值等于模板原文或为空）——用于自动隐藏未填当事人行 */
    private fun isRowUntouched(row: SpcRow, currentVal: String?): Boolean {
        if (row.kind == "field" || row.kind == "full_input" || row.kind == "guide") {
            val v = currentVal?.trim().orEmpty()
            val tpl = row.templateText.trim()
            return v.isEmpty() || v == tpl
        }
        return true
    }

    private val PARTY_PERSON = setOf("plaintiff_person", "defendant_person", "third_person")
    private val PARTY_ORG = setOf("plaintiff_org", "defendant_org", "third_org")

    /**
     * 把草稿注入原表，生成完整已填文档。
     *  - notice/section：原样保留官方说明/章节标题
     *  - guide：模板原文 + 用户输入（若有）
     *  - full_input / field：用户值优先，缺省回退模板原文（保留 □ 勾选）
     *  - grid：用户表格值优先，缺省回退模板 cells，带 vMerge 续行标记
     *  - extraRows：锚定在对应源行之后插入自定义要素行
     *  - excludedRowIds：隐藏指定行
     *  - hideUntouchedPartyRows：自动隐藏完全未填的当事人重复行（但保证至少留一类原告/被告）
     */
    fun buildResolved(tpl: SpcTemplate, draft: ElementDraft, blankMode: Boolean = false): ResolvedSpcDocument {
        val resolved = mutableListOf<ResolvedRow>()

        val hasFilledPlaintiff = tpl.rows.any {
            it.roleTag in PARTY_PERSON || it.roleTag in PARTY_ORG
        } && tpl.rows.any {
            (it.roleTag == "plaintiff_person" || it.roleTag == "plaintiff_org") &&
                !isRowUntouched(it, draft.rowValues[it.id])
        }
        val hasFilledDefendant = tpl.rows.any {
            (it.roleTag == "defendant_person" || it.roleTag == "defendant_org") &&
                !isRowUntouched(it, draft.rowValues[it.id])
        }

        val excluded = if (!blankMode) draft.excludedRowIds else emptySet()

        fun addExtrasAfter(sourceId: String) {
            if (blankMode) return
            draft.extraRows.filter { it.sourceRowId == sourceId }.forEach { ex ->
                resolved.add(ResolvedRow(ex.uid, "field", ex.label, ex.value, roleTag = ex.roleTag))
            }
        }

        for (r in tpl.rows) {
            if (!blankMode && r.kind !in setOf("notice", "section") && r.id in excluded) {
                addExtrasAfter(r.id)
                continue
            }
            if (!blankMode && draft.hideUntouchedPartyRows && r.kind == "field") {
                if (isRowUntouched(r, draft.rowValues[r.id])) {
                    if ((r.roleTag == "plaintiff_person" || r.roleTag == "plaintiff_org") && hasFilledPlaintiff) continue
                    if ((r.roleTag == "defendant_person" || r.roleTag == "defendant_org") && hasFilledDefendant) continue
                    if (r.roleTag in setOf("third_person", "third_org")) continue
                }
            }

            when (r.kind) {
                "notice", "section" -> resolved.add(ResolvedRow(r.id, r.kind, value = r.templateText))
                "guide" -> {
                    val user = if (blankMode) "" else draft.rowValues[r.id]?.trim().orEmpty()
                    resolved.add(ResolvedRow(r.id, "guide", value = if (user.isNotEmpty()) "${r.templateText}\n$user" else r.templateText))
                }
                "full_input" -> {
                    val v = if (blankMode) r.templateText else draft.rowValues[r.id] ?: r.templateText
                    resolved.add(ResolvedRow(r.id, "full_input", value = v))
                }
                "grid" -> {
                    val cells = if (blankMode) r.cells
                    else draft.gridValues[r.id] ?: r.cells
                    resolved.add(ResolvedRow(r.id, "grid", cells = cells, vMergeContinue = r.vMergeContinue, roleTag = r.roleTag))
                }
                else -> {
                    val v = if (blankMode) r.templateText else draft.rowValues[r.id] ?: r.templateText
                    resolved.add(ResolvedRow(r.id, "field", r.label, v, roleTag = r.roleTag))
                }
            }
            addExtrasAfter(r.id)
        }

        val attachments = if ((blankMode || draft.includeAttachments) && tpl.attachments.isNotEmpty()) {
            tpl.attachments.mapIndexed { idx, att ->
                val rows = if (!blankMode) draft.attachmentRows[idx] ?: att.rows else att.rows
                ResolvedAttachment(att.label, att.title, att.subtitle, att.columns, rows, att.footerLines)
            }
        } else emptyList()

        return ResolvedSpcDocument(
            templateId = tpl.id,
            docTitle = tpl.docTitle,
            docSubtitle = tpl.docSubtitle,
            displayName = tpl.displayName,
            rows = resolved,
            signerLine = tpl.signerLine,
            signerName = draft.signerName,
            dateLine = tpl.dateLine,
            signDate = draft.signDate,
            footnotes = tpl.footnotes,
            attachments = attachments
        )
    }

    /** 生成纯文本预览（无 XML） */
    fun toPlainText(doc: ResolvedSpcDocument): String = buildString {
        appendLine(doc.docTitle)
        if (doc.docSubtitle.isNotBlank()) appendLine(doc.docSubtitle)
        appendLine()
        for (r in doc.rows) {
            when (r.kind) {
                "grid" -> appendLine(r.cells.joinToString("  |  "))
                else -> {
                    if (r.label.isNotBlank()) append("${r.label}：")
                    appendLine(r.value)
                }
            }
        }
        appendLine()
        appendLine("${doc.signerLine}${doc.signerName}")
        appendLine("${doc.dateLine}${doc.signDate}")
        for (fn in doc.footnotes) appendLine(fn)
        for (att in doc.attachments) {
            appendLine()
            appendLine("【${att.title}】")
            for (c in att.columns) append("$c\t")
            appendLine()
            for (row in att.rows) { for (c in row) append("$c\t"); appendLine() }
        }
    }

    /**
     * 按最高法《说明》第4条复制主体行时的新标题：
     * 原告→原告2、原告（自然人）→原告2（自然人）；非主体行发「标题（2）」。
     * @param count 已复制出的同源代码行数，第一次复制为 0
     */
    fun nextDuplicateLabel(baseLabel: String, count: Int): String {
        val base = baseLabel.replace("\n", "").trim()
        val partyPrefixes = listOf(
            "委托诉讼代理人", "申请执行人", "被申请人", "被执行人", "答辩人", "自诉人",
            "第三人", "原告", "被告", "申请人"
        )
        val prefix = partyPrefixes.firstOrNull { base.startsWith(it) }
        val n = count + 2
        return if (prefix != null) base.replaceFirst(prefix, "$prefix$n") else "$base（$n）"
    }

    private val PREFIX_LABEL_REGEX = Regex(
        "^(?:\\s*\\d+\\.\\s*[\\u4e00-\\u9fff]|是|否|有|无|警告|罚款|没收|暂扣|限制|行政拘留|其他|大气|水|土壤|电子|固体|噪声|光|放射性)"
    )
    private val PREFIX_CAPTURE_REGEX = Regex("^([^□☑\\n]{1,22})")
    private val SUFFIX_REGEX = Regex("([^□☑：:（(；;，,。]+)$")
    private val CJK_CLOSE = setOf('）', '〕', '》', '」', '』', '】', '”', '’')
}
