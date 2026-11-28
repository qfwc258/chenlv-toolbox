package com.wb.mdgw.period

import com.wb.mdgw.period.LegalCalendarEngine.RolloverMode

/**
 * 高频法律期限模板。
 *
 * 前 11 项移植自参照仓库 src/tools/website/DateCalculator.tsx 的 LEGAL_PRESETS；
 * 后 6 项来自 src/tools/extra/PreservationDeadlineTool.tsx 的 PRESETS
 * （与前者重复的「劳动仲裁后起诉 15 日」「申请执行 2 年」不再重复收录）。
 * 所有依据条文原文保留，便于办案时直接核对。
 */
data class LegalPreset(
    val id: String,
    val label: String,
    val shortLabel: String,
    val category: String,
    val rule: String,
    val inputLabel: String,
    val rolloverMode: RolloverMode,
    val days: Int = 0,
    val years: Int = 0,
    val months: Int = 0,
    val warnDaysBefore: Int = 3,
    val isCriminal37: Boolean = false
) {
    /** 供引擎使用的参数（起算日由界面提供） */
    fun params(startYmd: String, manualDueYmd: String = "") = LegalCalendarEngine.DeadlineParams(
        startYmd = startYmd,
        rolloverMode = rolloverMode,
        days = days,
        years = years,
        months = months,
        warnDaysBefore = warnDaysBefore,
        manualDueYmd = manualDueYmd
    )
}

object LegalPresets {

