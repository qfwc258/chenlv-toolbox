package com.wb.mdgw.period

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 中国法律期间 / 诉讼时效统一计算引擎。
 *
 * 移植自参照仓库 qwertyzhu/cn-lawyer-ai-toolbox（Apache-2.0）src/lib/legalCalendar.ts，
 * 并内置 2025–2027 年国务院放假调休安排（2027 年依据国办发明电〔2026〕10号）。
 *
 * 覆盖规则：
 * - 严格 ISO YYYY-MM-DD 校验，空/非法日期绝不静默回退为某个默认日期；
 * - 《民法典》第 201–203 条、《民事诉讼法》第 85 条：期间自次日起算，
 *   按日/月/年计算，末日为法定休假日的顺延至节后首个工作日；
 * - 闰年/月末截断：2024-02-29 + 24 个月 → 2026-02-28，而非 2026-03-01；
 * - 三种顺延模式：
 *   · CIVIL_STATUTORY  民事法定期间：末日遇休息日顺延；
 *   · PRESERVATION_FREEZE 保全查封/冻结：起算日计入，到期不因节假日顺延，重叠时告警；
 *   · CRIMINAL_DETENTION 刑事拘留黄金 37 天：30 日提请 + 7 日批捕，不适用民事顺延。
 * - 跨年节假日覆盖提醒：超出 2025–2027 年表时仅按常规周末推算并显著提示核验。
 *
 * 纯 JVM，无 Android 依赖，便于单元测试。
 */
object LegalCalendarEngine {

    const val COVERED_MIN_YEAR = 2025
    const val COVERED_MAX_YEAR = 2027
    private const val MAX_WORKDAY_SPAN_DAYS = 73_050L

    /** 中国法定节假日（含调休放假日） */
    val CN_HOLIDAYS: Map<String, String> = linkedMapOf(
        // 2025
        "2025-01-01" to "元旦",
        "2025-01-28" to "春节（除夕）", "2025-01-29" to "春节", "2025-01-30" to "春节",
        "2025-01-31" to "春节", "2025-02-01" to "春节", "2025-02-02" to "春节",
        "2025-02-03" to "春节", "2025-02-04" to "春节",
        "2025-04-04" to "清明节", "2025-04-05" to "清明节", "2025-04-06" to "清明节",
        "2025-05-01" to "劳动节", "2025-05-02" to "劳动节", "2025-05-03" to "劳动节",
        "2025-05-04" to "劳动节", "2025-05-05" to "劳动节",
        "2025-05-31" to "端午节", "2025-06-01" to "端午节", "2025-06-02" to "端午节",
        "2025-10-01" to "国庆/中秋", "2025-10-02" to "国庆/中秋", "2025-10-03" to "国庆/中秋",
        "2025-10-04" to "国庆/中秋", "2025-10-05" to "国庆/中秋", "2025-10-06" to "国庆/中秋",
        "2025-10-07" to "国庆/中秋", "2025-10-08" to "国庆/中秋",
        // 2026
        "2026-01-01" to "元旦", "2026-01-02" to "元旦", "2026-01-03" to "元旦",
        "2026-02-15" to "春节", "2026-02-16" to "春节（除夕）", "2026-02-17" to "春节",
        "2026-02-18" to "春节", "2026-02-19" to "春节", "2026-02-20" to "春节",
        "2026-02-21" to "春节", "2026-02-22" to "春节", "2026-02-23" to "春节",
        "2026-04-04" to "清明节", "2026-04-05" to "清明节", "2026-04-06" to "清明节",
        "2026-05-01" to "劳动节", "2026-05-02" to "劳动节", "2026-05-03" to "劳动节",
        "2026-05-04" to "劳动节", "2026-05-05" to "劳动节",
        "2026-06-19" to "端午节", "2026-06-20" to "端午节", "2026-06-21" to "端午节",
        "2026-09-25" to "中秋节", "2026-09-26" to "中秋节", "2026-09-27" to "中秋节",
        "2026-10-01" to "国庆节", "2026-10-02" to "国庆节", "2026-10-03" to "国庆节",
        "2026-10-04" to "国庆节", "2026-10-05" to "国庆节", "2026-10-06" to "国庆节",
        "2026-10-07" to "国庆节",
        // 2027（国办发明电〔2026〕10号）
        "2027-01-01" to "元旦", "2027-01-02" to "元旦", "2027-01-03" to "元旦",
        "2027-02-05" to "春节（除夕）", "2027-02-06" to "春节", "2027-02-07" to "春节",
        "2027-02-08" to "春节", "2027-02-09" to "春节", "2027-02-10" to "春节",
        "2027-02-11" to "春节", "2027-02-12" to "春节", "2027-02-13" to "春节",
        "2027-04-03" to "清明节", "2027-04-04" to "清明节", "2027-04-05" to "清明节",
        "2027-05-01" to "劳动节", "2027-05-02" to "劳动节", "2027-05-03" to "劳动节",
        "2027-05-04" to "劳动节", "2027-05-05" to "劳动节",
        "2027-06-09" to "端午节", "2027-09-15" to "中秋节",
        "2027-10-01" to "国庆节", "2027-10-02" to "国庆节", "2027-10-03" to "国庆节",
        "2027-10-04" to "国庆节", "2027-10-05" to "国庆节", "2027-10-06" to "国庆节",
        "2027-10-07" to "国庆节"
    )

