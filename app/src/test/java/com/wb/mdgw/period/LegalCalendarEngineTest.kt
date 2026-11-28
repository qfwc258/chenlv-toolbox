package com.wb.mdgw.period

import com.wb.mdgw.period.LegalCalendarEngine.RolloverMode
import com.wb.mdgw.period.LegalCalendarEngine.VerificationStatus
import org.junit.Assert.*
import org.junit.Test

/** 移植自参照仓库 legalCalendar.ts 的期间算法回归测试 */
class LegalCalendarEngineTest {

    @Test
    fun `strict ISO date validation rejects empty and impossible dates`() {
        assertTrue(LegalCalendarEngine.isValidIsoYmd("2026-02-28"))
        assertFalse(LegalCalendarEngine.isValidIsoYmd(""))
        assertFalse(LegalCalendarEngine.isValidIsoYmd("2026-2-1"))
        assertFalse(LegalCalendarEngine.isValidIsoYmd("2026-02-30"))
        assertNull(LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("", RolloverMode.CIVIL_STATUTORY, days = 15)
        ))
    }

    @Test
    fun `civil period starts next day and does not roll over on ordinary workday`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2026-03-10", RolloverMode.CIVIL_STATUTORY, days = 15)
        )!!
        assertEquals("2026-03-11", c.countStartYmd)
        assertEquals("2026-03-25", c.rawEndYmd)
        assertEquals("2026-03-25", c.finalEndYmd)
        assertFalse(c.extended)
        assertEquals(VerificationStatus.EXACT, c.verificationStatus)
    }

    @Test
    fun `civil deadline falling on weekend rolls to first workday`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2026-05-01", RolloverMode.CIVIL_STATUTORY, days = 15)
        )!!
        assertEquals("2026-05-16", c.rawEndYmd)
        assertTrue(c.extended)
        assertEquals("2026-05-18", c.finalEndYmd)
        assertTrue(c.reasons.any { it.contains("周六公休日") })
    }

    @Test
    fun `civil deadline falling on national holiday rolls to next workday`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2026-09-12", RolloverMode.CIVIL_STATUTORY, days = 15)
        )!!
        assertEquals("2026-09-27", c.rawEndYmd)
        assertEquals("2026-09-28", c.finalEndYmd)
        assertTrue(c.reasons.any { it.contains("中秋节") })
    }

    @Test
    fun `leap day plus 24 months clamps to February 28`() {
        assertEquals("2026-02-28", LegalCalendarEngine.addMonthsLegalYmd("2024-02-29", 24))
    }

    @Test
    fun `preservation freeze counts start day and never rolls over`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2026-03-14", RolloverMode.PRESERVATION_FREEZE, months = 12)
        )!!
        assertEquals("2026-03-14", c.countStartYmd)
        assertEquals("2027-03-13", c.rawEndYmd)
        assertEquals(c.rawEndYmd, c.finalEndYmd)
        assertFalse(c.extended)
        assertTrue(c.holidayWarningText.contains("续封"))
        assertTrue(LegalCalendarEngine.isRestDayYmd(c.finalEndYmd).rest)
    }

    @Test
    fun `criminal detention golden 37 days has no civil rollover`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2026-01-01", RolloverMode.CRIMINAL_DETENTION, days = 37)
        )!!
        assertEquals("2026-01-31", c.day30Ymd)
        assertEquals("2026-02-07", c.finalEndYmd)
        assertFalse(c.extended)
    }

    @Test
    fun `manual due date overrides computation and detects inverted conflict`() {
        val base = LegalCalendarEngine.DeadlineParams(
            "2026-03-10", RolloverMode.CIVIL_STATUTORY, days = 15
        )
        val manual = LegalCalendarEngine.computeLegalDeadline(base.copy(manualDueYmd = "2026-04-01"))!!
        assertEquals(VerificationStatus.MANUAL_OVERRIDE, manual.verificationStatus)
        assertEquals("2026-04-01", manual.finalEndYmd)

        val conflict = LegalCalendarEngine.computeLegalDeadline(base.copy(manualDueYmd = "2026-01-01"))!!
        assertEquals(VerificationStatus.CONFLICT_INVALID, conflict.verificationStatus)
        assertTrue(conflict.hasDateConflict)
    }

    @Test
    fun `deadline beyond holiday coverage is flagged for verification`() {
        val c = LegalCalendarEngine.computeLegalDeadline(
            LegalCalendarEngine.DeadlineParams("2028-01-01", RolloverMode.CIVIL_STATUTORY, days = 15)
        )!!
        assertEquals(VerificationStatus.PENDING_HOLIDAY_SCHEDULE, c.verificationStatus)
        assertTrue(c.holidayWarningText.contains("待核验"))
    }

    @Test
    fun `workday interval excludes holidays and counts adjusted weekend workdays`() {
        val holiday = LegalCalendarEngine.calculateWorkdayInterval("2026-10-01", "2026-10-07")!!
        assertEquals(6L, holiday.absDays)
        assertEquals(0, holiday.workdays)
        assertEquals(7, holiday.weekendOrHoliday)
        assertEquals(7, holiday.holidaysHit.size)

        val makeup = LegalCalendarEngine.calculateWorkdayInterval("2026-10-10", "2026-10-10")!!
        assertEquals(1, makeup.workdays)
        assertEquals(0, makeup.weekendOrHoliday)
        assertEquals(listOf("2026-10-10"), makeup.adjustedHit)
    }

    @Test
    fun `presets contain all reference rules with unique ids`() {
        assertEquals(17, LegalPresets.ALL.size)
        assertEquals(17, LegalPresets.ALL.map { it.id }.distinct().size)
        assertTrue(LegalPresets.ALL.any { it.isCriminal37 })
        assertTrue(LegalPresets.ALL.count {
            it.rolloverMode == RolloverMode.PRESERVATION_FREEZE
        } == 3)
        assertTrue(LegalPresets.ALL.all { it.rule.isNotBlank() && it.inputLabel.isNotBlank() })
    }
}
