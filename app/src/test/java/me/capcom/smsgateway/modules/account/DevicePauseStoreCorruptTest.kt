package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.ui.account.FakeAppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A corrupt device-protected pause value reads as paused, and the resume repairs it. */
internal class DevicePauseStoreCorruptTest {

    private val now = 1_800_000_000_000L

    private class Plain : PauseStore {
        override var pausedAtMs: Long? = null
        override var resumedAtMs: Long? = null
        override var holdMaxMin: Int? = null
    }

    private fun corrupt(storage: FakeKeyValueStorage) {
        storage.values.keys.toList().forEach { storage.values[it] = "}{ not json" }
    }

    private fun storeOver(storage: FakeKeyValueStorage, unlocked: Boolean = true) =
        DevicePauseStore(storage, { Plain() }, isUnlocked = { unlocked })

    /** A store that was written once, then damaged. */
    private fun damaged(): Pair<FakeKeyValueStorage, DevicePauseStore> {
        val storage = FakeKeyValueStorage()
        storeOver(storage).holdMaxMin = 30
        assertTrue(storage.values.isNotEmpty())
        corrupt(storage)
        return storage to storeOver(storage)
    }

    @Test
    fun aCorruptValueReadsAsPausedNeverAsNotPaused() {
        val (_, store) = damaged()

        assertTrue(store.isPaused)
    }

    @Test
    fun aCorruptValueIsRewrittenCleanSoLaterReadsWork() {
        val (storage, store) = damaged()
        store.isPaused

        // a second instance (a restart) and the locked mode read it without any error
        val locked = storeOver(storage, unlocked = false)
        assertTrue(locked.isPaused)
        assertNotNull(locked.pausedAtMs)
    }

    @Test
    fun theResumeAfterACorruptValueLeavesAReadableNotPausedState() = runBlocking {
        val (storage, store) = damaged()
        val events = mutableListOf<String>()
        val pending = object : PendingStore {
            override suspend fun pending() = emptyList<PendingStamp>()
            override suspend fun fail(id: String, reason: String) {}
        }
        val reporter = object : StateReporter {
            override fun report(messageId: String) {}
        }
        val services = object : ServiceSwitch {
            override fun pause() { events.add("pause") }
            override fun start() { events.add("start") }
            override fun keepReporting() { events.add("keepReporting") }
        }
        val controller = PauseController(
            FakeAppClient(), store, services, StaleSweep(pending, store, reporter) { now }
        ) { now }

        controller.resume()

        assertEquals(listOf("keepReporting", "start"), events)
        val again = storeOver(storage, unlocked = false)
        assertFalse("readable and not paused", again.isPaused)
        assertEquals(now, again.resumedAtMs)
    }

    @Test
    fun aPauseAfterACorruptValueIsAlsoWrittenCleanly() = runBlocking {
        val (storage, store) = damaged()

        store.resumedAtMs = null
        store.pausedAtMs = now

        assertTrue(storeOver(storage, unlocked = false).isPaused)
        assertEquals(now, storeOver(storage, unlocked = false).pausedAtMs)
    }

    // V2-L25: a LOST device-protected file must not reopen the gate from stale legacy values.

    @Test
    fun aLostFileAfterAResumeDoesNotReopenTheGateFromTheOldCredentialStoredValues() {
        val storage = FakeKeyValueStorage()
        // the credential-encrypted copy still says "paused 1h ago"; the user resumed since
        val legacy = Plain().apply { pausedAtMs = now - 3_600_000 }
        val first = DevicePauseStore(storage, { legacy }, isUnlocked = { true })
        first.resumedAtMs = now
        assertFalse(first.isPaused)

        // the DE file is lost (or its migration flag is gone); the old copy says resumed=null
        storage.values.clear()
        val warnings = mutableListOf<String>()
        val after = DevicePauseStore(storage, { legacy }, isUnlocked = { true }, onRepair = { warnings.add(it) })

        assertTrue("a lost file reads as paused", after.isPaused)
        assertEquals("the repair is logged once", 1, warnings.size)
    }

    @Test
    fun theOldCredentialStoredCopyIsMarkedPausedAfterTheOneTimeCopy() {
        val storage = FakeKeyValueStorage()
        val legacy = Plain().apply { resumedAtMs = now }
        val store = DevicePauseStore(storage, { legacy }, isUnlocked = { true })

        assertFalse(store.isPaused)

        // a downgrade to a build that reads the old fields, or a lost file, sees paused
        assertNotNull(legacy.pausedAtMs)
        assertEquals(null, legacy.resumedAtMs)
    }

