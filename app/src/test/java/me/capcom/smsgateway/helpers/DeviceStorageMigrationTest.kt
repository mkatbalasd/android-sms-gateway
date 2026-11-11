package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.DeviceStorageMigration.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStorageMigrationTest {

    private class Fake(
        var unlocked: Boolean = true,
        var done: Boolean = false,
        var prefsExist: Boolean = true,
        var dbExist: Boolean = true,
        var prefsOk: Boolean = true,
        var dbOk: Boolean = true,
        var scrubThrows: Boolean = false,
    ) {
        val calls = mutableListOf<String>()

        fun migration() = DeviceStorageMigration(
            isUnlocked = { unlocked },
            isDone = { done },
            prefsSourceExists = { prefsExist },
            movePrefs = { calls += "prefs"; prefsOk },
            scrubPassword = {
                calls += "scrub"
                if (scrubThrows) throw IllegalStateException("boom")
            },
            dbSourceExists = { dbExist },
            moveDb = { calls += "db"; dbOk },
            markDone = { calls += "flag"; done = true },
        )
    }

    @Test
    fun movesPrefsThenScrubsThenDbThenWritesFlag() {
        val f = Fake()
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("prefs", "scrub", "db", "flag"), f.calls)
    }

    @Test
    fun runsOnce() {
        val f = Fake()
        assertEquals(Result.DONE, f.migration().run())
        f.calls.clear()
        assertEquals(Result.ALREADY_DONE, f.migration().run())
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun neverWhileLockedAndTouchesNothing() {
        val f = Fake(unlocked = false)
        assertEquals(Result.SKIPPED_LOCKED, f.migration().run())
        assertTrue(f.calls.isEmpty())
        assertFalse(f.done)
    }

    @Test
    fun flagIsNotWrittenWhenPrefsMoveFails() {
        val f = Fake(prefsOk = false)
        assertEquals(Result.FAILED, f.migration().run())
        assertEquals(listOf("prefs"), f.calls)
        assertFalse(f.done)
    }

    @Test
    fun flagIsNotWrittenWhenDbMoveFailsAndItIsRetriedLater() {
        val f = Fake(dbOk = false)
        assertEquals(Result.FAILED, f.migration().run())
        assertEquals(listOf("prefs", "scrub", "db"), f.calls)
        assertFalse(f.done)

        f.dbOk = true
        f.calls.clear()
        assertEquals(Result.DONE, f.migration().run())
        assertTrue(f.done)
    }

    @Test
    fun flagIsNotWrittenWhenAStepThrows() {
        val f = Fake(scrubThrows = true)
        assertEquals(Result.FAILED, f.migration().run())
        assertFalse(f.done)
    }

    @Test
    fun missingSourcesAreTolerated() {
        val f = Fake(prefsExist = false, dbExist = false)
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("scrub", "flag"), f.calls)
    }

    @Test
    fun missingDatabaseOnlyIsTolerated() {
        val f = Fake(dbExist = false)
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("prefs", "scrub", "flag"), f.calls)
    }

    @Test
    fun scrubRemovesOnlyThePassword() {
        val json = """{"id":"d1","token":"t","login":"l","password":"p"}"""
        val out = RegistrationInfoScrubber.scrub(json)!!
        assertFalse(out.contains("password"))
        assertTrue(out.contains("\"token\":\"t\""))
        assertTrue(out.contains("\"login\":\"l\""))
        assertTrue(out.contains("\"id\":\"d1\""))
    }

    @Test
    fun scrubLeavesAlreadyCleanOrUnreadableValuesAlone() {
        assertNull(RegistrationInfoScrubber.scrub(null))
        assertNull(RegistrationInfoScrubber.scrub(""))
        assertNull(RegistrationInfoScrubber.scrub("not json"))
        assertNull(RegistrationInfoScrubber.scrub("""{"id":"d1","password":null}"""))
        assertNull(RegistrationInfoScrubber.scrub("""{"id":"d1"}"""))
    }
}
