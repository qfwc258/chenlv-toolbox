package com.wb.mdgw

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护 UndoHistoryStore 的核心不变量：
 *  - Markdown / 公文撤销栈都能正确序列化往返；
 *  - 空栈与含 null 元素的栈也能往返；
 *  - 大栈也能正常编码。
 *
 * 这里只测数据模型层，不涉及 Context 与磁盘 I/O（真实落盘由 JsonFileStore
 * 在 Android 端验证，属于集成层）。
 */
class UndoHistoryStoreTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // ============================================================
    // Markdown 撤销栈
    // ============================================================

    @Test
    fun `MdUndoHistory 空栈可序列化`() {
        val h = UndoHistoryStore.MdUndoHistory()
        val back = json.decodeFromString<UndoHistoryStore.MdUndoHistory>(json.encodeToString(UndoHistoryStore.MdUndoHistory.serializer(), h))
        assertTrue(back.undo.isEmpty())
        assertTrue(back.redo.isEmpty())
    }

    @Test
    fun `MdUndoHistory 多步快照可序列化往返且字段保真`() {
        val h = UndoHistoryStore.MdUndoHistory(
            undo = listOf(
                UndoHistoryStore.MdUndoSnapshot("第一段", 0, 3),
                UndoHistoryStore.MdUndoSnapshot("第二段更长", 4, 8),
                UndoHistoryStore.MdUndoSnapshot("第三段含换行\n第二行", 0, 14)
            ),
            redo = listOf(
                UndoHistoryStore.MdUndoSnapshot("被撤销的状态", 0, 0)
            )
        )
        val back = json.decodeFromString<UndoHistoryStore.MdUndoHistory>(json.encodeToString(UndoHistoryStore.MdUndoHistory.serializer(), h))
        assertEquals(3, back.undo.size)
        assertEquals("第一段", back.undo[0].text)
        assertEquals(0, back.undo[0].selStart)
        assertEquals(3, back.undo[0].selEnd)
        assertEquals("第三段含换行\n第二行", back.undo[2].text)
        assertEquals(14, back.undo[2].selEnd)
        assertEquals(1, back.redo.size)
        assertEquals("被撤销的状态", back.redo[0].text)
    }

    @Test
    fun `MdUndoHistory 含中文长内容可往返`() {
        val long = "陈律工具箱".repeat(200)
        val h = UndoHistoryStore.MdUndoHistory(
            undo = listOf(UndoHistoryStore.MdUndoSnapshot(long, long.length / 2, long.length))
        )
        val back = json.decodeFromString<UndoHistoryStore.MdUndoHistory>(json.encodeToString(UndoHistoryStore.MdUndoHistory.serializer(), h))
        assertEquals(long, back.undo[0].text)
        assertEquals(long.length, back.undo[0].selEnd)
    }

    // ============================================================
    // 公文撤销栈
    // ============================================================

    @Test
    fun `GovUndoHistory 空栈可序列化`() {
        val h = UndoHistoryStore.GovUndoHistory()
        val back = json.decodeFromString<UndoHistoryStore.GovUndoHistory>(json.encodeToString(UndoHistoryStore.GovUndoHistory.serializer(), h))
        assertTrue(back.undo.isEmpty())
        assertTrue(back.redo.isEmpty())
    }

    @Test
    fun `GovUndoHistory 含 null 元素的栈可序列化往返`() {
        // null 是合法元素：用户关闭文档时 commitGov(null) 会把 null 推入 undo 栈
        val h = UndoHistoryStore.GovUndoHistory(
            undo = listOf<GovDoc?>(null, makeGovDoc("第一版"), null, makeGovDoc("第三版")),
            redo = listOf<GovDoc?>(makeGovDoc("回退目标"), null)
        )
        val back = json.decodeFromString<UndoHistoryStore.GovUndoHistory>(json.encodeToString(UndoHistoryStore.GovUndoHistory.serializer(), h))
        assertEquals(4, back.undo.size)
        assertNull(back.undo[0])
        assertNotNull(back.undo[1])
        assertEquals("第一版", back.undo[1]?.title)
        assertNull(back.undo[2])
        assertEquals("第三版", back.undo[3]?.title)
        assertEquals(2, back.redo.size)
        assertEquals("回退目标", back.redo[0]?.title)
        assertNull(back.redo[1])
    }

    @Test
    fun `GovUndoHistory 大量快照也能往返`() {
        val undo = (1..60).map { makeGovDoc("版本 $it") }
        val redo = (1..20).map { makeGovDoc("回退 $it") }
        val h = UndoHistoryStore.GovUndoHistory(undo = undo, redo = redo)
        val back = json.decodeFromString<UndoHistoryStore.GovUndoHistory>(json.encodeToString(UndoHistoryStore.GovUndoHistory.serializer(), h))
        assertEquals(60, back.undo.size)
        assertEquals("版本 1", back.undo.first()?.title)
        assertEquals("版本 60", back.undo.last()?.title)
        assertEquals(20, back.redo.size)
        assertEquals("回退 20", back.redo.last()?.title)
    }

    @Test
    fun `GovUndoHistory 中 originalDocx 不参与持久化`() {
        val docWithBytes = makeGovDoc("带源字节").copy(originalDocx = byteArrayOf(1, 2, 3, 4, 5))
        val h = UndoHistoryStore.GovUndoHistory(undo = listOf(docWithBytes))
        val back = json.decodeFromString<UndoHistoryStore.GovUndoHistory>(json.encodeToString(UndoHistoryStore.GovUndoHistory.serializer(), h))
        assertNull(back.undo[0]?.originalDocx)
        assertEquals("带源字节", back.undo[0]?.title)
    }

    // ============================================================
    // 辅助
    // ============================================================

    private fun makeGovDoc(title: String): GovDoc = GovDoc(
        blocks = listOf(
            Block.Para(
                runs = listOf(TextRun(title, "仿宋", 16.0)),
                props = ParaProps(align = Align.LEFT)
            )
        ),
        page = PageSetup(),
        title = title,
        mainTitleFont = "方正小标宋简体",
        bodyFont = "仿宋_GB2312",
        bodySizePt = 16.0,
        lineSpacingPt = 28.0,
        indentPt = 32.0
    )
}
