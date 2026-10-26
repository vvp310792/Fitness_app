package com.fitnessapp.summary.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** The importer replaces a whole period with the file's rows - only an intact file may pass. */
class GymBackupPartsTest {

    private val file = ByteArray(1000) { (it * 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
    private val parts = listOf(file.copyOfRange(0, 400), file.copyOfRange(400, 800), file.copyOfRange(800, 1000))
        .mapIndexed { i, p -> i to Base64.getEncoder().encodeToString(p) }

    @Test
    fun `parts in any order give the file back`() {
        val out = GymBackupParts.assemble(parts.reversed(), 3, sha.uppercase())
        assertArrayEquals(file, out.getOrThrow())
    }

    @Test
    fun `missing part is refused`() {
        assertTrue(GymBackupParts.assemble(parts.take(2), 3, sha).isFailure)
    }

    @Test
    fun `duplicated part is refused`() {
        assertTrue(GymBackupParts.assemble(parts + parts[1], 3, sha).isFailure)
    }

    @Test
    fun `wrong checksum is refused`() {
        assertTrue(GymBackupParts.assemble(parts, 3, "0".repeat(64)).isFailure)
    }

    @Test
    fun `no checksum is refused`() {
        assertTrue(GymBackupParts.assemble(parts, 3, null).isFailure)
    }
}
