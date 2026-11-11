package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.DeviceStorageMigration.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStorageMigrationTest {

    private class FakeStore(
        override val name: String,
        private val calls: MutableList<String>,
        var exists: Boolean = true,
        var fails: Boolean = false,
    ) : DeviceStorageMigration.Store {
        override fun hasSource(): Boolean = exists

        override fun copy() {
            calls += name
            if (fails) throw java.io.IOException("boom")
        }
    }

    private class Fake(
        var unlocked: Boolean = true,
        var done: Boolean = false,
        prefsExist: Boolean = true,
        dbExist: Boolean = true,
        prefsFail: Boolean = false,
        dbFail: Boolean = false,
    ) {
        val calls = mutableListOf<String>()
        val prefs = FakeStore("prefs", calls, prefsExist, prefsFail)
        val db = FakeStore("db", calls, dbExist, dbFail)

        fun migration() = DeviceStorageMigration(
            isUnlocked = { unlocked },
            isDone = { done },
            stores = listOf(prefs, db),
            markDone = { calls += "flag"; done = true },
        )
    }

    @Test
    fun copiesPrefsThenDbThenWritesTheFlagLast() {
        val f = Fake()
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("prefs", "db", "flag"), f.calls)
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
    fun neverOverwritesDeviceProtectedDataOnceTheFlagIsSet() {
        // the flag is the only thing that says the device-protected copy is authoritative:
        // when it is set, nothing is copied again, whatever the credential-encrypted side holds
        val f = Fake(done = true)
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
    fun aMigrationDeferredWhileLockedRunsAtTheFirstUnlockedStart() {
        val f = Fake(unlocked = false)
        assertEquals(Result.SKIPPED_LOCKED, f.migration().run())

        f.unlocked = true
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("prefs", "db", "flag"), f.calls)
    }

    @Test
    fun flagIsNotWrittenWhenThePrefsCopyFails() {
        val f = Fake(prefsFail = true)
        val m = f.migration()
        assertEquals(Result.FAILED, m.run())
        assertEquals(listOf("prefs"), f.calls)
        assertFalse(f.done)
        assertNotNull(m.failure)
    }

    @Test
    fun flagIsNotWrittenWhenTheDbCopyFailsAndItIsRetriedLater() {
        val f = Fake(dbFail = true)
        assertEquals(Result.FAILED, f.migration().run())
        assertEquals(listOf("prefs", "db"), f.calls)
        assertFalse(f.done)

        f.db.fails = false
        f.calls.clear()
        assertEquals(Result.DONE, f.migration().run())
        // the retry copies the whole thing again (idempotent), then the flag
        assertEquals(listOf("prefs", "db", "flag"), f.calls)
        assertTrue(f.done)
    }

    @Test
    fun aFailureNamesTheStoreWithoutAnyContent() {
        val f = Fake(dbFail = true)
        val m = f.migration()
        m.run()
        assertEquals("db: IOException", m.failure)
    }

    @Test
    fun noFailureIsReportedOnSuccess() {
        val m = Fake().migration()
        m.run()
        assertNull(m.failure)
    }

    @Test
    fun flagIsNotWrittenWhenAStepThrowsSomethingElse() {
        val f = Fake()
        val m = DeviceStorageMigration(
            isUnlocked = { true },
            isDone = { f.done },
            stores = listOf(object : DeviceStorageMigration.Store {
                override val name = "prefs"
                override fun hasSource() = true
                override fun copy() = throw IllegalStateException("boom")
            }),
            markDone = { f.done = true },
        )
        assertEquals(Result.FAILED, m.run())
        assertFalse(f.done)
    }

    @Test
    fun missingSourcesAreTolerated() {
        val f = Fake(prefsExist = false, dbExist = false)
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("flag"), f.calls)
    }

    @Test
    fun missingDatabaseOnlyIsTolerated() {
        val f = Fake(dbExist = false)
        assertEquals(Result.DONE, f.migration().run())
        assertEquals(listOf("prefs", "flag"), f.calls)
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
