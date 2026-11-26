package com.wb.mdgw.injury

import org.junit.Assert.*
import org.junit.Test

/** 人身损害 / 交通事故赔偿引擎单测。 */
class PersonInjuryEngineTest {

    @Test
    fun `compensation years fold by age`() {
        assertEquals(20, PersonInjuryEngine.compensationYears(38))
        assertEquals(20, PersonInjuryEngine.compensationYears(60))
        assertEquals(19, PersonInjuryEngine.compensationYears(61))
        assertEquals(5, PersonInjuryEngine.compensationYears(75))
        assertEquals(5, PersonInjuryEngine.compensationYears(85))
    }

    @Test
    fun `disability coefficient by grade`() {
        assertEquals(1.0, PersonInjuryEngine.coeff(0), 0.001)   // 死亡
        assertEquals(1.0, PersonInjuryEngine.coeff(1), 0.001)
        assertEquals(0.1, PersonInjuryEngine.coeff(10), 0.001)
        assertEquals(0.4, PersonInjuryEngine.coeff(7), 0.001)
    }

    @Test
    fun `disability compensation equals income times years times coeff`() {
        val p = PersonInjuryParams(urbanDisposable = 80_000.0)
        val r = PersonInjuryEngine.calc(PersonInjuryCase(grade = 10, age = 40, medicalFee = 0.0,
            lostDays = 0.0, nurseDays = 0.0, hospDays = 0.0, nutritionDays = 0.0, mentalFee = 0.0), p)
        // 8万 × 20年 × 10% = 16万
        assertEquals(160_000.0, r.items.first().amount, 0.01)
        assertEquals(160_000.0, r.grossTotal, 0.01)
    }

    @Test
    fun `death case adds funeral fee half of non private wage`() {
        val p = PersonInjuryParams(urbanDisposable = 80_000.0, nonPrivateYearWage = 120_000.0)
        val r = PersonInjuryEngine.calc(PersonInjuryCase(grade = 0, age = 50, medicalFee = 0.0,
            lostDays = 0.0, nurseDays = 0.0, hospDays = 0.0, nutritionDays = 0.0, mentalFee = 0.0), p)
        assertTrue(r.items.any { it.name.contains("丧葬费") && it.amount == 60_000.0 })
    }

    @Test
    fun `compulsory insurance pays first then fault ratio applies to remainder`() {
        val p = PersonInjuryParams(urbanDisposable = 80_000.0)
        // 残疾赔偿金 = 8万×20×100%(1级) = 160万，医疗费 3万
        val c = PersonInjuryCase(grade = 1, age = 40, medicalFee = 30_000.0,
            lostDays = 0.0, nurseDays = 0.0, hospDays = 0.0, nutritionDays = 0.0, mentalFee = 0.0,
            faultPct = 100.0, hasCompulsoryIns = true)
        val r = PersonInjuryEngine.calc(c, p)
        assertEquals(180_000.0 + 18_000.0, r.compulsoryPay, 0.01)  // 医疗3万>1.8万封顶，伤残>18万封顶
        assertEquals(1_630_000.0 - 198_000.0, r.commercialOrPartyPay, 0.01)
        assertEquals(1_630_000.0, r.estimatedRecoverable, 0.01)
    }

    @Test
    fun `fault ratio reduces recoverable`() {
        val p = PersonInjuryParams(urbanDisposable = 80_000.0)
        val c = PersonInjuryCase(grade = 10, age = 40, medicalFee = 0.0,
            lostDays = 0.0, nurseDays = 0.0, hospDays = 0.0, nutritionDays = 0.0, mentalFee = 0.0,
            faultPct = 30.0, hasCompulsoryIns = false)
        val r = PersonInjuryEngine.calc(c, p)
        assertEquals(160_000.0, r.grossTotal, 0.01)
        assertEquals(48_000.0, r.commercialOrPartyPay, 0.01)
        assertEquals(0.0, r.compulsoryPay, 0.01)
        assertEquals(48_000.0, r.estimatedRecoverable, 0.01)
    }
}
