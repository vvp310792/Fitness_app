package com.fitnessapp.summary.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror is read by scripts on another machine, and the inbox writes into the only copy
 * of years of history - both sides of the contract are pinned here, on the real org.json
 * (which hands back BigDecimal, the trap the zone parser already fell into).
 */
class MirrorLayoutTest {

    private fun col(name: String, pk: Int = 0, notNull: Boolean = false, hasDefault: Boolean = false) =
        MirrorLayout.Column(name, "INTEGER", notNull, hasDefault, pk)

    private val dayTable = listOf(
        col("dateEpochDay", pk = 1, notNull = true),
        col("steps", notNull = true),
        col("note"),
    )

    // ---- monthKey ----------------------------------------------------------------------

    @Test
    fun `day column wins and gives local month`() {
        // 2026-09-01 is epoch day 20697.
        assertEquals("2026-09", MirrorLayout.monthKey(mapOf("dateEpochDay" to 20697L, "startTimeMillis" to 0L)))
    }

    @Test
    fun `millis are bucketed in UTC`() {
        // 2026-08-31T23:30:00Z - already September in Novosibirsk, still August in UTC.
        assertEquals("2026-08", MirrorLayout.monthKey(mapOf("startTimeMillis" to 1_788_219_000_000L)))
    }

    @Test
    fun `row without a date goes to the undated bucket`() {
        assertEquals(MirrorLayout.UNDATED, MirrorLayout.monthKey(mapOf("sport" to "RUNNING", "startMillis" to 0L)))
    }

    // ---- rowJson -----------------------------------------------------------------------

    @Test
    fun `rowJson keeps column order and escapes text`() {
        val json = MirrorLayout.rowJson(listOf("b", "a", "t"), listOf(2L, null, "он сказал \"да\""))
        assertEquals("{\"b\":2,\"a\":null,\"t\":\"он сказал \\\"да\\\"\"}", json)
    }

    @Test
    fun `non finite double becomes null`() {
        assertEquals("{\"x\":null,\"y\":1.5}", MirrorLayout.rowJson(listOf("x", "y"), listOf(Double.NaN, 1.5)))
    }

    @Test
    fun `blob becomes base64 string`() {
        assertEquals("{\"b\":\"AQID\"}", MirrorLayout.rowJson(listOf("b"), listOf(byteArrayOf(1, 2, 3))))
    }

    // ---- parts and ids -----------------------------------------------------------------

    @Test
    fun `parts are sorted so rowid order does not change the hash`() {
        val a = MirrorLayout.splitIntoParts(listOf("{\"k\":2}", "{\"k\":1}"))
        val b = MirrorLayout.splitIntoParts(listOf("{\"k\":1}", "{\"k\":2}"))
        assertEquals(a, b)
        assertEquals(1, a.size)
    }

    @Test
    fun `month is split when it does not fit`() {
        val rows = (1..10).map { "{\"k\":$it}" } // 7-8 bytes each
        val parts = MirrorLayout.splitIntoParts(rows, maxBytes = 30)
        assertTrue(parts.size > 1)
        assertEquals(rows.sorted(), parts.flatten())
        for (p in parts) assertTrue(MirrorLayout.arrayJson(p).toByteArray().size <= 30)
    }

    @Test
    fun `single part has no suffix`() {
        assertEquals("garmin_sleep__2026-09", MirrorLayout.docId("garmin_sleep", "2026-09", 0, 1))
        assertEquals("garmin_sleep__2026-09__p2", MirrorLayout.docId("garmin_sleep", "2026-09", 1, 3))
    }

