package com.wb.mdgw.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/** 便签去重回归：崩溃日志中 LazyVerticalStaggeredGrid 的重复 key 来自同 id 副本 */
class NoteModelsTest {

    @Test
    fun `dedupeById keeps newest first occurrence`() {
        val a = StickyNote("a", "A", updatedAt = 1)
        val b = StickyNote("b", "B", updatedAt = 2)
        val duplicated = listOf(a, b, a.copy(title = "旧副本"))
        val deduped = duplicated.dedupeById()
        assertEquals(2, deduped.size)
        assertEquals("A", deduped[0].title)
        assertEquals("B", deduped[1].title)
    }

    @Test
    fun `upsert replaces same id without duplicating and inserts new at top`() {
        val a = StickyNote("a", "A", updatedAt = 1)
        val b = StickyNote("b", "B", updatedAt = 2)
        val list = listOf(a, b)
        val replaced = list.upsert(a.copy(title = "A2", content = "已保存"))
        assertEquals(2, replaced.size)
        assertEquals("A2", replaced[0].title)
        assertEquals("已保存", replaced[0].content)

        val c = StickyNote("c", "C", updatedAt = 3)
        val inserted = replaced.upsert(c)
        assertEquals(3, inserted.size)
        assertEquals("c", inserted[0].id)
    }
}
