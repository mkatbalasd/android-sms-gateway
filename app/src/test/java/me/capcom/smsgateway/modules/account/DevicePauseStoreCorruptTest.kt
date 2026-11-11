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
