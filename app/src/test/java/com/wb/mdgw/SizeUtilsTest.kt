package com.wb.mdgw

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeUtilsTest {

    @Test
    fun cmToPt_basic() {
        // 1 英寸 = 2.54 cm = 72 pt
        assertEquals(72f, SizeUtils.cmToPt(2.54f), 1e-4f)
        assertEquals(2.54f, SizeUtils.ptToCm(72f), 1e-4f)
    }

    @Test
    fun presets_matchSpec() {
        assertEquals(SizeUtils.cmToPt(SizeUtils.COMPANY_SEAL_CM), SizeUtils.companyPt, 1e-4f)
        assertEquals(SizeUtils.cmToPt(SizeUtils.FINANCE_SEAL_CM), SizeUtils.financePt, 1e-4f)
        assertEquals(SizeUtils.cmToPt(SizeUtils.PERSON_SEAL_CM), SizeUtils.personPt, 1e-4f)
    }
}
