package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** CARD-24 item 4: the credential-protected rollback copies of CARD-21 are removed, safely. */
class RollbackCopiesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun layout(): Pair<List<File>, File> {
        val ce = tmp.newFolder("ce")
        val prefs = File(ce, "shared_prefs").apply { mkdirs() }
        val dbs = File(ce, "databases").apply { mkdirs() }
        val account = File(prefs, "mnaseb-account.xml").apply { writeText("session") }
        val work = File(dbs, "androidx.work.workdb").apply { writeText("work") }
        listOf("pkg_preferences.xml", "pkg_preferences.xml.bak").forEach { File(prefs, it).writeText("x") }
        listOf("gateway", "gateway-wal", "gateway-shm").forEach { File(dbs, it).writeText("x") }
        val files = RollbackCopies.candidates(ce, dbs, "pkg_preferences", "gateway")
        assertTrue(account.exists() && work.exists())
        return files to ce
    }

    @Test
    fun removesTheOldPreferencesAndDatabaseButNotTheAccountOrWorkManagerStores() {
        val (files, ce) = layout()

        val removed = RollbackCopies.remove(true, true, true, files)

        assertEquals(5, removed)
        files.forEach { assertFalse(it.path, it.exists()) }
        assertTrue(File(ce, "shared_prefs/mnaseb-account.xml").exists())
        assertTrue(File(ce, "databases/androidx.work.workdb").exists())
    }

    @Test
    fun removesNothingBeforeTheCopyIsDone() {
        val (files, _) = layout()
        assertEquals(0, RollbackCopies.remove(false, true, true, files))
        assertTrue(files.any { it.exists() })
    }

    @Test
    fun removesNothingWhenTheAppRunsOnTheOriginalData() {
        val (files, _) = layout()
        assertEquals(0, RollbackCopies.remove(true, false, true, files))
        assertEquals(5, files.count { it.exists() })
    }

    @Test
    fun removesNothingWhileLocked() {
        val (files, _) = layout()
        assertEquals(0, RollbackCopies.remove(true, true, false, files))
        assertEquals(5, files.count { it.exists() })
    }

    @Test
    fun aSecondRunFindsNothingLeft() {
        val (files, _) = layout()
        RollbackCopies.remove(true, true, true, files)
        assertEquals(0, RollbackCopies.remove(true, true, true, files))
    }
}