    /** 国务院调休补班日（周末但计为工作日） */
    val CN_ADJUSTED_WORKDAYS: Set<String> = setOf(
        // 2025
        "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
        // 2026
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10",
        // 2027（国办发明电〔2026〕10号）
        "2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09"
    )

    enum class RolloverMode {
        CIVIL_STATUTORY,        // 民事法定期间：末日休假日顺延
        PRESERVATION_FREEZE,    // 保全查封/冻结：起算日计入、到期不顺延
        CRIMINAL_DETENTION      // 刑事拘留黄金 37 天：30+7，不顺延
    }

    enum class VerificationStatus {
        EXACT, MANUAL_OVERRIDE, PENDING_HOLIDAY_SCHEDULE, CONFLICT_INVALID
    }

    data class RestCheck(val rest: Boolean, val reason: String)
    data class Extension(val finalYmd: String, val extended: Boolean, val reasons: List<String>)

    data class DeadlineParams(
        val startYmd: String,
        val rolloverMode: RolloverMode,
        val days: Int = 0,
        val months: Int = 0,
        val years: Int = 0,
        val warnDaysBefore: Int = 3,
        val manualDueYmd: String = ""
    )

    data class ComputedDeadline(
        val startYmd: String,
        val countStartYmd: String,
        val rawEndYmd: String,
        val finalEndYmd: String,
        val warnYmd: String,
        val extended: Boolean,
        val reasons: List<String>,
        val rolloverMode: RolloverMode,
        val outsideHolidayCoverage: Boolean,
        val hasDateConflict: Boolean = false,
        val verificationStatus: VerificationStatus,
        val holidayWarningText: String = "",
        val day30Ymd: String? = null
    )

    data class WorkdayInterval(
        val signedDays: Long,
        val absDays: Long,
        val inclusiveTotalDays: Long,
        val weeks: Long,
        val remDays: Long,
        val workdays: Int,
        val weekendOrHoliday: Int,
        val holidaysHit: List<String>,
        val adjustedHit: List<String>,
        val outsideHolidayCoverage: Boolean,
        val exceededMaxRange: Boolean
    )

