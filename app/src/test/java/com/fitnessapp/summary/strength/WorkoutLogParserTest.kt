package com.fitnessapp.summary.strength

import com.fitnessapp.summary.analytics.StrengthAnalytics
import com.fitnessapp.summary.analytics.StrengthLift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.ZoneId

/**
 * The shape of a real gym-log export, with the three things that break a naive parser:
 * the exercise-totals line that also starts with digits and a dot, an exercise planned
 * but not performed (`0 / 0`), and windows-1251 encoding.
 */
class WorkoutLogParserTest {

    private val utc = ZoneId.of("UTC")

    private val log = """
        08.09.2026, 09:21
        День #3 День ног. Программа раздельная на пять раз в неделю
        00:21:35 • 4.16 т • 1 / 7 / 52

        1. Приседания со штангой
        02:00/03:00/02:00
        4.16 т • 7 / 52 • 03:34
           1. 20кг • 12x
           2. 100кг • 8x
           3. 120кг • 5x
           4. 120кг • 5x
           5. 110кг • 6x

        2. Становая тяга сумо
        02:00/03:00/02:00
        0 / 0

        07.09.2026, 09:22
        День #2 Спина 1. Программа раздельная на пять раз в неделю
        00:21:25 • 2.93 т • 1 / 6 / 35

        1. Подтягивания
        02:00/03:00/02:00
        2.93 т • 6 / 35 • 04:16
           1. 78.5кг • 12x
           2. 90кг • 5x
           3. 90кг • 4x
    """.trimIndent()

    private fun parse(text: String = log, charset: Charset = Charsets.UTF_8) =
        WorkoutLogParser.parse(text.toByteArray(charset).inputStream(), utc) as WorkoutLogParser.Result.Ok

    @Test
    fun `reads sessions, exercises and sets`() {
        val result = parse()
        assertEquals(2, result.sessions)
        assertEquals(0, result.skippedLines)
        assertEquals(8, result.sets.size)
        val squat = result.sets.filter { it.lift == StrengthLift.SQUAT.key }
        assertEquals(5, squat.size)
        assertEquals(120f, squat[2].weightKg)
        assertEquals(5, squat[2].reps)
        assertEquals("Приседания со штангой", squat[2].exerciseName)
    }

    @Test
    fun `the exercise totals line is not mistaken for an exercise`() {
        // "4.16 т • 7 / 52 • 03:34" begins with a digit and a dot, exactly like "1. Приседания".
        assertTrue(parse().sets.none { it.exerciseName.startsWith(".") || it.exerciseName.contains("т •") })
    }

    @Test
    fun `an exercise planned but not performed contributes nothing`() {
        assertTrue(parse().sets.none { it.lift == StrengthLift.DEADLIFT.key })
    }

    @Test
    fun `windows-1251 is read as well as UTF-8`() {
        val cp1251 = parse(charset = Charset.forName("windows-1251"))
        assertEquals(parse().sets.map { it.exerciseName }, cp1251.sets.map { it.exerciseName })
        assertTrue(cp1251.sets.any { it.lift == StrengthLift.PULL_UP.key })
    }

    @Test
    fun `a file that is not a workout log fails instead of importing nothing`() {
        val result = WorkoutLogParser.parse("time,weight\n2021-02-05,77.1".byteInputStream(), utc)
        assertTrue(result is WorkoutLogParser.Result.Failed)
    }

    @Test
    fun `lifts are matched by keyword and non-target exercises stay unmapped`() {
        assertEquals(StrengthLift.BENCH, StrengthLift.match("Жим штанги лёжа средним хватом"))
        assertEquals(StrengthLift.OVERHEAD, StrengthLift.match("Армейский жим стоя"))
        assertEquals(StrengthLift.ROW, StrengthLift.match("Тяга штанги к груди в наклоне"))
        assertEquals(StrengthLift.DEADLIFT, StrengthLift.match("Становая тяга сумо"))
        assertEquals(StrengthLift.DEADLIFT, StrengthLift.match("Становая тяга со штангой"))
        assertNull(StrengthLift.match("Жим ногами"))
        assertNull(StrengthLift.match("Тяга на нижнем блоке"))
        assertEquals(StrengthLift.DIPS, StrengthLift.match("Отжимания на брусьях"))
        // Neighbours in the same log that are NOT dips.
        assertNull(StrengthLift.match("Отжимания"))
        assertNull(StrengthLift.match("Отжимания от скамьи из-за спины"))
    }

    @Test
    fun `session metrics follow the documented rules`() {
        val squat = StrengthAnalytics.sessionsOf(parse().sets, StrengthLift.SQUAT).single()
        // Epley on the best set: 120 × 5 -> 140; the 20 × 12 warm-up must not win it.
        assertEquals(140f, squat.oneRmKg, 0.01f)
        // Heaviest weight done twice, not the mode and not the single heaviest.
        assertEquals(120f, squat.workingWeightKg)
        assertEquals(5, squat.workingReps)
        assertEquals(120f, squat.topWeightKg)
        assertEquals(5, squat.setCount)
    }

    @Test
    fun `working weight falls back to the heaviest set when nothing repeats`() {
        val ramp = """
            01.09.2026, 09:00
            Проверка
            1. Приседания со штангой
               1. 60кг • 5x
               2. 80кг • 3x
               3. 100кг • 1x
        """.trimIndent()
        val session = StrengthAnalytics.sessionsOf(parse(ramp).sets, StrengthLift.SQUAT).single()
        assertEquals(100f, session.workingWeightKg)
        assertEquals(100f, session.oneRmKg, 0.01f)
    }

    @Test
    fun `sets past the rep cap do not produce a one-rep-max estimate`() {
        assertEquals(0f, StrengthAnalytics.estimateOneRm(60f, 20), 0.001f)
        assertEquals(60f, StrengthAnalytics.estimateOneRm(60f, 1), 0.001f)
    }
}
