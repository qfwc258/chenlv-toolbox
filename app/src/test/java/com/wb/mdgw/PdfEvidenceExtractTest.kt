package com.wb.mdgw

import com.wb.mdgw.PdfEvidenceExtract.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfEvidenceExtractTest {

    @Test
    fun parse_mixedRangesAndSingles() {
        val p = PdfEvidenceExtract.parse("1,2-3,4-15,16-18,19")
        assertNull(p.error)
        assertEquals(
            listOf(Segment(1, 1), Segment(2, 3), Segment(4, 15), Segment(16, 18), Segment(19, 19)),
            p.segments
        )
    }

    @Test
    fun parse_reversedRangeIsSwapped() {
        val p = PdfEvidenceExtract.parse("10-2")
        assertNull(p.error)
        assertEquals(listOf(Segment(2, 10)), p.segments)
    }

    @Test
    fun parse_tolerantToSpacesAndChineseComma() {
        val p = PdfEvidenceExtract.parse(" 1 ， 2 - 3 ,4")
        assertNull(p.error)
        assertEquals(listOf(Segment(1, 1), Segment(2, 3), Segment(4, 4)), p.segments)
    }

    @Test
    fun parse_ignoresEmptySegments() {
        val p = PdfEvidenceExtract.parse("1,,2-3,")
        assertNull(p.error)
        assertEquals(listOf(Segment(1, 1), Segment(2, 3)), p.segments)
    }

    @Test
    fun parse_emptyInputReturnsError() {
        assertNotNull(PdfEvidenceExtract.parse("  ").error)
    }

    @Test
    fun parse_invalidTokenReturnsError() {
        assertNotNull(PdfEvidenceExtract.parse("1,abc,3").error)
        assertNotNull(PdfEvidenceExtract.parse("1,2-").error)
    }

    @Test
    fun validate_reportsOutOfBounds() {
        val segs = listOf(Segment(1, 1), Segment(4, 9))
        assertNull(PdfEvidenceExtract.validate(segs, 10))
        assertNotNull(PdfEvidenceExtract.validate(segs, 8))
        assertNotNull(PdfEvidenceExtract.validate(segs, 0))
    }

    @Test
    fun outputName_usesEvidenceIndexAndSanitizes() {
        assertEquals("证据1-合同首页.pdf", PdfEvidenceExtract.outputName(0, "合同首页"))
        assertEquals("证据2.pdf", PdfEvidenceExtract.outputName(1, "  "))
        assertEquals("证据3-a_b.pdf", PdfEvidenceExtract.outputName(2, "a/b"))
    }
}
