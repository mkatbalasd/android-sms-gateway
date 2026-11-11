package me.capcom.smsgateway.modules.gateway

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.account.DevicePauseStore
import me.capcom.smsgateway.modules.account.FakeKeyValueStorage
import me.capcom.smsgateway.modules.account.LockedException
import me.capcom.smsgateway.modules.account.PauseStateUnreadableException
import me.capcom.smsgateway.modules.account.PauseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CARD-21 round 7: the pause is read from device-protected storage, so the locked pipeline
 * (before the first unlock) honours it, and it fails CLOSED.
 */
internal class LockedPauseTest {

    private val now = 1_800_000_000_000L

    private class Plain(
        override var pausedAtMs: Long? = null,
        override var resumedAtMs: Long? = null,
        override var holdMaxMin: Int? = null,
    ) : PauseStore

    /** A store whose every read fails, as the credential-encrypted one does before the first unlock. */
    private class Unreadable : PauseStore {
        override var pausedAtMs: Long?
            get() = throw LockedException()
            set(_) = throw LockedException()
        override var resumedAtMs: Long?
            get() = throw LockedException()
            set(_) = throw LockedException()
        override var holdMaxMin: Int?
            get() = throw LockedException()
            set(_) = throw LockedException()
        override val isPaused: Boolean get() = throw LockedException()
    }

    private fun message(): GatewayApi.Message =
        Gson().fromJson("""{"id":"m1","phoneNumbers":["+1000","+2000"]}""", GatewayApi.Message::class.java)

    private val calls = mutableListOf<String>()

    private fun pull(store: PauseStore) = runBlocking {
        enqueueUnlessHeld(message(), store, now, { calls.add("report") }) { calls.add("enqueue") }
    }

    private fun dePauseStore(
        storage: FakeKeyValueStorage = FakeKeyValueStorage(),
        unlocked: Boolean,
        legacy: () -> PauseStore = { Plain() },
    ) = DevicePauseStore(storage, legacy, isUnlocked = { unlocked })

    // region the pull while locked

    @Test
    fun lockedAndNotPausedTheMessageIsEnqueued() {
        // the device-protected store holds the copied state and is readable while locked
        val storage = FakeKeyValueStorage()
        dePauseStore(storage, unlocked = true).holdMaxMin = 30
        val locked = dePauseStore(storage, unlocked = false)

        pull(locked)

        assertEquals(listOf("enqueue"), calls)
    }

    @Test
    fun lockedAndPausedNothingIsEnqueuedOrSent() {
        val storage = FakeKeyValueStorage()
        dePauseStore(storage, unlocked = true).pausedAtMs = now - 1_000
        val locked = dePauseStore(storage, unlocked = false)
        assertTrue(locked.isPaused)

        pull(locked)

        assertEquals("nothing enqueued, nothing reported", emptyList<String>(), calls)
    }

    @Test
    fun aResumedPauseEnqueuesAgain() {
        val storage = FakeKeyValueStorage()
        val unlocked = dePauseStore(storage, unlocked = true)
        unlocked.pausedAtMs = now - 60_000
        unlocked.resumedAtMs = now - 1_000

        pull(dePauseStore(storage, unlocked = false))

        assertEquals(listOf("enqueue"), calls)
    }

    @Test
    fun anUnreadablePauseStateIsTreatedAsPaused() {
        pull(Unreadable())

        assertEquals("fail closed: not enqueued, not reported", emptyList<String>(), calls)
    }

    @Test
    fun lockedBeforeTheOneTimeCopyTheStateIsUnknownAndNothingIsEnqueued() {
        // an updated phone that was not unlocked since the update: no copy yet, old copy unreadable
        val store = dePauseStore(unlocked = false, legacy = { throw LockedException() })
        try {
            store.isPaused
            fail("must not answer 'not paused' for an unknown state")
        } catch (expected: PauseStateUnreadableException) {
        }

        pull(store)

        assertEquals(emptyList<String>(), calls)
    }

    // endregion

    // region the device-protected store

    @Test
    fun theFirstUnlockedReadCopiesTheOldCredentialStoredPauseOnce() {
        val storage = FakeKeyValueStorage()
        var legacyReads = 0
        val legacy = Plain(pausedAtMs = now - 5_000, holdMaxMin = 45)
        val store = dePauseStore(storage, unlocked = true) { legacyReads++; legacy }

        assertTrue(store.isPaused)
        assertEquals(45, store.holdMaxMin)

        // later changes live in the device-protected copy; the old one is not asked again
        store.resumedAtMs = now
        assertFalse(store.isPaused)
        assertEquals(1, legacyReads)
        // and it is readable before the next unlock without the old store
        val locked = dePauseStore(storage, unlocked = false, legacy = { throw LockedException() })
        assertFalse(locked.isPaused)
        assertEquals(45, locked.holdMaxMin)
    }

