package com.fitnessapp.summary.strength

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.fitnessapp.summary.debug.AppLog
import java.io.File
import java.io.InputStream
import java.time.ZoneId

/**
 * Reads a gym-app database backup off disk and hands its rows to [GymUpBackupParser].
 *
 * Everything Android-specific lives here and nothing else does: opening a foreign SQLite
 * file, the four queries, and the fact that `SQLiteDatabase` needs a real path and cannot
 * be pointed at a `content://` stream. The meaning of the rows - which sets count, how they
 * are numbered, what an unnamed exercise is called - is in the parser, where tests reach it.
 *
 * The file is opened **read-only** and from a copy in the cache. Both matter: it is the
 * user's backup of another app's data, and this app has no business being able to write to
 * it even by accident.
 */
object GymUpBackupReader {

    /** `SQLite format 3` followed by a NUL - the 16 bytes every SQLite file starts with. */
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Whether [head] (the first bytes of a file) is a SQLite database.
     *
     * This is what lets one "Import" button take both a database backup and the old text
     * export: the format is read off the file itself rather than off the file name, which
     * on Android arrives through a `content://` URI and is frequently not a name at all.
     */
    fun looksLikeSqlite(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    fun readMagic(input: InputStream): ByteArray {
        val head = ByteArray(MAGIC.size)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        return head.copyOf(read)
    }

    /**
     * Parses the backup at [file]. The file is not modified and not kept.
     */
    fun read(file: File, zone: ZoneId = ZoneId.systemDefault()): GymUpBackupParser.Result {
        val db = try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (e: SQLiteException) {
            AppLog.w("GymUpBackupReader", "Бэкап не открылся как база", e)
            return GymUpBackupParser.Result.Failed("файл не открылся как база тренировок")
        }

        return db.use {
            try {
                val trainings = it.rawQuery("SELECT _id, startDateTime FROM training", null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(GymUpBackupParser.TrainingRow(c.getLong(0), c.getLong(1)))
                        }
                    }
                }
                val workouts = it.rawQuery(
                    "SELECT _id, training_id, th_exercise_id, order_num FROM workout",
                    null
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                GymUpBackupParser.WorkoutRow(
                                    id = c.getLong(0),
                                    trainingId = c.getLong(1),
                                    exerciseId = c.getLong(2),
                                    orderNum = c.getLong(3)
                                )
                            )
                        }
                    }
                }
                val sets = it.rawQuery("SELECT _id, workout_id, weight, reps FROM set_", null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                GymUpBackupParser.SetRow(
                                    id = c.getLong(0),
                                    workoutId = c.getLong(1),
                                    weightKg = c.getFloat(2),
                                    // Stored REAL: the app allows fractional reps in principle
                                    // and writes 12.0, so read as a number and round once here
                                    // rather than letting getInt() truncate silently.
                                    reps = Math.round(c.getFloat(3))
                                )
                            )
                        }
                    }
                }
                // Only user-added exercises carry a name; stock ones are NULL and are named
                // by GymUpExerciseCatalog. Read anyway - the NULLs are the evidence for that.
                val names = it.rawQuery("SELECT _id, name FROM th_exercise", null).use { c ->
                    buildMap<Long, String?> {
                        while (c.moveToNext()) put(c.getLong(0), if (c.isNull(1)) null else c.getString(1))
                    }
                }

                AppLog.i(
                    "GymUpBackupReader",
                    "Бэкап прочитан: тренировок ${trainings.size}, упражнений в них ${workouts.size}, " +
                        "подходов ${sets.size}, каталог ${names.size} (с именем ${names.count { e -> !e.value.isNullOrBlank() }})"
                )
                GymUpBackupParser.build(trainings, workouts, sets, names, zone)
            } catch (e: SQLiteException) {
                // A database that opens but has no `training` table is not this app's backup.
                AppLog.w("GymUpBackupReader", "В базе нет ожидаемых таблиц", e)
                GymUpBackupParser.Result.Failed("это база, но не бэкап дневника тренировок")
            }
        }
    }
}
