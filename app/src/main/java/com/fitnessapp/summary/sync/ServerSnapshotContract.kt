package com.fitnessapp.summary.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.InflaterInputStream

/** Versioned, content-addressed server snapshot. V0 status is deliberately not activatable. */
data class ServerCatalogStatus(
    val schemaVersion: Long,
    val manifestSetSha256: String,
    val groups: Long,
    val records: Long,
    val publishedSeconds: Long,
    val publishedNanos: Int
)

data class ServerManifest(
    val id: String,
    val source: String,
    val kind: String,
    val month: String,
    val count: Long,
    val parts: List<String>,
    val sha256: String
)

data class ServerPart(
    val id: String,
    val source: String,
    val kind: String,
    val month: String,
    val count: Long,
    val sha256: String,
    val rowsJson: String? = null,
    val rowsZlib: ByteArray? = null
)

data class VerifiedServerRow(val source: String, val kind: String, val nativeId: String, val day: String, val rowJson: String)

object ServerSnapshotContract {
    private val safeName = Regex("[a-z][a-z0-9_-]*")
    private val monthPattern = Regex("[0-9]{4}-(0[1-9]|1[0-2])|undated")
    private val hex = Regex("[0-9a-f]{64}")
    const val SCHEMA_VERSION = 1L
    const val MAX_PART_BYTES = 1_000_000
    const val MAX_GROUPS = 10_000
    const val MAX_ROWS = 1_000_000L

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun digest(text: String): String = digest(text.toByteArray(Charsets.UTF_8))

    /** The publisher uses Python json.dumps(..., separators=(",",":")). Part IDs
     * are restricted to safe ASCII so no additional JSON escaping is needed. */
    fun validateManifest(m: ServerManifest) {
        require(safeName.matches(m.source) && safeName.matches(m.kind))
        require(monthPattern.matches(m.month) && m.id == "${m.source}__${m.kind}__${m.month}")
        require(m.count in 1..MAX_ROWS && m.parts.isNotEmpty() && m.parts.size.toLong() <= m.count)
        require(m.parts.distinct().size == m.parts.size)
        val prefix = m.id + "__"
        require(m.parts.all { it.startsWith(prefix) && hex.matches(it.removePrefix(prefix)) })
        val partJson = m.parts.joinToString(",", "[", "]") { "\"$it\"" }
        require(hex.matches(m.sha256) && digest(partJson) == m.sha256)
    }

    /** Identical bytes to the server helper. Includes every group, its ordered
     * immutable part identities (via the manifest hash), and row count. */
    fun catalogDigest(manifests: List<ServerManifest>): String {
        require(manifests.size <= MAX_GROUPS)
        require(manifests.map { it.id }.distinct().size == manifests.size)
        manifests.forEach(::validateManifest)
        return digest(manifests.sortedBy { it.id }.joinToString("") { "${it.id}\t${it.sha256}\t${it.count}\n" })
    }

    fun validateStatus(status: ServerCatalogStatus) {
        require(status.schemaVersion == SCHEMA_VERSION)
        require(status.groups in 0..MAX_GROUPS.toLong() && status.records in 0..MAX_ROWS)
        require(status.publishedSeconds > 0 && status.publishedNanos in 0..999_999_999)
        require(hex.matches(status.manifestSetSha256))
        require(status.groups <= status.records && (status.groups == 0L) == (status.records == 0L))
    }

    fun validateCatalog(status: ServerCatalogStatus, manifests: List<ServerManifest>) {
        validateStatus(status)
        require(status.groups == manifests.size.toLong())
        require(status.records == manifests.sumOf { it.count })
        require(status.manifestSetSha256 == catalogDigest(manifests))
    }

    private fun unpack(part: ServerPart): ByteArray {
        require((part.rowsJson == null) != (part.rowsZlib == null))
        part.rowsJson?.let {
            val raw = it.toByteArray(Charsets.UTF_8)
            require(raw.size <= MAX_PART_BYTES)
            return raw
        }
        val packed = requireNotNull(part.rowsZlib)
        require(packed.size <= MAX_PART_BYTES)
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(packed)).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n == -1) break
                require(out.size() + n <= MAX_PART_BYTES)
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    fun validatePart(m: ServerManifest, p: ServerPart): List<VerifiedServerRow> {
        require(p.id in m.parts && p.source == m.source && p.kind == m.kind && p.month == m.month)
        require(p.count in 1..m.count)
        val raw = unpack(p)
        require(hex.matches(p.sha256) && digest(raw) == p.sha256)
        require(p.id == m.id + "__" + p.sha256)
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        val arr = JSONArray(decoded)
        require(arr.length().toLong() == p.count)
        val seen = mutableSetOf<String>()
        return (0 until arr.length()).map { i ->
            val row: JSONObject = arr.getJSONObject(i)
            val id = row.get("id") as? String ?: error("invalid id")
            val day = row.get("day") as? String ?: error("invalid day")
            require(id.isNotBlank() && seen.add(id))
            require(day == "undated" || LocalDate.parse(day).toString() == day)
            require(day.take(7) == m.month)
            require(row.has("data") && !row.isNull("data"))
            VerifiedServerRow(m.source, m.kind, id, day, row.toString())
        }
    }

    /** Re-check status after reading the catalog and again after downloading all
     * parts. A changed publication must be retried, not partially activated. */
    fun samePublication(before: ServerCatalogStatus, after: ServerCatalogStatus) = before == after
}