    val ALL: List<LegalPreset> = listOf(
        LegalPreset(
            id = "appeal_judgment",
            label = "民事一审判决上诉期（15日）",
            shortLabel = "判决上诉 15日",
            category = "民事诉讼",
            rule = "《民事诉讼法》第171条：自判决书送达之日起十五日内有权向上一级人民法院提起上诉。期间开始的次日起算，最后一日为法定休假日的顺延至首个工作日。",
            inputLabel = "收到一审判决书日期（送达日）",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        ),
        LegalPreset(
            id = "appeal_ruling",
            label = "民事一审裁定上诉期（10日）",
            shortLabel = "裁定上诉 10日",
            category = "民事诉讼",
            rule = "《民事诉讼法》第171条：不服第一审裁定（如管辖权异议裁定、驳回起诉裁定），自裁定书送达之日起十日内有权上诉。",
            inputLabel = "收到一审裁定书日期（送达日）",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 10
        ),
        LegalPreset(
            id = "labor_arb_suit_15",
            label = "劳动仲裁裁决后起诉期（15日）",
            shortLabel = "仲裁后起诉 15日",
            category = "劳动争议",
            rule = "《劳动争议调解仲裁法》第50条、《民事诉讼法》第85条：非终局裁决当事人自收到仲裁裁决书之日起十五日内可向人民法院提起诉讼；最后一日遇法定休假日的顺延至首个工作日。",
            inputLabel = "收到劳动仲裁裁决书日期（送达日）",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        ),
        LegalPreset(
            id = "criminal_37",
            label = "刑事拘留「黄金 37 天」（30日+7日）",
            shortLabel = "刑事黄金 37天",
            category = "刑事辩护",
            rule = "《刑事诉讼法》第91条：对流窜作案、多次作案、结伙作案的重大嫌疑分子，公安机关提请审查批准逮捕的时间可延长至30日，人民检察院自接到提请后7日以内作出决定（刑事强制措施期限不套用民事休假日顺延规则）。",
            inputLabel = "刑事拘留执行日期（拘留通知书所载日期）",
            rolloverMode = RolloverMode.CRIMINAL_DETENTION,
            days = 37,
            isCriminal37 = true
        ),
        LegalPreset(
            id = "jurisdiction_objection",
            label = "民事答辩期 / 管辖权异议期（15日）",
            shortLabel = "答辩/管辖异议 15日",
            category = "民事诉讼",
            rule = "《民事诉讼法》第128条、第130条：被告应当在收到起诉状副本之日起十五日内提出答辩状；对管辖权有异议的，应当在提交答辩状期间提出。",
            inputLabel = "签收起诉状副本日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        ),
        LegalPreset(
            id = "evidence_summary_15",
            label = "简易程序举证期限（上限15日 · 按15日测算）",
            shortLabel = "简易举证 ≤15日",
            category = "民事诉讼",
            rule = "《最高法关于民事诉讼证据的若干规定》第51条第2款：适用简易程序审理的案件，人民法院指定的举证期限不得超过十五日（小额诉讼一般不超过七日；具体以法院《举证通知书》载明日期为准）。",
            inputLabel = "收到举证通知书日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        ),
        LegalPreset(
            id = "evidence_ordinary_min15",
            label = "普通程序最低举证期限（下限15日 · 按15日测算）",
            shortLabel = "普通举证 ≥15日",
            category = "民事诉讼",
            rule = "《最高法关于民事诉讼证据的若干规定》第51条第1款：人民法院指定第一审普通程序案件的举证期限不得少于十五日（具体以《举证通知书》指定天数为准）。",
            inputLabel = "收到举证通知书日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        ),
        LegalPreset(
            id = "evidence_30",
            label = "普通程序常见指定举证期（30日参考）",
            shortLabel = "普通举证 30日",
            category = "民事诉讼",
            rule = "实务常见一审普通程序指定举证期为 30 日；如涉案证据复杂确需延期，应在举证期限届满前书面申请延长。",
            inputLabel = "收到举证通知书日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 30
        ),
        LegalPreset(
            id = "labor_arb_1y",
            label = "劳动争议申请仲裁时效（1年）",
            shortLabel = "劳动仲裁 1年",
            category = "劳动争议",
            rule = "《劳动争议调解仲裁法》第27条：劳动争议申请仲裁的时效期间为一年，自当事人知道或者应当知道其权利被侵害之日起计算（拖欠劳动报酬争议在劳动关系存续期间不受一年限制，终止后一年内提出）。",
            inputLabel = "知道或应当知道权利被侵害之日",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            years = 1
        ),
        LegalPreset(
            id = "exec_2y",
            label = "申请强制执行期限（2年）",
            shortLabel = "申请执行 2年",
            category = "执行程序",
            rule = "《民事诉讼法》第250条、《民法典》第202条：申请执行的期间为二年，从法律文书规定履行期间的最后一日起计算；无对应日则以该月最后一日为届满日。",
            inputLabel = "履行期届满日 / 文书生效日",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            years = 2
        ),
        LegalPreset(
            id = "civil_3y",
            label = "普通民事诉讼时效（3年）",
            shortLabel = "民事时效 3年",
            category = "民事诉讼",
            rule = "《民法典》第188条：向人民法院请求保护民事权利的诉讼时效期间为三年，自权利人知道或者应当知道权利受到损害以及义务人之日起计算（涉外货物买卖合同为四年）。",
            inputLabel = "知道或应当知道权利受损及义务人之日",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            years = 3
        ),

        // ---- 保全与执行专项（来自 PreservationDeadlineTool） ----
        LegalPreset(
            id = "bank_freeze_1y",
            label = "银行存款账户冻结（最长 1 年 · 提前30日续冻）",
            shortLabel = "银行冻结 1年",
            category = "保全期限",
            rule = "《民诉法解释》第487条：人民法院冻结被执行人的银行存款的期限不得超过一年；保全查封/冻结到期自动解冻、不适用民事期间顺延，应在届满前提前提交续冻申请。",
            inputLabel = "冻结措施执行日期",
            rolloverMode = RolloverMode.PRESERVATION_FREEZE,
            months = 12,
            warnDaysBefore = 30
        ),
        LegalPreset(
            id = "movable_seize_2y",
            label = "动产查封 / 扣押（最长 2 年 · 提前30日续封）",
            shortLabel = "动产查封 2年",
            category = "保全期限",
            rule = "《民诉法解释》第487条：查封、扣押动产的期限不得超过二年（到期不因节假日顺延，请提前续封）。",
            inputLabel = "查封/扣押执行日期",
            rolloverMode = RolloverMode.PRESERVATION_FREEZE,
            months = 24,
            warnDaysBefore = 30
        ),
        LegalPreset(
            id = "real_estate_equity_3y",
            label = "不动产查封 / 股权等财产权冻结（最长 3 年 · 提前30日续封）",
            shortLabel = "不动产查封 3年",
            category = "保全期限",
            rule = "《民诉法解释》第487条：查封不动产、冻结其他财产权的期限不得超过三年（到期不因节假日顺延，请提前续封）。",
            inputLabel = "查封/冻结执行日期",
            rolloverMode = RolloverMode.PRESERVATION_FREEZE,
            months = 36,
            warnDaysBefore = 30
        ),
        LegalPreset(
            id = "appraisal_report_obj_5d",
            label = "财产评估报告书面异议期（收到报告起 5 日）",
            shortLabel = "评估异议 5日",
            category = "执行程序",
            rule = "《最高人民法院关于人民法院确定财产处置参考价若干问题的规定》第22–24条：当事人、利害关系人收到评估报告后五日内可提出书面异议（逾期不予受理；末日为法定休假日的顺延至节后首个工作日）。",
            inputLabel = "收到评估报告日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 5,
            warnDaysBefore = 2
        ),
        LegalPreset(
            id = "exec_reconsideration_10d",
            label = "执行异议裁定申请复议期（收到裁定起 10 日）",
            shortLabel = "执行复议 10日",
            category = "执行程序",
            rule = "《民事诉讼法》第236条：当事人、利害关系人对执行行为异议裁定不服的，可自裁定送达之日起十日内向上一级人民法院申请复议。",
            inputLabel = "收到执行异议裁定日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 10
        ),
        LegalPreset(
            id = "exec_outsider_suit_15d",
            label = "案外人执行异议之诉起诉期（收到裁定起 15 日）",
            shortLabel = "案外人异议 15日",
            category = "执行程序",
            rule = "《民事诉讼法》第238条：案外人、当事人对执行标的异议裁定不服，认为与原判决、裁定无关的，自裁定送达之日起十五日内向人民法院提起诉讼（遇法定休假日顺延至首个工作日）。",
            inputLabel = "收到执行标的异议裁定日期",
            rolloverMode = RolloverMode.CIVIL_STATUTORY,
            days = 15
        )
    )

    fun byId(id: String): LegalPreset = ALL.firstOrNull { it.id == id } ?: ALL.first()

    val categories: List<String> = ALL.map { it.category }.distinct()
}
