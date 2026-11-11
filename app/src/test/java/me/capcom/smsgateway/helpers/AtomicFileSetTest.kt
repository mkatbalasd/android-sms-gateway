package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * CARD-21 (review rev-fork17-1): the copy of one store (the preferences file, or the Room
 * database with its -wal and -shm) from credential-encrypted to device-protected storage.
 * It copies (the originals stay), writes every file under a temporary name first, and
 * renames only when the whole set is ready.
 */
class AtomicFileSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String) =
        File(dir, name).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun names(dir: File): Set<String> = dir.list()?.toSet() ?: emptySet()

    @Test
    fun copiesEveryFileAndLeavesTheOriginalsInPlace() {
        val ce = tmp.newFolder("ce")
        val de = File(tmp.root, "de")
        val sources = listOf(
            file(ce, "gateway", "db"),
            file(ce, "gateway-wal", "wal"),
            file(ce, "gateway-shm", "shm"),
        )

        AtomicFileSet.copy(sources, de)

        assertEquals("db", File(de, "gateway").readText())
        assertEquals("wal", File(de, "gateway-wal").readText())
        assertEquals("shm", File(de, "gateway-shm").readText())
        // copy, not move: a pre-CARD-21 build still finds its data (stale but intact)
        assertEquals(setOf("gateway", "gateway-wal", "gateway-shm"), names(ce))
        assertEquals("wal", File(ce, "gateway-wal").readText())
    }

    @Test
    fun leavesNoTemporaryFileBehind() {
        val ce = tmp.newFolder("ce")
        val de = File(tmp.root, "de")

        AtomicFileSet.copy(listOf(file(ce, "a", "1"), file(ce, "b", "2")), de)

        assertEquals(setOf("a", "b"), names(de))
    }

    @Test
    fun aFailureBeforeTheRenamesChangesNothingInTheTarget() {
        val ce = tmp.newFolder("ce")
        val de = tmp.newFolder("de")
        file(de, "a", "old-a")
        file(de, "b", "old-b")
        val sources = listOf(file(ce, "a", "new-a"), file(ce, "b", "new-b"))

        var calls = 0
        try {
            AtomicFileSet.copy(sources, de) {
                calls++
                if (calls == 2) throw IOException("disk full")
            }
            throw AssertionError("expected a failure")
        } catch (e: IOException) {
            // expected
        }

        assertEquals("old-a", File(de, "a").readText())
        assertEquals("old-b", File(de, "b").readText())
        assertEquals(setOf("a", "b"), names(de))
    }

    @Test
    fun retryAfterAFailureCompletesTheSetAndIsIdempotent() {
        val ce = tmp.newFolder("ce")
        val de = File(tmp.root, "de")
        val sources = listOf(file(ce, "a", "1"), file(ce, "b", "2"))

        try {
            AtomicFileSet.copy(sources, de) { throw IOException("boom") }
        } catch (e: IOException) {
            // expected
        }
        assertTrue(names(de).none { it == "a" || it == "b" })

        AtomicFileSet.copy(sources, de)
        AtomicFileSet.copy(sources, de)

        assertEquals("1", File(de, "a").readText())
        assertEquals("2", File(de, "b").readText())
        assertEquals(setOf("a", "b"), names(de))
    }

    @Test
    fun aStaleWalInTheTargetIsRemovedWhenTheSourceHasNone() {
        val ce = tmp.newFolder("ce")
        val de = tmp.newFolder("de")
        file(de, "gateway-wal", "stale wal of an older copy")
        file(de, "gateway-shm", "stale shm")

        AtomicFileSet.copy(
            sources = listOf(file(ce, "gateway", "db")),
            targetDir = de,
            ownedNames = listOf("gateway", "gateway-wal", "gateway-shm", "gateway-journal"),
        )

        assertEquals(setOf("gateway"), names(de))
    }

    @Test
    fun filesOutsideTheSetAreNeverTouched() {
        val ce = tmp.newFolder("ce")
        val de = tmp.newFolder("de")
        file(de, "other.xml", "keep me")

        AtomicFileSet.copy(
            sources = listOf(file(ce, "gateway", "db")),
            targetDir = de,
            ownedNames = listOf("gateway", "gateway-wal"),
        )

        assertEquals("keep me", File(de, "other.xml").readText())
    }

    @Test
    fun missingSourceFilesAreSkipped() {
        val ce = tmp.newFolder("ce")
        val de = File(tmp.root, "de")

        AtomicFileSet.copy(listOf(file(ce, "gateway", "db"), File(ce, "gateway-wal")), de)

        assertEquals(setOf("gateway"), names(de))
    }

    @Test
    fun theTransformSeesTheTemporaryCopyNeverTheOriginal() {
        val ce = tmp.newFolder("ce")
        val de = File(tmp.root, "de")
        val source = file(ce, "prefs.xml", "secret")

        AtomicFileSet.copy(listOf(source), de) { it.writeText("scrubbed") }

        assertEquals("secret", source.readText())
        assertEquals("scrubbed", File(de, "prefs.xml").readText())
        assertFalse(names(de).any { it.endsWith(AtomicFileSet.TMP_SUFFIX) })
    }
}
