package com.fitnessapp.summary.sync

import java.security.MessageDigest
import java.util.Base64

/**
 * Reassembles a file the PC split into base64 parts for the inbox (`tools/gymup_push.py`).
 *
 * Kept free of Firestore so the checks are pinned by a plain JVM test: a part missing or
 * read twice would otherwise reach the importer as a truncated SQLite file, and the import
 * REPLACES the period the file covers - so a wrong file must stop here, not there.
 */
object GymBackupParts {

    fun assemble(parts: List<Pair<Int, String>>, expected: Int, sha256: String?): Result<ByteArray> {
        if (expected <= 0) return Result.failure(IllegalArgumentException("частей: $expected"))
        val indices = parts.map { it.first }
        if (indices.sorted() != (0 until expected).toList()) {
            return Result.failure(IllegalStateException("части ${indices.sorted()} вместо 0..${expected - 1}"))
        }
        val bytes = try {
            parts.sortedBy { it.first }
                .map { Base64.getDecoder().decode(it.second) }
                .fold(ByteArray(0)) { acc, part -> acc + part }
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalStateException("base64 не читается: ${e.message}"))
        }
        if (sha256.isNullOrBlank()) return Result.failure(IllegalArgumentException("нет sha256"))
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) {
            return Result.failure(IllegalStateException("sha256 не совпал (${bytes.size} байт)"))
        }
        return Result.success(bytes)
    }
}
