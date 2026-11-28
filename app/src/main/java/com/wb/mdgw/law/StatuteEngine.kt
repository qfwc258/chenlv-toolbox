package com.wb.mdgw.law

/**
 * 离线法规搜索引擎（纯 JVM，可单测）。
 * 移植参照源码 src/lib/statuteSearch.ts + LawsAndChecklistsTab.tsx 的数据转换与筛选逻辑。
 * 支持 "585" / "第五百八十五条" / "民法典第585条" / "民法典 第1165条 第2款" 等自然律师检索式。
 */
object StatuteEngine {

    // ---------------- 中文数字解析 ----------------
    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val CN_UNIT = mapOf('十' to 10, '百' to 100, '千' to 1000)
    private val CN_NUM_RE = Regex("^[零〇一二两三四五六七八九十百千]+$")
    private val ARABIC_RE = Regex("^\\d{1,4}$")

    fun parseChineseArticleNumber(raw: String): Int? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // 兼容带「第…条」包装的写法
        val wrapped = Regex("^第\\s*(.+?)\\s*条?$").find(s)
        if (wrapped != null) s = wrapped.groupValues[1].trim()
        if (ARABIC_RE.matches(s)) {
            val n = s.toInt()
            return if (n > 0) n else null
        }
        if (!CN_NUM_RE.matches(s)) return null

        var total = 0
        var currentDigit = 0
        var hasUnit = false
        for (ch in s) {
            if (CN_DIGIT.containsKey(ch)) {
                currentDigit = CN_DIGIT.getValue(ch)
            } else if (CN_UNIT.containsKey(ch)) {
                hasUnit = true
                val unit = CN_UNIT.getValue(ch)
                val mult = if (currentDigit == 0 && ch == '十' && total == 0) 1 else currentDigit
                total += mult * unit
                currentDigit = 0
            }
        }
        total += currentDigit