    /** 中国时区当天日期；避免设备时区为境外时倒计时错位 */
    fun todayChinaYmd(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    fun isValidIsoYmd(ymd: String): Boolean {
        val s = ymd.trim()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        val date = parseYmd(s) ?: return false
        return date.year in 1900..2100
    }

    fun parseYmd(ymd: String): LocalDate? = runCatching { LocalDate.parse(ymd.trim()) }.getOrNull()

    fun formatYmd(date: LocalDate): String =
        "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)

    fun weekdayLabel(ymd: String): String {
        val d = parseYmd(ymd) ?: return ""
        return when (d.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
    }

    fun formatCnYmd(ymd: String): String =
        if (!isValidIsoYmd(ymd)) "待输入有效日期" else "$ymd（${weekdayLabel(ymd)}）"

    fun addDaysYmd(ymd: String, deltaDays: Int): String {
        val d = parseYmd(ymd) ?: return ""
        return formatYmd(d.plusDays(deltaDays.toLong()))
    }

    fun diffDaysYmd(fromYmd: String, toYmd: String): Long? {
        val a = parseYmd(fromYmd) ?: return null
        val b = parseYmd(toYmd) ?: return null
        return ChronoUnit.DAYS.between(a, b)
    }

    /** 按月/年推算并截断到目标月最后一日；inclusiveStartDay 用于保全「起算日计入」口径 */
    fun addMonthsLegalYmd(startYmd: String, months: Int, inclusiveStartDay: Boolean = false): String {
        val d = parseYmd(startYmd) ?: return ""
        val target = d.plusMonths(months.toLong())
        val inclusiveApplied = inclusiveStartDay && d.dayOfMonth <= target.lengthOfMonth()
        return formatYmd(if (inclusiveApplied) target.minusDays(1) else target)
    }

    fun isRestDayYmd(ymd: String): RestCheck {
        if (!isValidIsoYmd(ymd)) return RestCheck(false, "未知日期")
        CN_HOLIDAYS[ymd]?.let { return RestCheck(true, "法定节假日（$it）") }
        if (ymd in CN_ADJUSTED_WORKDAYS) return RestCheck(false, "法定调休工作日")
        val d = parseYmd(ymd)!!
        return when (d.dayOfWeek.value) {
            6 -> RestCheck(true, "周六公休日")
            7 -> RestCheck(true, "周日公休日")
            else -> RestCheck(false, "正常工作日")
        }
    }

    /** 末日为休息日时顺延至首个工作日（带 25 次防呆上限） */
    fun extendPastRestDaysYmd(ymd: String): Extension {
        if (!isValidIsoYmd(ymd)) return Extension("", false, emptyList())
        var cur = ymd
        val reasons = mutableListOf<String>()
        var guard = 0
        while (guard < 25) {
            val check = isRestDayYmd(cur)
            if (!check.rest) break
            reasons.add("$cur ${check.reason}")
            cur = addDaysYmd(cur, 1)
            guard++
        }
        return Extension(cur, reasons.isNotEmpty(), reasons)
    }

    fun isDateOutsideHolidayCoverage(ymd: String): Boolean {
        val d = parseYmd(ymd) ?: return true
        return d.year < COVERED_MIN_YEAR || d.year > COVERED_MAX_YEAR
    }

    /** 法律期间主计算入口 */
    fun computeLegalDeadline(p: DeadlineParams): ComputedDeadline? {
        if (!isValidIsoYmd(p.startYmd)) return null
        val start = p.startYmd.trim()
        val countStart = if (p.rolloverMode == RolloverMode.PRESERVATION_FREEZE)
            start else addDaysYmd(start, 1)

        // 文书载明具体届满日优先（选填）
        if (p.manualDueYmd.isNotBlank()) {
            val manual = p.manualDueYmd.trim()
            if (!isValidIsoYmd(manual)) return null
            val warn = addDaysYmd(manual, -p.warnDaysBefore)
            if (manual < start) {
                return ComputedDeadline(
                    startYmd = start, countStartYmd = countStart,
                    rawEndYmd = manual, finalEndYmd = manual, warnYmd = warn,
                    extended = false, reasons = emptyList(), rolloverMode = p.rolloverMode,
                    outsideHolidayCoverage = false, hasDateConflict = true,
                    verificationStatus = VerificationStatus.CONFLICT_INVALID,
                    holidayWarningText = "日期倒挂冲突：手输文书届满日（$manual）早于起算日（$start），请核对送达回证与文书载明日期后再登记！"
                )
            }
            return ComputedDeadline(
                startYmd = start, countStartYmd = countStart,
                rawEndYmd = manual, finalEndYmd = manual, warnYmd = warn,
                extended = false, reasons = emptyList(), rolloverMode = p.rolloverMode,
                outsideHolidayCoverage = false,
                verificationStatus = VerificationStatus.MANUAL_OVERRIDE,
                holidayWarningText = "按手动录入的文书载明届满日为准"
            )
        }

        // 刑事拘留黄金 37 天（刑事强制措施不适用民事休假日顺延）
        if (p.rolloverMode == RolloverMode.CRIMINAL_DETENTION) {
            val day30 = addDaysYmd(start, 30)
            val day37 = addDaysYmd(start, 37)
            return ComputedDeadline(
                startYmd = start, countStartYmd = countStart,
                rawEndYmd = day37, finalEndYmd = day37,
                warnYmd = addDaysYmd(day30, -p.warnDaysBefore),
                extended = false, reasons = emptyList(), rolloverMode = p.rolloverMode,
                outsideHolidayCoverage = false,
                verificationStatus = VerificationStatus.EXACT,
                day30Ymd = day30
            )
        }

        val totalMonths = p.years * 12 + p.months
        var rawEnd = start
        if (totalMonths > 0) {
            rawEnd = addMonthsLegalYmd(
                start, totalMonths,
                inclusiveStartDay = p.rolloverMode == RolloverMode.PRESERVATION_FREEZE
            )
        }
        if (p.days > 0) rawEnd = addDaysYmd(rawEnd, p.days)
        val warn = addDaysYmd(rawEnd, -p.warnDaysBefore)

        // 保全查封/冻结：到期日不顺延，但若撞上休息日/超出节假日表须显著提示提前续封
        if (p.rolloverMode == RolloverMode.PRESERVATION_FREEZE) {
            val rest = isRestDayYmd(rawEnd)
            val outside = isDateOutsideHolidayCoverage(rawEnd)
            val warning = when {
                rest.rest -> "注意：保全期限届满日（$rawEnd）恰逢${rest.reason}，法院通常在届满当日自动解封/解冻、不适用民事期间顺延，请务必提前提交续封申请！"
                outside -> "提示：保全届满日（$rawEnd）超出已发布 $COVERED_MIN_YEAR–$COVERED_MAX_YEAR 年节假日表，保全到期不顺延，请提前 30 日提交续封申请。"
                else -> ""
            }
            return ComputedDeadline(
                startYmd = start, countStartYmd = countStart,
                rawEndYmd = rawEnd, finalEndYmd = rawEnd, warnYmd = warn,
                extended = false, reasons = if (rest.rest) listOf("$rawEnd ${rest.reason}（保全到期不顺延）") else emptyList(),
                rolloverMode = p.rolloverMode, outsideHolidayCoverage = outside,
                verificationStatus = VerificationStatus.EXACT, holidayWarningText = warning
            )
        }

        // 民事法定期间：末日休息日顺延至首个工作日
        val ext = extendPastRestDaysYmd(rawEnd)
        val outside = isDateOutsideHolidayCoverage(rawEnd) || isDateOutsideHolidayCoverage(ext.finalYmd)
        return ComputedDeadline(
            startYmd = start, countStartYmd = countStart,
            rawEndYmd = rawEnd, finalEndYmd = ext.finalYmd,
            warnYmd = addDaysYmd(ext.finalYmd, -p.warnDaysBefore),
            extended = ext.extended, reasons = ext.reasons,
            rolloverMode = p.rolloverMode, outsideHolidayCoverage = outside,
            verificationStatus = if (outside) VerificationStatus.PENDING_HOLIDAY_SCHEDULE else VerificationStatus.EXACT,
            holidayWarningText = if (outside)
                "【待核验提醒】推算届满日（${ext.finalYmd}）超出本工具已收录的 $COVERED_MIN_YEAR–$COVERED_MAX_YEAR 年国务院放假调休表，当前仅按常规周六/周日推算，登记前请核对国务院当年正式通知及法院文书！"
            else ""
        )
    }

    /** 法定工作日与日期间隔（含首尾逐日核算，不静默截断） */
    fun calculateWorkdayInterval(startYmd: String, endYmd: String): WorkdayInterval? {
        val signed = diffDaysYmd(startYmd, endYmd) ?: return null
        val abs = kotlin.math.abs(signed)
        val inclusive = abs + 1
        val from = if (signed >= 0) startYmd else endYmd
        val to = if (signed >= 0) endYmd else startYmd
        val outside = isDateOutsideHolidayCoverage(from) || isDateOutsideHolidayCoverage(to)

        if (abs > MAX_WORKDAY_SPAN_DAYS) {
            return WorkdayInterval(signed, abs, inclusive, abs / 7, abs % 7, 0, 0,
                emptyList(), emptyList(), true, true)
        }

        var cur = parseYmd(from)!!
        val end = parseYmd(to)!!
        var workdays = 0
        var restDays = 0
        val holidaysHit = mutableListOf<String>()
        val adjustedHit = mutableListOf<String>()
        while (!cur.isAfter(end)) {
            val iso = formatYmd(cur)
            when {
                CN_HOLIDAYS.containsKey(iso) -> {
                    restDays++
                    if (holidaysHit.size < 24) holidaysHit.add("$iso ${CN_HOLIDAYS[iso]}")
                }
                iso in CN_ADJUSTED_WORKDAYS -> {
                    workdays++
                    if (adjustedHit.size < 24) adjustedHit.add(iso)
                }
                cur.dayOfWeek.value == 6 || cur.dayOfWeek.value == 7 -> restDays++
                else -> workdays++
            }
            cur = cur.plusDays(1)
        }
        return WorkdayInterval(signed, abs, inclusive, abs / 7, abs % 7, workdays, restDays,
            holidaysHit, adjustedHit, outside, false)
    }
}
