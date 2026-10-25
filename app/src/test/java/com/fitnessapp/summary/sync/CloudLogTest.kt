package com.fitnessapp.summary.sync

import com.fitnessapp.summary.debug.AppLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC reads these documents with a script that never saw the phone - the line format and
 * the batch id are the contract, pinned on the real org.json.
 */
class CloudLogTest {

    @Test
    fun `outbox line survives quotes, newlines and missing detail`() {
        val entry = AppLog.Entry(1_788_219_000_000L, AppLog.Level.ERROR, "Garmin", "ответ \"401\"\nвторая строка")
        val json = JSONObject(AppLog.outboxLine(entry))
        assertEquals(1_788_219_000_000L, json.getLong("t"))
        assertEquals("ERROR", json.getString("level"))
        assertEquals("ответ \"401\"\nвторая строка", json.getString("msg"))
        assertTrue(json.isNull("detail"))
        assertTrue(json.isNull("stack"))
        assertTrue("one line per entry", '\n' !in AppLog.outboxLine(entry))
    }

    @Test
    fun `batch takes oldest lines that fit`() {
        val lines = listOf("a".repeat(10), "b".repeat(10), "c".repeat(10))
        assertEquals(lines.take(2), CloudLog.takeBatch(lines, maxBytes = 25))
        assertEquals(lines, CloudLog.takeBatch(lines, maxBytes = 1_000))
    }

    @Test
    fun `oversized line still goes alone`() {
        val lines = listOf("x".repeat(100), "y")
        assertEquals(lines.take(1), CloudLog.takeBatch(lines, maxBytes = 10))
    }

    @Test
    fun `id sorts by time and repeats for the same batch`() {
        val batch = listOf("{\"t\":1}")
        val id = CloudLog.docId(1_788_219_000_123L, "ab12cd", batch)
        assertTrue(id, id.startsWith("20260831T233000123Z-ab12cd-"))
        assertEquals(id, CloudLog.docId(1_788_219_000_123L, "ab12cd", batch))
        assertNotEquals(id, CloudLog.docId(1_788_219_000_123L, "ab12cd", batch + "{\"t\":2}"))
    }
}