    @Test
    fun aPhoneMigratedByAnEarlierBuildGetsTheOldCopyMarkedOnTheNextUnlockedRead() {
        // rc4: DE says migrated, the old copy was never marked and says "not paused"
        val storage = FakeKeyValueStorage()
        storage.set("state", DevicePauseStore.Stored(migrated = true))
        val legacy = Plain()
        val warnings = mutableListOf<String>()
        fun open() = DevicePauseStore(storage, { legacy }, isUnlocked = { true }, onRepair = { warnings.add(it) })

        val store = open()
        assertFalse(store.isPaused)
        store.pausedAtMs = now
        assertEquals(DevicePauseStore.LEGACY_MARK, legacy.pausedAtMs)

        // the DE file is lost: it must not reopen the gate from the old copy
        storage.values.clear()
        assertTrue(open().isPaused)
        assertEquals(1, warnings.size)
    }

    @Test
    fun unlinkThenRelinkThenPauseThenALostFileStillReadsPaused() {
        val storage = FakeKeyValueStorage()
        val legacy = Plain()
        fun open() = DevicePauseStore(storage, { legacy }, isUnlocked = { true })
        val first = open()
        assertFalse(first.isPaused)

        // unlink: the account store clears the old copy, then the pause store resets
        legacy.pausedAtMs = null
        legacy.resumedAtMs = null
        first.reset()
        assertEquals(DevicePauseStore.LEGACY_MARK, legacy.pausedAtMs)

        // linked again, paused
        val relinked = open()
        relinked.pausedAtMs = now
        storage.values.clear()

        assertTrue(open().isPaused)
    }

    @Test
    fun aMarkThatCouldNotBeWrittenIsRetriedOnALaterRead() {
        val storage = FakeKeyValueStorage()
        storage.set("state", DevicePauseStore.Stored(migrated = true))
        val legacy = Plain()
        var fail = true
        val flaky = object : PauseStore {
            override var pausedAtMs: Long?
                get() = legacy.pausedAtMs
                set(v) { if (fail) throw IllegalStateException("x") else legacy.pausedAtMs = v }
            override var resumedAtMs: Long?
                get() = legacy.resumedAtMs
                set(v) { legacy.resumedAtMs = v }
            override var holdMaxMin: Int?
                get() = legacy.holdMaxMin
                set(v) { legacy.holdMaxMin = v }
        }
        val store = DevicePauseStore(storage, { flaky }, isUnlocked = { true })
        assertFalse(store.isPaused)
        assertEquals(null, legacy.pausedAtMs)

        fail = false
        assertFalse(store.isPaused)
        assertEquals(DevicePauseStore.LEGACY_MARK, legacy.pausedAtMs)
    }

    @Test
    fun aFirstCopyIsNotReportedAsARepair() {
        val warnings = mutableListOf<String>()
        val store = DevicePauseStore(FakeKeyValueStorage(), { Plain() }, isUnlocked = { true }, onRepair = { warnings.add(it) })

        assertFalse(store.isPaused)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCorruptValueIsLogged() {
        val storage = FakeKeyValueStorage()
        storeOver(storage).holdMaxMin = 30
        corrupt(storage)
        val warnings = mutableListOf<String>()

        assertTrue(DevicePauseStore(storage, { Plain() }, isUnlocked = { true }, onRepair = { warnings.add(it) }).isPaused)

        assertEquals(1, warnings.size)
    }

    @Test
    fun thePauseFileIsWrittenSynchronously() {
        val source = me.capcom.smsgateway.modules.gateway.PauseGateSources.read("modules/account/DevicePauseStore.kt")
        assertTrue(source.contains("synchronous = true"))
    }

    @Test
    fun theHomeScreenCountsAnUnreadableStateAsPaused() {
        val source = me.capcom.smsgateway.modules.gateway.PauseGateSources.read("ui/account/HomeV2Fragment.kt")
        val start = source.indexOf("private fun isPausedNow(")
        assertTrue(start >= 0)
        // an expression body: look at the lines that follow the declaration
        val body = source.substring(start, minOf(source.length, start + 300))
        assertTrue(body.contains("catch (e: Exception)"))
        assertTrue(body.contains("true"))
    }
}
