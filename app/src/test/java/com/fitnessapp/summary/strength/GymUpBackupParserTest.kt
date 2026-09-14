package com.fitnessapp.summary.strength

import com.fitnessapp.summary.analytics.StrengthLift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The backup format's traps, each one taken from the user's real files rather than invented:
 * stock exercises carry no name, a session can hold no performed set at all, two sessions
 * can share a start instant, and one exercise can appear twice in one session.
 */
class GymUpBackupParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Novosibirsk")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun build(
        trainings: List<GymUpBackupParser.TrainingRow>,
        workouts: List<GymUpBackupParser.WorkoutRow>,
        sets: List<GymUpBackupParser.SetRow>,
        names: Map<Long, String?> = emptyMap()
    ) = GymUpBackupParser.build(trainings, workouts, sets, names, zone)

    private fun ok(result: GymUpBackupParser.Result): GymUpBackupParser.Result.Ok {
        assertTrue("ожидался успешный разбор, а пришло $result", result is GymUpBackupParser.Result.Ok)
        return result as GymUpBackupParser.Result.Ok
    }

    @Test
    fun `stock exercise is named from the catalogue, not from the backup`() {
        val start = at(2026, 9, 14, 9, 26)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                // 220 is squats; the backup's own name column is NULL, as it is for all 572
                // stock rows, which is precisely why the catalogue exists.
                workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 220, 100)),
                sets = listOf(
                    GymUpBackupParser.SetRow(1, 10, 20f, 12),
                    GymUpBackupParser.SetRow(2, 10, 120f, 5)
                ),
                names = mapOf(220L to null)
            )
        )

        assertEquals(listOf("Приседания со штангой", "Приседания со штангой"), result.sets.map { it.exerciseName })
        assertEquals(listOf(1, 2), result.sets.map { it.setIndex })
        assertEquals(StrengthLift.SQUAT.key, result.sets.first().lift)
        assertEquals(1, result.sessions)
        assertTrue(result.unidentified.isEmpty())
    }

    @Test
    fun `a user-added exercise keeps the name the backup carries`() {
        val start = at(2020, 2, 20, 12, 0)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 1004, 100)),
                sets = listOf(GymUpBackupParser.SetRow(1, 10, 50f, 10)),
                names = mapOf(1004L to "Гребная тяга сидя")
            )
        )

        assertEquals("Гребная тяга сидя", result.sets.single().exerciseName)
        assertTrue(result.unidentified.isEmpty())
    }

    @Test
    fun `an unknown stock exercise is imported under a placeholder and reported`() {
        val start = at(2019, 4, 12, 9, 21)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 490, 100)),
                sets = listOf(
                    GymUpBackupParser.SetRow(1, 10, 30f, 12),
                    GymUpBackupParser.SetRow(2, 10, 60f, 5)
                ),
                names = mapOf(490L to null)
            )
        )

        // Imported, not dropped - the sets happened.
        assertEquals(2, result.sets.size)
        assertEquals("Упражнение #490", result.sets.first().exerciseName)
        // ...and not filed under a guess: no base lift claims it.
        assertNull(StrengthLift.match(result.sets.first().exerciseName))
        val unknown = result.unidentified.single()
        assertEquals(490L, unknown.exerciseId)
        assertEquals(2, unknown.sets)
        assertEquals(1, unknown.sessions)
        assertEquals(60f, unknown.maxWeightKg, 0.001f)
    }

    @Test
    fun `a session where nothing was performed is not a session`() {
        val start = at(2019, 4, 12, 9, 21)
        val result = ok(
            build(
                trainings = listOf(
                    GymUpBackupParser.TrainingRow(1, start),
                    // The real 2019 file holds a second session at the very same instant whose
                    // every exercise is empty - a programme day opened and abandoned. Kept, it
                    // would collide with the first on the (session, exercise, index) key.
                    GymUpBackupParser.TrainingRow(2, start)
                ),
                workouts = listOf(
                    GymUpBackupParser.WorkoutRow(10, 1, 220, 100),
                    GymUpBackupParser.WorkoutRow(20, 2, 220, 100)
                ),
                sets = listOf(GymUpBackupParser.SetRow(1, 10, 100f, 5))
            )
        )

        assertEquals(1, result.sessions)
        assertEquals(1, result.emptySessions)
        assertEquals(1, result.sets.size)
    }

    @Test
    fun `a planned but skipped exercise carries no sets`() {
        val start = at(2026, 9, 1, 9, 52)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(
                    GymUpBackupParser.WorkoutRow(10, 1, 220, 100),
                    GymUpBackupParser.WorkoutRow(11, 1, 47, 200)
                ),
                sets = listOf(
                    GymUpBackupParser.SetRow(1, 10, 100f, 5),
                    // reps = 0 is the backup's "planned, not performed", same fact as the text
                    // export's `0 / 0` line.
                    GymUpBackupParser.SetRow(2, 11, 60f, 0)
                )
            )
        )

        assertEquals(1, result.sets.size)
        assertEquals("Приседания со штангой", result.sets.single().exerciseName)
    }

    @Test
    fun `an exercise repeated in one session keeps numbering running instead of overwriting`() {
        val start = at(2025, 7, 31, 9, 30)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(
                    // Exactly the 2025-07-31 shape: the programme's block, then sets added
                    // later, which the gym app files as a second block with a later order_num.
                    GymUpBackupParser.WorkoutRow(972, 1, 66, 1693140328186),
                    GymUpBackupParser.WorkoutRow(973, 1, 66, 1753930102313)
                ),
                sets = listOf(
                    GymUpBackupParser.SetRow(1, 972, 25f, 8),
                    GymUpBackupParser.SetRow(2, 973, 20f, 12),
                    GymUpBackupParser.SetRow(3, 973, 25f, 8),
                    GymUpBackupParser.SetRow(4, 973, 25f, 8)
                )
            )
        )

        // Numbering each block from 1 would collide on indexes 1..3 and lose a set - which is
        // what the text export does here. All four survive, numbered straight through.
        assertEquals(listOf(1, 2, 3, 4), result.sets.map { it.setIndex })
        assertEquals(listOf(25f, 20f, 25f, 25f), result.sets.map { it.weightKg })
        assertEquals(4, result.sets.map { Triple(it.startMillis, it.exerciseName, it.setIndex) }.distinct().size)
    }

    @Test
    fun `exercises are read in the gym app's own order, not by row id`() {
        val start = at(2026, 9, 14, 9, 26)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(
                    // order_num is a creation timestamp: the lower one is displayed first even
                    // though its row id is higher.
                    GymUpBackupParser.WorkoutRow(99, 1, 220, 100),
                    GymUpBackupParser.WorkoutRow(11, 1, 121, 900)
                ),
                sets = listOf(
                    GymUpBackupParser.SetRow(1, 99, 100f, 5),
                    GymUpBackupParser.SetRow(2, 11, 80f, 8)
                )
            )
        )

        assertEquals(listOf("Приседания со штангой", "Жим штанги лёжа средним хватом"), result.sets.map { it.exerciseName })
    }

    @Test
    fun `the session's calendar day comes from the local zone`() {
        // 00:30 local is the previous day in UTC. The day a session belongs to is the local
        // one, as everywhere else in this app.
        val start = at(2026, 9, 14, 0, 30)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 220, 100)),
                sets = listOf(GymUpBackupParser.SetRow(1, 10, 100f, 5))
            )
        )

        assertEquals(java.time.LocalDate.of(2026, 9, 14).toEpochDay(), result.sets.single().dateEpochDay)
    }

    @Test
    fun `a file with no performed set fails instead of wiping the period`() {
        val start = at(2026, 9, 1, 9, 52)
        val result = build(
            trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
            workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 220, 100)),
            sets = emptyList()
        )

        // Matters because the import deletes the file's day range before writing it: an empty
        // result must never be allowed to describe a range.
        assertTrue(result is GymUpBackupParser.Result.Failed)
    }

    @Test
    fun `an empty file fails`() {
        assertTrue(build(emptyList(), emptyList(), emptyList()) is GymUpBackupParser.Result.Failed)
    }

    @Test
    fun `bodyweight lifts keep the full load the gym app records`() {
        val start = at(2026, 9, 8, 9, 21)
        val result = ok(
            build(
                trainings = listOf(GymUpBackupParser.TrainingRow(1, start)),
                workouts = listOf(GymUpBackupParser.WorkoutRow(10, 1, 542, 100)),
                sets = listOf(GymUpBackupParser.SetRow(1, 10, 90f, 6)),
                names = mapOf(542L to null)
            )
        )

        // 90 kg on a 78 kg person is bodyweight plus 12 on a belt. Nothing is subtracted here:
        // the app does not know the bodyweight of that day and must not invent one.
        assertEquals(90f, result.sets.single().weightKg, 0.001f)
        assertEquals(StrengthLift.PULL_UP.key, result.sets.single().lift)
    }

    @Test
    fun `sqlite files are recognised by their header`() {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        assertTrue(GymUpBackupReader.looksLikeSqlite(header))
        assertTrue(GymUpBackupReader.looksLikeSqlite(header + byteArrayOf(0, 16, 0, 1)))
        // The old text export starts with a date line, and a truncated read is not a database.
        assertTrue(!GymUpBackupReader.looksLikeSqlite("08.09.2026, 09:21".toByteArray()))
        assertTrue(!GymUpBackupReader.looksLikeSqlite("SQLite".toByteArray()))
        // The 16th byte is a NUL, not a space. Written as a literal invisible byte in
        // the source, this very constant once looked right and read wrong.
        assertTrue(!GymUpBackupReader.looksLikeSqlite("SQLite format 3 ".toByteArray()))
        assertTrue(!GymUpBackupReader.looksLikeSqlite(ByteArray(0)))
    }
}
