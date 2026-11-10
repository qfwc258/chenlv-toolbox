package com.wb.mdgw.docgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并后的统一「类型」内置列表与字段结构映射校验（不依赖 Context）。 */
class DocGenTypeStoreTest {

    @Test
    fun `unified types are seven distinct single-select codes`() {
        val items = DocTypeStore.DEFAULT
        assertEquals(listOf("1", "2", "3", "4", "5", "8", "9"), items.map { it.code })
        assertEquals(items.size, items.map { it.code }.distinct().size)
        assertEquals(
            listOf("民事委托", "调查", "诉讼", "执行", "刑事委托", "刑事法援", "民事法援"),
            items.map { it.label }
        )
        // 默认选中项沿用旧版默认案件类型（民事法援）
        assertEquals("9", DocTypeStore.DEFAULT_CODE)
    }

    @Test
    fun `each type maps to its field structure`() {
        val items = DocTypeStore.DEFAULT
        assertEquals(CaseTypeStore.ID_CIVIL_ENTRUST, DocTypeStore.selectedCaseTypeId(items, "1"))
        assertEquals(CaseTypeStore.ID_CRIMINAL_ENTRUST, DocTypeStore.selectedCaseTypeId(items, "5"))
        assertEquals(CaseTypeStore.ID_CRIMINAL_AID, DocTypeStore.selectedCaseTypeId(items, "8"))
        assertEquals(CaseTypeStore.ID_CIVIL_AID, DocTypeStore.selectedCaseTypeId(items, "9"))
        // 调查 / 诉讼 / 执行没有专属结构，切换时保留当前表单
        for (code in listOf("2", "3", "4")) {
            assertTrue(DocTypeStore.selectedCaseTypeId(items, code).isEmpty())
        }
        assertTrue(DocTypeStore.selectedCaseTypeId(items, "99").isEmpty())
    }

    @Test
    fun `legacy code mapping keeps upgraded custom lists usable`() {
        assertEquals(CaseTypeStore.ID_CIVIL_ENTRUST, DocTypeStore.caseTypeIdFor("1"))
        assertEquals(CaseTypeStore.ID_CRIMINAL_ENTRUST, DocTypeStore.caseTypeIdFor("5"))
        assertEquals(CaseTypeStore.ID_CRIMINAL_AID, DocTypeStore.caseTypeIdFor("8"))
        assertEquals(CaseTypeStore.ID_CIVIL_AID, DocTypeStore.caseTypeIdFor("9"))
        assertTrue(DocTypeStore.caseTypeIdFor("3").isEmpty())
    }
}
