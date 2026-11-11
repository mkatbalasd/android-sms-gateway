package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.DeviceStorageMigration.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CARD-21 (review rev-fork17-1): the migration on real files: copy not move, the database
 * set (-wal and -shm) together, any `gateway*` file counts as a source, the password is not
 * in the device-protected copy, and a flagged destination is never overwritten.
 */
class DeviceStorageStoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefsXml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="gateway.REGISTRATION_INFO">{"id":"d1","token":"t","password":"p"}</string>
    <string name="webhooks.signing_key">k</string>
</map>
"""

    private lateinit var cePrefs: File
    private lateinit var dePrefs: File
    private lateinit var ceDb: File
    private lateinit var deDb: File
    private var flag = false

    private fun setUp() {
        cePrefs = tmp.newFolder("ce_prefs")
        dePrefs = File(tmp.root, "de_prefs")
        ceDb = tmp.newFolder("ce_db")
        deDb = File(tmp.root, "de_db")
    }

    private fun migration(unlocked: Boolean = true) = DeviceStorageMigration(
        isUnlocked = { unlocked },
        isDone = { flag },
        stores = listOf(
            DeviceStorageStores.preferences(cePrefs, dePrefs, "app_preferences"),
            DeviceStorageStores.database(ceDb, deDb, "gateway"),
        ),
        markDone = { flag = true },
    )

    private fun names(dir: File) = dir.list()?.toSet() ?: emptySet()

    @Test
    fun copiesPreferencesAndTheWholeDatabaseSetAndKeepsTheOriginals() {
        setUp()
        File(cePrefs, "app_preferences.xml").writeText(prefsXml)
        File(ceDb, "gateway").writeText("db")
        File(ceDb, "gateway-wal").writeText("committed rows in the log")
        File(ceDb, "gateway-shm").writeText("shm")

        assertEquals(Result.DONE, migration().run())

        assertEquals(setOf("gateway", "gateway-wal", "gateway-shm"), names(deDb))
        assertEquals("committed rows in the log", File(deDb, "gateway-wal").readText())
        // the password is not in the device-protected copy, the signing key is
        val copied = File(dePrefs, "app_preferences.xml").readText()
        assertFalse(copied.contains("\"password\""))
        assertTrue(copied.contains("\"token\":\"t\"") || copied.contains("&quot;token&quot;"))
        assertTrue(copied.contains(">k<"))
        // copy, not move: the credential-encrypted originals are intact, password included
        assertEquals(prefsXml, File(cePrefs, "app_preferences.xml").readText())
        assertEquals(setOf("gateway", "gateway-wal", "gateway-shm"), names(ceDb))
        assertTrue(flag)
    }

    @Test
    fun aWalWithoutTheMainFileStillCountsAsASource() {
        // the review case: a kill between two files left only the log behind; the old check
        // looked at the main file alone, found nothing, set the flag and orphaned the log
        setUp()
        File(ceDb, "gateway-wal").writeText("committed rows in the log")

        assertEquals(Result.DONE, migration().run())

        assertEquals("committed rows in the log", File(deDb, "gateway-wal").readText())
    }

    @Test
    fun leftoverTemporaryCopiesOfAKilledAttemptAreDeletedAtTheStart() {
        setUp()
        // a kill left an unscrubbed temporary copy of the preferences in the destination
        dePrefs.mkdirs()
        File(dePrefs, "app_preferences.xml.mnaseb-tmp").writeText("password here")
        File(dePrefs, "mnaseb_device_storage.xml").writeText("flag file of another store: keep")
        deDb.mkdirs()
        File(deDb, "gateway.mnaseb-tmp").writeText("half a db")
        File(ceDb, "gateway").writeText("db")

        assertEquals(Result.DONE, migration().run())

        assertEquals(setOf("mnaseb_device_storage.xml"), names(dePrefs))
        assertEquals(setOf("gateway"), names(deDb))
    }

    @Test
    fun aFreshInstallHasNothingToCopyAndStillWritesTheFlag() {
        setUp()
        assertEquals(Result.DONE, migration().run())
        assertTrue(flag)
        assertFalse(dePrefs.exists())
        assertFalse(deDb.exists())
    }

    @Test
    fun aCrashedAttemptIsRetriedAndDoesNotLeaveAStaleLogBehind() {
        setUp()
        File(cePrefs, "app_preferences.xml").writeText(prefsXml)
        File(ceDb, "gateway").writeText("db")
        // an older partial copy left a log that does not belong to this database
        deDb.mkdirs()
        File(deDb, "gateway-wal").writeText("stale log")

        assertEquals(Result.DONE, migration().run())

        assertEquals(setOf("gateway"), names(deDb))
    }

    @Test
    fun neverOverwritesDeviceProtectedDataThatIsNewer() {
        setUp()
        File(cePrefs, "app_preferences.xml").writeText(prefsXml)
        File(ceDb, "gateway").writeText("stale db")
        assertEquals(Result.DONE, migration().run())

        // the app ran on the device-protected copy and wrote newer data
        File(deDb, "gateway").writeText("newer db")

        // a later start (even a downgrade/upgrade round trip that touched the old CE files)
        File(ceDb, "gateway").writeText("older db written by an old build")
        assertEquals(Result.ALREADY_DONE, migration().run())

        assertEquals("newer db", File(deDb, "gateway").readText())
    }

    @Test
    fun aMigrationDeferredWhileLockedLeavesBothSidesUntouched() {
        setUp()
        File(cePrefs, "app_preferences.xml").writeText(prefsXml)
        File(ceDb, "gateway").writeText("db")

        assertEquals(Result.SKIPPED_LOCKED, migration(unlocked = false).run())

        assertFalse(dePrefs.exists())
        assertFalse(deDb.exists())
        assertFalse(flag)

        assertEquals(Result.DONE, migration().run())
        assertTrue(File(deDb, "gateway").exists())
    }

    @Test
    fun anUnreadablePrefsFileFailsTheMigrationAndPublishesNothing() {
        setUp()
        File(cePrefs, "app_preferences.xml").writeText("<map><string name=\"a\">")
        File(ceDb, "gateway").writeText("db")

        val m = migration()
        assertEquals(Result.FAILED, m.run())

        assertFalse(flag)
        assertFalse(File(dePrefs, "app_preferences.xml").exists())
        assertEquals("preferences: SAXParseException", m.failure)
    }
}
