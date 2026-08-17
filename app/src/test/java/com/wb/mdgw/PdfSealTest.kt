package com.wb.mdgw

import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfSealTest {

    @Test
    fun sealRect_centered() {
        // 中心(100,100)，边长50 → 左下角矩形(75,75,50,50)
        val r: PDRectangle = PdfSeal.sealRect(100f, 100f, 50f)
        assertEquals(75f, r.lowerLeftX, 1e-6f)
        assertEquals(75f, r.lowerLeftY, 1e-6f)
        assertEquals(50f, r.width, 1e-6f)
        assertEquals(50f, r.height, 1e-6f)
    }

    @Test
    fun sealRect_clampedAtOrigin() {
        // 中心在原点且超出页面时，左下角不应为负（coerceAtLeast(0)）
        val r: PDRectangle = PdfSeal.sealRect(0f, 0f, 20f)
        assertEquals(0f, r.lowerLeftX, 1e-6f)
        assertEquals(0f, r.lowerLeftY, 1e-6f)
        assertEquals(20f, r.width, 1e-6f)
    }
}