        // 纯位序中文数字 "五八五" -> 585
        if (!hasUnit && s.length >= 2) {
            var posVal = 0
            for (ch in s) {
                posVal = posVal * 10 + (CN_DIGIT[ch] ?: return null)
            }
            return if (posVal > 0) posVal else null
        }
        return if (total > 0) total else null
    }

    // ---------------- 法名同义词（严格：解释/规定不可静默用母法替代） ----------------
    private data class Synonym(val re: Regex, val requiredParts: List<String>)
    private val LAW_SYNONYMS = listOf(
        Synonym(Regex("^民诉法解释$|^民诉解释$"), listOf("民事诉讼法", "解释")),
        Synonym(Regex("^民诉法$"), listOf("民事诉讼法")),
        Synonym(Regex("^民诉证据|^证据规定$"), listOf("民事诉讼证据")),
        Synonym(Regex("^反法$|^反不正当竞争$"), listOf("反不正当竞争")),
        Synonym(Regex("^建工解释$"), listOf("建设工程", "解释")),
        Synonym(Regex("^道交解释$"), listOf("道路交通事故", "解释")),
        Synonym(Regex("^人损解释$"), listOf("人身损害", "解释")),
        Synonym(Regex("^民间借贷$|^民间借贷解释$"), listOf("民间借贷")),
        Synonym(Regex("^劳动仲裁法$"), listOf("劳动争议调解仲裁")),
        Synonym(Regex("^商标法$"), listOf("商标")),
        Synonym(Regex("^著作权法$|^版权法$"), listOf("著作权")),
        Synonym(Regex("^专利法$"), listOf("专利")),
        Synonym(Regex("^个保法$"), listOf("个人信息保护"))
    )
    private val INTERP_SERIES_RE = Regex("(?:解释|规定)\\s*([一二三四五六七八九十1-9])")
    private val INTERP_WORD_RE = Regex("解释|规定|批复|纪要")

    fun matchLawHint(hint: String, item: StatuteEntry): Boolean {
        val clean = hint.trim()
        if (clean.isEmpty()) return true
        val hay = "${item.lawTitle} ${item.fullLawTitle}"

        val asksForInterp = INTERP_WORD_RE.containsMatchIn(clean)
        val itemIsInterp = INTERP_WORD_RE.containsMatchIn(hay)
        if (asksForInterp && !itemIsInterp) return false

        INTERP_SERIES_RE.find(clean)?.let { m ->
            val n = m.groupValues[1]
            if (!hay.contains("（$n）") && !hay.contains("($n)") && !hay.contains(clean)) return false
        }

        if (hay.contains(clean)) return true
        for (syn in LAW_SYNONYMS) {
            if (syn.re.matches(clean)) {
                if (!asksForInterp && itemIsInterp && clean.endsWith("法")) {
                    return item.lawTitle.contains(syn.requiredParts[0]) && !INTERP_WORD_RE.containsMatchIn(item.lawTitle)
                }
                return syn.requiredParts.all { hay.contains(it) }
            }
        }
        return false
    }

    // ---------------- 查询解析 ----------------
    data class ParsedQuery(
        val raw: String,
        val exactNo: Int? = null,
        val lawHint: String = "",
        val clauseHint: String = "",
        val keywords: List<String> = emptyList()
    )

    private val CLAUSE_RE = Regex("第\\s*([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,4})\\s*([款项])")
    private val PURE_NO_RE = Regex("^第?\\s*([0-9]{1,4}|[零〇一二两三四五六七八九十百千]{1,10})\\s*条?$")
    private val COMPOUND_RE = Regex("^([\\u4e00-\\u9fa5A-Za-z0-9]{2,24}?)\\s*(?:第\\s*([0-9]{1,4}|[零〇一二两三四五六七八九十百千]{1,10})\\s*条?|([0-9]{1,4})\\s*条?)$")
    private val SUB_COMPOUND_RE = Regex("^([\\u4e00-\\u9fa5]{2,20}?)第([0-9]{1,4}|[零〇一二两三四五六七八九十百千]{1,10})条?$")
    private val ART_TOK_RE = Regex("^第?([0-9]{1,4}|[零〇一二两三四五六七八九十百千]{1,10})条$")
    private val LAW_END_RE = Regex("(?:法|典|条例|解释[一二三四五六七八九十1-9]?|规定|办法|纪要)$")

    fun parseStatuteQuery(rawInput: String): ParsedQuery {
        val cleaned = rawInput.replace(Regex("[《》<>「」『』【】()（）]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return ParsedQuery("")

        val clauseParts = mutableListOf<String>()
        val withoutClause = CLAUSE_RE.replace(cleaned) { m ->
            clauseParts.add("第${m.groupValues[1]}${m.groupValues[2]}")
            " "
        }.replace(Regex("\\s+"), " ").trim()
        val clauseHint = clauseParts.joinToString("")
        val working = withoutClause.ifEmpty { cleaned }

        // 1. 纯条号
        PURE_NO_RE.find(working)?.let { m ->
            parseChineseArticleNumber(m.groupValues[1])?.let { n ->
                return ParsedQuery(cleaned, exactNo = n, clauseHint = clauseHint)
            }
        }

        // 2. 法名前缀 + 条号
        COMPOUND_RE.find(working)?.let { m ->
            val lawPart = m.groupValues[1].trim()
            val numStr = m.groupValues[2].ifEmpty { m.groupValues[3] }
            parseChineseArticleNumber(numStr)?.let { n ->
                if (Regex("[\\u4e00-\\u9fa5]").containsMatchIn(lawPart)) {
                    return ParsedQuery(cleaned, exactNo = n, lawHint = lawPart, clauseHint = clauseHint)
                }
            }
        }

        // 3. 多 token 混合查询
        var exactNo: Int? = null
        var extractedLawHint = ""
        val remaining = mutableListOf<String>()
        val tokens = working.split(" ").filter { it.isNotEmpty() }
        for (tok in tokens) {
            var consumed = false
            if (exactNo == null) {
                SUB_COMPOUND_RE.find(tok)?.let { m ->
                    parseChineseArticleNumber(m.groupValues[2])?.let { n ->
                        exactNo = n
                        extractedLawHint = m.groupValues[1]
                        consumed = true
                    }
                }
                if (!consumed) {
                    ART_TOK_RE.find(tok)?.let { m ->
                        parseChineseArticleNumber(m.groupValues[1])?.let { n ->
                            exactNo = n
                            consumed = true
                        }
                    }
                }
                if (!consumed && ARABIC_RE.matches(tok) && tokens.size >= 2) {
                    exactNo = tok.toInt()
                    consumed = true
                }
            }
            if (!consumed) remaining.add(tok)
        }

        if (extractedLawHint.isEmpty() && exactNo != null && remaining.size == 1 && LAW_END_RE.matches(remaining[0])) {
            extractedLawHint = remaining[0]
            remaining.clear()
        }

        return ParsedQuery(cleaned, exactNo, extractedLawHint, clauseHint, remaining)
    }

    // ---------------- 语料 -> 归一化条目 ----------------
    fun buildEntries(corpus: StatuteCorpus): List<StatuteEntry> {
        val linkedByCivilNo = mutableMapOf<Int, MutableList<InterpRef>>()
        for (doc in corpus.companionDocs) {
            for (it in doc.items) {
                if (it.linkedCivil.isEmpty()) continue
                val snippet = it.text.replace(Regex("\\s+"), " ").trim().let { s ->
                    if (s.length > 180) s.take(180) + "…" else s
                }
                val ref = InterpRef(doc.shortTitle, it.label, snippet)
                for (no in it.linkedCivil) {
                    val bucket = linkedByCivilNo.getOrPut(no) { mutableListOf() }
                    if (bucket.size < 8) bucket.add(ref)
                }
            }
        }

        val list = mutableListOf<StatuteEntry>()
        for (a in corpus.civilArticles) {
            val validRaw = a.interpRefs.filter { it.docTitle.isNotBlank() || it.snippet.isNotBlank() }
            val refs = if (validRaw.isNotEmpty()) validRaw else (linkedByCivilNo[a.no] ?: emptyList())
            list.add(
                StatuteEntry(
                    key = "civil:${a.no}",
                    lawTitle = "民法典",
                    fullLawTitle = "中华人民共和国民法典",
                    articleNo = a.no,
                    articleLabel = a.label,
                    breadcrumb = listOf(a.book, a.chapter).filter { it.isNotBlank() }.joinToString(" · "),
                    summary = a.summary,
                    text = a.text,
                    litTips = a.litTips,
                    nonLitTips = a.nonLitTips,
                    interpRefs = refs,
                    isCivil = true,
                    isInterp = false
                )
            )
        }
        for (doc in corpus.companionDocs) {
            for (it in doc.items) {
                list.add(
                    StatuteEntry(
                        key = "${doc.id}:${it.no}",
                        lawTitle = doc.shortTitle,
                        fullLawTitle = doc.fullTitle.ifBlank { doc.shortTitle },
                        articleNo = it.no,
                        articleLabel = it.label,
                        breadcrumb = listOf(doc.docNo, it.section).filter { it.isNotBlank() }.joinToString(" · "),
                        text = it.text,
                        isCivil = false,
                        isInterp = INTERP_WORD_RE.containsMatchIn(doc.shortTitle)
                    )
                )
            }
        }
        return list
    }

    // ---------------- 分类筛选 ----------------
    enum class Scope(val id: String, val label: String) {
        ALL("all", "全部法规"),
        CIVIL("civil", "民法典"),
        IP("ip", "著作权/商标/反法"),
        PROCEDURE("procedure", "民诉法/证据/仲裁"),
        COMPANY("company", "公司法/合伙/破产"),
        INTERP("interp", "核心司法解释"),
        STARRED("starred", "⭐ 常看法条");

        companion object {
            fun fromId(id: String): Scope = entries.firstOrNull { it.id == id } ?: ALL
        }
    }

    private val IP_RE = Regex("著作权|商标|反不正当竞争|知识产权|网络侵害|人脸识别|个人信息|电子商务")
    private val PROC_RE = Regex("民事诉讼|证据|仲裁")
    private val COMPANY_RE = Regex("公司法|合伙企业|企业破产")

    fun matchScope(item: StatuteEntry, scope: Scope, starred: Set<String>): Boolean = when (scope) {
        Scope.ALL -> true
        Scope.CIVIL -> item.isCivil
        Scope.IP -> IP_RE.containsMatchIn(item.lawTitle)
        Scope.PROCEDURE -> PROC_RE.containsMatchIn(item.lawTitle)
        Scope.COMPANY -> COMPANY_RE.containsMatchIn(item.lawTitle)
        Scope.INTERP -> item.isInterp && !item.isCivil
        Scope.STARRED -> item.key in starred
    }

    // ---------------- 搜索 ----------------
    data class SearchResult(
        val parsed: ParsedQuery,
        val hits: List<StatuteEntry>,
        val uncollectedNotice: String = "",
        val clauseNotice: String = ""
    )

    fun search(
        entries: List<StatuteEntry>,
        rawQuery: String,
        scope: Scope = Scope.ALL,
        starred: Set<String> = emptySet(),
        limit: Int = 50
    ): SearchResult {
        val scoped = entries.filter { matchScope(it, scope, starred) }
        val parsed = parseStatuteQuery(rawQuery)
        if (parsed.raw.isEmpty()) {
            return SearchResult(parsed, scoped.take(limit))
        }

        val lawExists = parsed.lawHint.isEmpty() || scoped.any { matchLawHint(parsed.lawHint, it) }
        if (!lawExists) {
            return SearchResult(
                parsed,
                emptyList(),
                uncollectedNotice = "离线法规库暂未收录《${parsed.lawHint}》，系统已拒绝使用同名母法或其他条文静默替代；请前往国家法律法规数据库（flk.npc.gov.cn）核对权威现行原文。"
            )
        }

        if (parsed.exactNo != null && parsed.keywords.isEmpty()) {
            val byNo = scoped.filter {
                it.articleNo == parsed.exactNo && (parsed.lawHint.isEmpty() || matchLawHint(parsed.lawHint, it))
            }.sortedWith(compareBy<StatuteEntry> {
                if (parsed.lawHint.isNotEmpty() && it.lawTitle == parsed.lawHint) 0 else 1
            }.thenBy { if (it.isCivil) 0 else 1 }).take(limit)
            val notice = if (parsed.clauseHint.isNotEmpty() && byNo.isNotEmpty())
                "离线法规库按“条”粒度索引，已定位至第 ${parsed.exactNo} 条全文，请人工核对正文中「${parsed.clauseHint}」对应段落。" else ""
            return SearchResult(parsed, byNo, clauseNotice = notice)
        }

        val scored = mutableListOf<Pair<StatuteEntry, Int>>()
        for (item in scoped) {
            if (parsed.exactNo != null && item.articleNo != parsed.exactNo) continue
            if (parsed.lawHint.isNotEmpty() && !matchLawHint(parsed.lawHint, item)) continue

            val hay = "${item.lawTitle} ${item.fullLawTitle} ${item.articleLabel} 第${item.articleNo}条 ${item.summary} ${item.text}"
            if (!parsed.keywords.all { kw -> hay.contains(kw) || matchLawHint(kw, item) }) continue

            var score = 0
            if (parsed.exactNo != null && item.articleNo == parsed.exactNo) score += 60
            if (item.summary.isNotBlank() && parsed.keywords.any { item.summary.contains(it) }) score += 30
            if (parsed.keywords.any { item.lawTitle.contains(it) }) score += 25
            if (item.isCivil) score += 5
            scored.add(item to score)
            if (scored.size >= 300) break
        }
        val hits = scored.sortedWith(compareByDescending<Pair<StatuteEntry, Int>> { it.second }
            .thenBy { it.first.articleNo }).take(limit).map { it.first }
        val notice = if (parsed.clauseHint.isNotEmpty() && hits.isNotEmpty())
            "离线法规库按“条”粒度索引，已定位对应法条全文，请人工核对正文中「${parsed.clauseHint}」对应段落。" else ""
        return SearchResult(parsed, hits, clauseNotice = notice)
    }

    // ---------------- 引注 ----------------
    fun citation(entry: StatuteEntry, withVerification: Boolean, dataAsOf: String): String {
        val docNoPrefix = if (entry.breadcrumb.isNotBlank()) "（${entry.breadcrumb}）" else ""
        val base = "《${entry.fullLawTitle}》${entry.articleLabel}${docNoPrefix}：\n${entry.text}"
        return if (withVerification) {
            "$base\n—— 离线语料核验截至 $dataAsOf，正式提交前请核对国家法律法规数据库 https://flk.npc.gov.cn 现行有效文本"
        } else base
    }

    // ---------------- 高频速跳 ----------------
    val QUICK_JUMPS = listOf(
        "585" to "违约金调减(585)",
        "561" to "债之冲抵(561)",
        "563" to "法定解除(563)",
        "1195" to "避风港通知删除(1195)",
        "信息网络传播权" to "信息网络传播权",
        "法定赔偿" to "知产法定赔偿"
    )
}