    @Test
    fun aFailedCopyIsNotCountedAsNotPaused() {
        val store = dePauseStore(unlocked = true, legacy = { throw LockedException() })
        try {
            store.isPaused
            fail("the old store could not be read: the pause state is unknown")
        } catch (expected: LockedException) {
        }
    }

    @Test
    fun pauseAndResumeAreWrittenToTheDeviceProtectedStore() {
        val storage = FakeKeyValueStorage()
        val store = dePauseStore(storage, unlocked = true)
        store.resumedAtMs = null
        store.pausedAtMs = now

        assertTrue(dePauseStore(storage, unlocked = false).isPaused)
    }

    @Test
    fun forgettingTheAccountClearsThePauseAndDoesNotCopyTheOldOneBack() {
        val storage = FakeKeyValueStorage()
        val store = dePauseStore(storage, unlocked = true) { Plain(pausedAtMs = now) }
        assertTrue(store.isPaused)

        store.reset()

        assertFalse(dePauseStore(storage, unlocked = true) { Plain(pausedAtMs = now) }.isPaused)
        assertEquals(null, store.pausedAtMs)
    }

    @Test
    fun theGateWithoutAReadableStoreIsClosed() {
        // no Koin in a JVM test: nothing readable, so paused
        assertTrue(me.capcom.smsgateway.modules.account.PauseGate.defaultProvider())
    }

    // endregion

    // region logs: no recipient numbers, no text

    @Test
    fun theFailurePathLogsTheMessageIdAndTheErrorClassOnly() = runBlocking {
        val logged = mutableListOf<Pair<String, Map<String, String>>>()
        val failing = message()

        processPulled(
            listOf(failing, failing.copy(id = "m2")),
            process = { throw IllegalStateException("cannot send to +1000 and +2000: the secret body") },
            log = { text, data -> logged.add(text to data) },
        )

        assertEquals("one failure must not stop the next message", 2, logged.size)
        for ((text, data) in logged) {
            val everything = text + data.toString()
            assertFalse(everything, everything.contains("+1000") || everything.contains("+2000"))
            assertFalse(everything, everything.contains("secret body"))
            assertEquals(setOf("messageId", "exception"), data.keys)
            assertEquals("java.lang.IllegalStateException", data["exception"])
        }
        assertEquals(listOf("m1", "m2"), logged.map { it.second["messageId"] })
    }

    @Test
    fun aLockedExceptionIsLoggedWithoutItsStackTrace() = runBlocking {
        val logged = mutableListOf<Map<String, String>>()

        processPulled(listOf(message()), { throw LockedException() }) { _, data -> logged.add(data) }

        assertEquals(1, logged.size)
        assertFalse(logged[0].toString().contains("\n"))
        assertTrue(logged[0]["exception"]!!.endsWith("LockedException"))
    }

    @Test
    fun theServiceNoLongerLogsTheMessageOrAStackTrace() {
        val source = PauseGateSources.read("modules/gateway/GatewayService.kt")
        val pull = PauseGateSources.functionBody(source, "internal suspend fun getNewMessages(")
        assertFalse(pull.contains("\"message\" to"))
        assertFalse(pull.contains("stackTraceToString"))
        assertFalse(pull.contains("printStackTrace"))
        assertTrue(pull.contains("processPulled("))
    }

    // endregion

    // region the other locked-mode entries honour the pause

    @Test
    fun theLockedSendRefusesWhilePaused() {
        val body = PauseGateSources.functionBody(
            PauseGateSources.read("modules/gateway/LockedRunner.kt"), "override suspend fun sendPending("
        )
        assertTrue(body.contains("PauseGate.isPaused()"))
        assertTrue(body.indexOf("PauseGate.isPaused()") < body.indexOf("sendPendingMessages()"))
    }

    @Test
    fun theSendLoopStopsBeforeTheNextMessageWhilePaused() {
        val body = PauseGateSources.functionBody(
            PauseGateSources.read("modules/messages/MessagesService.kt"), "private suspend fun drainPendingMessages("
        )
        assertTrue(body.contains("PauseGate.isPaused()"))
        assertTrue(body.indexOf("PauseGate.isPaused()") < body.indexOf("messages.getPending("))
    }

    // endregion

    // region WorkerStateReporter goes through the scheduler

    private class RecordingScheduler : WorkScheduler {
        val states = mutableListOf<String>()
        override fun pullMessages() {}
        override fun sendMessages(force: Boolean, scheduleAt: Long) {}
        override fun sendState(messageId: String) {
            states.add(messageId)
        }

        override fun processWebhookQueue(internetRequired: Boolean) {}
        override fun updateWebhooks() {}
        override fun updateSettings() {}
    }

    @Test
    fun theStateReporterUsesTheScheduler() {
        val scheduler = RecordingScheduler()

        WorkerStateReporter(scheduler).report("m9")

        assertEquals(listOf("m9"), scheduler.states)
    }

    // endregion
}
