package com.fitnessapp.summary.sync

import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * Pure rules of the cloud mirror and its inbox - everything [CloudMirror] and
 * [InboxApplier] decide that does not need a device, kept here so a JVM test reaches it.
 *
 * Why a mirror at all, next to [FirestoreSyncManager]: that one syncs two tables between
 * phones, row per document. The mirror exists so the whole database can be read off the
 * phone (by the owner's own scripts on a PC) and written back through a reviewed queue.
 * Row-per-document would not fit the free Firestore quota - tens of thousands of rows
 * against 20k writes a day - so rows are packed a month per document, and only the
 * months whose content hash changed are rewritten.
 */
object MirrorLayout {

    /**
     * Not mirrored and never writable from the inbox. Room's and SQLite's own bookkeeping,
     * plus `garmin_sync_marks`: tens of thousands of "this day was asked" flags that mean
     * nothing off the phone, and a write into them could silently stop the Garmin sync
     * from ever asking about a day again.
     */
    val EXCLUDED_TABLES = setOf(
        "room_master_table",
        "android_metadata",
        "sqlite_sequence",
        "garmin_sync_marks",
    )

    /**
     * Upper bound for one document's `rowsJson`, in UTF-8 bytes. Firestore's hard limit is
     * 1 MiB for the whole document; the gap leaves room for the other fields and for the
     * index overhead Firestore counts against the same limit.
     */
    const val MAX_PART_BYTES = 900_000

    /** Month bucket for tables with no date column (one row per sport profile, say). */
    const val UNDATED = "all"

    /** Millisecond columns tried, in order, when a row has no `dateEpochDay`. */
    private val MILLIS_COLUMNS = listOf("startTimeMillis", "startMillis", "timestampMillis")

    fun isMirrored(table: String): Boolean =
        table !in EXCLUDED_TABLES && !table.startsWith("sqlite_") && !table.startsWith("room_")

    /**
     * `YYYY-MM` the row belongs to. Day columns first, because they are the app's own idea
     * of the local day; milliseconds are bucketed in UTC so a row never moves between
     * documents just because the phone changed time zone.
     */
    fun monthKey(row: Map<String, Any?>): String {
        (row["dateEpochDay"] as? Number)?.let { return LocalDate.ofEpochDay(it.toLong()).toMonthKey() }
        for (column in MILLIS_COLUMNS) {
            val millis = (row[column] as? Number)?.toLong() ?: continue
            if (millis > 0) return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toMonthKey()
        }
        return UNDATED
    }

    private fun LocalDate.toMonthKey() = "%04d-%02d".format(year, monthValue)

    /** One row as a JSON object, columns in table order - stable text, so a stable hash. */
    fun rowJson(columns: List<String>, values: List<Any?>): String {
        require(columns.size == values.size) { "columns ${columns.size} != values ${values.size}" }
        val sb = StringBuilder("{")
        columns.forEachIndexed { i, name ->
            if (i > 0) sb.append(',')
            sb.append(JSONObject.quote(name)).append(':').append(jsonValue(values[i]))
        }
        return sb.append('}').toString()
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Long, is Int, is Short, is Byte -> value.toString()
        // NaN and infinities are not JSON; SQLite can hold them, the reader could not parse them.
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Float -> if (value.isFinite()) value.toString() else "null"
        is String -> JSONObject.quote(value)
        is ByteArray -> JSONObject.quote(Base64.getEncoder().encodeToString(value))
        else -> JSONObject.quote(value.toString())
    }

    /**
     * Rows of one month split into parts that each fit [maxBytes] as a JSON array. Rows are
     * sorted first: SQLite's scan order follows rowid, and REPLACE gives a row a new rowid
     * without changing it, which would change the hash and rewrite an unchanged month.
     */
    fun splitIntoParts(rows: List<String>, maxBytes: Int = MAX_PART_BYTES): List<List<String>> {
        val parts = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 2 // the brackets
        for (row in rows.sorted()) {
            val rowBytes = row.toByteArray(Charsets.UTF_8).size + 1 // and its comma
            if (current.isNotEmpty() && size + rowBytes > maxBytes) {
                parts += current
                current = mutableListOf()
                size = 2
            }
            current += row
            size += rowBytes
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    fun arrayJson(rows: List<String>): String = rows.joinToString(",", "[", "]")

    /**
     * Document id. A single part carries no suffix, so the common case reads plainly; when
     * the part count changes the ids change too, and the old ones are deleted by manifest.
     */
    fun docId(table: String, month: String, part: Int, parts: Int): String =
        if (parts <= 1) "${table}__$month" else "${table}__${month}__p${part + 1}"

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- inbox -------------------------------------------------------------------------

    /** One column as `PRAGMA table_info` describes it. */
    data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val hasDefault: Boolean,
        /** 1-based position in the primary key, 0 if not part of it. */
        val pkPosition: Int,
    )

    sealed interface InboxOp {
        val table: String

        /** Whole row, INSERT OR REPLACE - columns left out fall back to their defaults. */
        data class Upsert(override val table: String, val values: Map<String, Any?>) : InboxOp

        /** UPDATE of the given columns only, the row found by its primary key. */
        data class Patch(override val table: String, val key: Map<String, Any?>, val values: Map<String, Any?>) : InboxOp

        data class Delete(override val table: String, val key: Map<String, Any?>) : InboxOp
    }

    /**
     * Reads one inbox document's fields. Rows arrive as JSON text (`rowJson`, `keyJson`)
     * rather than Firestore maps, so their numbers mean the same thing as in the mirror the
     * writer read them from - a Firestore map would turn every whole double into an integer.
     */
    fun parseOp(op: String?, table: String?, rowJson: String?, keyJson: String?): Result<InboxOp> = runCatching {
        require(!table.isNullOrBlank()) { "нет поля table" }
        when (op) {
            "upsert" -> InboxOp.Upsert(table, parseObject(rowJson, "rowJson"))
            "patch" -> InboxOp.Patch(table, parseObject(keyJson, "keyJson"), parseObject(rowJson, "rowJson"))
            "delete" -> InboxOp.Delete(table, parseObject(keyJson, "keyJson"))
            else -> throw IllegalArgumentException("неизвестная операция: $op")
        }
    }

    private fun parseObject(text: String?, field: String): Map<String, Any?> {
        require(!text.isNullOrBlank()) { "нет поля $field" }
        val json = JSONObject(text)
        val out = LinkedHashMap<String, Any?>()
        for (key in json.keys()) out[key] = plainValue(json.get(key), key)
        require(out.isNotEmpty()) { "$field пустой" }
        return out
    }

    /**
     * JSON value to what SQLite stores. Matched on [Number], not on Double: the upstream
     * org.json in tests hands back BigDecimal where Android's hands back Double - the same
     * trap the zone parser fell into.
     */
    private fun plainValue(value: Any?, key: String): Any? = when (value) {
        null, JSONObject.NULL -> null
        is Boolean -> if (value) 1L else 0L // Room stores Boolean as INTEGER
        is BigDecimal -> if (value.stripTrailingZeros().scale() <= 0) value.toLong() else value.toDouble()
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toLong()
        is String -> value
        else -> throw IllegalArgumentException("поле $key: вложенные объекты и массивы не принимаются")
    }

    /**
     * Null if [op] may be applied to a table with [columns], otherwise the reason in words.
     * Checked against the live schema rather than a list in code, so a column added by a
     * future migration is writable without touching this file.
     */
    fun validate(op: InboxOp, columns: List<Column>): String? {
        if (!isMirrored(op.table)) return "таблица ${op.table} закрыта для записи"
        if (columns.isEmpty()) return "таблицы ${op.table} нет"
        val names = columns.map { it.name }.toSet()
        val pk = columns.filter { it.pkPosition > 0 }.map { it.name }.toSet()

        fun unknown(fields: Map<String, Any?>) = (fields.keys - names).takeIf { it.isNotEmpty() }
            ?.let { "нет столбцов: ${it.joinToString()}" }

        fun fullKey(key: Map<String, Any?>): String? {
            if (key.keys != pk) return "ключ должен состоять ровно из: ${pk.joinToString()}"
            if (key.values.any { it == null }) return "в ключе есть null"
            return null
        }

        return when (op) {
            is InboxOp.Upsert -> unknown(op.values)
                ?: (pk - op.values.keys).takeIf { it.isNotEmpty() }?.let { "нет ключевых столбцов: ${it.joinToString()}" }
                ?: columns.filter { it.notNull && !it.hasDefault && it.name !in op.values }
                    .takeIf { it.isNotEmpty() }?.let { c -> "нет обязательных столбцов: ${c.joinToString { it.name }}" }
            is InboxOp.Patch -> fullKey(op.key)
                ?: unknown(op.values)
                ?: (op.values.keys intersect pk).takeIf { it.isNotEmpty() }?.let { "ключ не меняется через patch: ${it.joinToString()}" }
            is InboxOp.Delete -> fullKey(op.key)
        }
    }
}
