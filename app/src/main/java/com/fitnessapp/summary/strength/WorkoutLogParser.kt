package com.fitnessapp.summary.strength

import com.fitnessapp.summary.analytics.StrengthLift
import com.fitnessapp.summary.data.StrengthSet
import com.fitnessapp.summary.debug.AppLog
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads the plain-text training log the user exports from their gym app.
 *
 * The format, taken from a real 13 000-line export covering 520 sessions since 2021:
 *
 * ```
 * 08.09.2026, 09:21                       <- session: date, time
 * День #3 День ног. Программа ...         <- programme name, ignored
 * 00:21:35 • 4.16 т • 1 / 7 / 52          <- session totals, ignored
 *
 * 1. Приседания со штангой                <- exercise
 * 02:00/03:00/02:00                       <- rest timers, ignored
 * 4.16 т • 7 / 52 • 03:34                 <- exercise totals, ignored
 *    1. 20кг • 12x                        <- sets, indented
 *    2. 60кг • 12x
 *
 * 2. Становая тяга сумо
 * 02:00/03:00/02:00
 * 0 / 0                                   <- planned but not performed
 * ```
 *
 * Three details decide whether a naive parser works or quietly produces garbage:
 *
 * - **The file is Windows-1251**, not UTF-8 - it comes off a Russian Android app. Read as
 *   UTF-8 it either throws or turns every exercise name into mojibake, which would then be
 *   matched against Cyrillic keywords and match nothing. [decode] tries UTF-8 *strictly*
 *   first and falls back, rather than trusting either.
 * - **The exercise line and the exercise-totals line both start with digits and a dot**:
 *   `1. Приседания` versus `4.16 т • 7 / 52`. The separator is the space - `\d+\.` followed
 *   by whitespace is an exercise, `4.16` is a tonnage. Without that space the parser
 *   invents an exercise called ".16 т • 7 / 52" on every single session.
 * - **`0 / 0` means the exercise was planned and skipped.** It carries no sets, so it needs
 *   no special case - but it does mean an exercise appearing in the log is not evidence it
 *   was performed, and counts must come from sets.
 *
 * Everything is stored, including exercises that are not tracked lifts; [StrengthLift.match]
 * decides which ones the strength screen draws. Set numbering is taken from the log rather
 * than counted, so the primary key stays stable if the file is imported twice.
 */
object WorkoutLogParser {

    sealed class Result {
        data class Ok(
            val sets: List<StrengthSet>,
            val sessions: Int,
            /** Lines that looked like sets but could not be read - 0 on a healthy file. */
            val skippedLines: Int
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    fun parse(input: InputStream, zone: ZoneId = ZoneId.systemDefault()): Result {
        val text = try {
            decode(input.readBytes())
        } catch (e: Exception) {
            AppLog.w("WorkoutLogParser", "Файл не прочитался", e)
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }

        val now = System.currentTimeMillis()
        val out = ArrayList<StrengthSet>()
        val sessionStarts = HashSet<Long>()
        var startMillis: Long? = null
        var dateEpochDay = 0L
        var exercise: String? = null
        var lift = ""
        var skipped = 0

        for (line in text.lineSequence()) {
            val trimmed = line.trim()

            val session = SESSION_HEADER.matchEntire(trimmed)
            if (session != null) {
                val stamp = runCatching {
                    LocalDateTime.parse(session.groupValues[1].trim(), SESSION_FORMAT).atZone(zone)
                }.getOrNull()
                if (stamp != null) {
                    val millis = stamp.toInstant().toEpochMilli()
                    startMillis = millis
                    dateEpochDay = stamp.toLocalDate().toEpochDay()
                    sessionStarts += millis
                    exercise = null
                }
                continue
            }
            val currentStart = startMillis ?: continue

            // An exercise heading is never indented; a set always is.
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                val heading = EXERCISE_HEADER.matchEntire(trimmed)
                if (heading != null) {
                    val name = heading.groupValues[2].trim()
                    exercise = name
                    lift = StrengthLift.match(name)?.key.orEmpty()
                }
                continue
            }

            val name = exercise ?: continue
            val match = SET_LINE.matchEntire(trimmed) ?: continue
            val index = match.groupValues[1].toIntOrNull()
            val weight = match.groupValues[2].replace(',', '.').toFloatOrNull()
            val reps = match.groupValues[3].toIntOrNull()
            if (index == null || weight == null || reps == null || reps <= 0) {
                skipped++
                continue
            }
            out += StrengthSet(
                startMillis = currentStart,
                dateEpochDay = dateEpochDay,
                exerciseName = name,
                lift = lift,
                setIndex = index,
                weightKg = weight,
                reps = reps,
                updatedAtMillis = now
            )
        }

        if (out.isEmpty()) {
            return Result.Failed(
                if (sessionStarts.isEmpty()) "не похоже на выгрузку тренировок — в файле нет ни одной даты тренировки"
                else "в файле есть тренировки, но ни одного подхода с весом и повторами"
            )
        }
        // Same (session, exercise, set) twice in one file would collide on the primary key;
        // the later line wins, as it would in the database anyway.
        val unique = out.associateBy { Triple(it.startMillis, it.exerciseName, it.setIndex) }.values.toList()
        AppLog.i(
            "WorkoutLogParser",
            "Разобрано тренировок ${sessionStarts.size}, подходов ${unique.size}, пропущено строк $skipped"
        )
        return Result.Ok(unique.sortedWith(compareBy({ it.startMillis }, { it.setIndex })), sessionStarts.size, skipped)
    }

    /**
     * UTF-8 if the bytes really are UTF-8, Windows-1251 otherwise. Decided by decoding
     * strictly and catching the failure, not by sniffing bytes: a Cyrillic cp1251 file is
     * almost always invalid UTF-8, and a valid UTF-8 file is never misread this way.
     */
    private fun decode(bytes: ByteArray): String {
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: Exception) {
            AppLog.d("WorkoutLogParser", "Файл не в UTF-8, читаю как windows-1251")
            String(bytes, WINDOWS_1251)
        }
    }

    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    /** "08.09.2026, 09:21" */
    private val SESSION_HEADER = Regex("""^(\d{2}\.\d{2}\.\d{4},\s*\d{2}:\d{2})$""")
    private val SESSION_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

    /** "1. Приседания со штангой" - the space after the dot is what separates it from "4.16 т". */
    private val EXERCISE_HEADER = Regex("""^(\d{1,3})\.\s+(\S.*)$""")

    /**
     * "1. 20кг • 12x" - tolerant about the bits that vary between versions and locales:
     * the unit may be absent, the separator may be any bullet or dash, and the rep marker
     * may be a Latin x, a Cyrillic х, or nothing at all.
     */
    private val SET_LINE = Regex("""^(\d{1,3})\.\s*([\d.,]+)\s*(?:кг|kg)?\s*[•·*\-–—]?\s*(\d{1,3})\s*[xхX]?\s*$""")
}