    @Test
    fun `sha256 is lowercase hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            MirrorLayout.sha256("abc"),
        )
    }

    // ---- parseOp -----------------------------------------------------------------------

    @Test
    fun `whole numbers become Long and fractions Double`() {
        val op = MirrorLayout.parseOp("upsert", "t", "{\"a\":93.0,\"b\":93.5,\"c\":7,\"d\":true}", null).getOrThrow()
        val values = (op as MirrorLayout.InboxOp.Upsert).values
        assertEquals(93L, values["a"])
        assertEquals(93.5, values["b"])
        assertEquals(7L, values["c"])
        assertEquals(1L, values["d"])
    }

    @Test
    fun `json null is kept as null`() {
        val op = MirrorLayout.parseOp("upsert", "t", "{\"a\":null,\"b\":1}", null).getOrThrow()
        val values = (op as MirrorLayout.InboxOp.Upsert).values
        assertTrue(values.containsKey("a"))
        assertNull(values["a"])
    }

    @Test
    fun `nested object is refused`() {
        val r = MirrorLayout.parseOp("upsert", "t", "{\"a\":{\"x\":1}}", null)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("вложенные"))
    }

    @Test
    fun `unknown op and missing table are refused`() {
        assertTrue(MirrorLayout.parseOp("drop", "t", "{\"a\":1}", null).exceptionOrNull()!!.message!!.contains("неизвестная операция"))
        assertTrue(MirrorLayout.parseOp("upsert", " ", "{\"a\":1}", null).exceptionOrNull()!!.message!!.contains("table"))
        assertTrue(MirrorLayout.parseOp("delete", "t", null, "{}").isFailure)
    }

    @Test
    fun `patch carries key and values separately`() {
        val op = MirrorLayout.parseOp("patch", "t", "{\"steps\":5}", "{\"dateEpochDay\":20697}").getOrThrow()
        op as MirrorLayout.InboxOp.Patch
        assertEquals(mapOf("dateEpochDay" to 20697L), op.key)
        assertEquals(mapOf("steps" to 5L), op.values)
    }

    // ---- validate ----------------------------------------------------------------------

    @Test
    fun `excluded table is closed for writes`() {
        val op = MirrorLayout.InboxOp.Delete("garmin_sync_marks", mapOf("x" to 1L))
        assertTrue(MirrorLayout.validate(op, dayTable)!!.contains("закрыта"))
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Delete("room_master_table", mapOf("x" to 1L)), dayTable)!!.contains("закрыта"))
    }

    @Test
    fun `missing table is named`() {
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Delete("nope", mapOf("x" to 1L)), emptyList())!!.contains("нет"))
    }

    @Test
    fun `good upsert passes and nullable column may be left out`() {
        assertNull(MirrorLayout.validate(MirrorLayout.InboxOp.Upsert("d", mapOf("dateEpochDay" to 1L, "steps" to 5L)), dayTable))
    }

    @Test
    fun `upsert with unknown column, without key or without required column is refused`() {
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Upsert("d", mapOf("dateEpochDay" to 1L, "steps" to 5L, "zz" to 1L)), dayTable)!!.contains("zz"))
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Upsert("d", mapOf("steps" to 5L)), dayTable)!!.contains("ключевых"))
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Upsert("d", mapOf("dateEpochDay" to 1L)), dayTable)!!.contains("steps"))
    }

    @Test
    fun `required column with default may be left out`() {
        val table = listOf(col("id", pk = 1, notNull = true), col("flag", notNull = true, hasDefault = true))
        assertNull(MirrorLayout.validate(MirrorLayout.InboxOp.Upsert("d", mapOf("id" to 1L)), table))
    }

    @Test
    fun `patch and delete need exactly the full key without nulls`() {
        val composite = listOf(col("day", pk = 1), col("section", pk = 2), col("v"))
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Delete("c", mapOf("day" to 1L)), composite)!!.contains("ровно"))
        assertTrue(MirrorLayout.validate(MirrorLayout.InboxOp.Delete("c", mapOf("day" to 1L, "section" to null)), composite)!!.contains("null"))
        assertNull(MirrorLayout.validate(MirrorLayout.InboxOp.Delete("c", mapOf("day" to 1L, "section" to "s")), composite))
    }

    @Test
    fun `patch may not change the key`() {
        val op = MirrorLayout.InboxOp.Patch("d", mapOf("dateEpochDay" to 1L), mapOf("dateEpochDay" to 2L))
        assertNotNull(MirrorLayout.validate(op, dayTable))
        assertTrue(MirrorLayout.validate(op, dayTable)!!.contains("не меняется"))
    }
}
