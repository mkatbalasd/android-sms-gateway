package me.capcom.smsgateway.modules.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.account.PauseStore
import me.capcom.smsgateway.modules.account.PendingStamp
import me.capcom.smsgateway.modules.account.PendingStore
import me.capcom.smsgateway.modules.account.StaleSweep
import me.capcom.smsgateway.modules.account.StateReporter
import me.capcom.smsgateway.modules.events.EventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** rev-fork28-1: the paused sweep must never fail a message the send loop already moved on or holds. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PausedSweepRaceTest {

    private val now = 1_800_000_000_000L
    private val hourMs = 3_600_000L

    private class Paused(at: Long) : PauseStore {
        override var pausedAtMs: Long? = at
        override var resumedAtMs: Long? = null
        override var holdMaxMin: Int? = null
    }

    private class Reports : StateReporter {
        val ids = mutableListOf<String>()
        override fun report(messageId: String) {
            ids.add(messageId)
        }
    }

    @Test
    fun aPendingMessageIsFailedByFailIfPending() = runTest {
        val dao = FakeMessagesDao()
        dao.seed("m1")
        val service = MessageStateService(dao, EventBus())

        assertTrue(service.failIfPending("m1", "TTL expired"))

        assertEquals(ProcessingState.Failed, dao.storedState("m1"))
        assertEquals(ProcessingState.Failed, dao.recipientState("m1", "+79990000001"))
    }

    @Test
    fun aMessageThatMovedToProcessedAfterTheSnapshotStaysUnfailed() = runTest {
        val dao = FakeMessagesDao()
        dao.seed("m1")
        val service = MessageStateService(dao, EventBus())
        val reports = Reports()
        // the snapshot still lists m1 as Pending, then the send loop moves it on
        val store = object : PendingStore {
            override suspend fun pending(): List<PendingStamp> {
                val snapshot = listOf(PendingStamp("m1", now - 3 * hourMs))
                service.transitionRecipient("m1", "+79990000001", ProcessingState.Processed)
                return snapshot
            }

            override suspend fun fail(id: String, reason: String) {
                service.transitionRecipients(id, ProcessingState.Failed, reason)
            }

            override suspend fun failIfPending(id: String, reason: String) = service.failIfPending(id, reason)
        }

        val dropped = StaleSweep(store, Paused(now - 2 * hourMs), reports) { now }.sweep()

        assertEquals(0, dropped)
        assertTrue("not reported", reports.ids.isEmpty())
        assertEquals(ProcessingState.Processed, dao.storedState("m1"))
        assertEquals(ProcessingState.Processed, dao.recipientState("m1", "+79990000001"))
    }

    @Test
    fun failIfPendingLeavesAPartlySentMessageAlone() = runTest {
        val dao = FakeMessagesDao()
        dao.seed(
            "m1",
            seedRecipients = listOf("+111" to ProcessingState.Pending, "+222" to ProcessingState.Processed)
        )
        val service = MessageStateService(dao, EventBus())

        assertFalse(service.failIfPending("m1", "TTL expired"))

        assertEquals(ProcessingState.Pending, dao.recipientState("m1", "+111"))
        assertEquals(ProcessingState.Processed, dao.recipientState("m1", "+222"))
    }

    @Test
    fun theSweepSkipsWhileASubmitIsInProgress() = runTest {
        val dao = FakeMessagesDao()
        dao.seed("m1")
        val service = MessageStateService(dao, EventBus())
        val gate = SendGate()
        val store = RoomPendingStore(dao, service) { gate }
        val reports = Reports()
        val sweep = StaleSweep(store, Paused(now - 2 * hourMs), reports) { now }
        val submitting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        launch {
            gate.exclusive { // the drain loop: m1 stays Pending across the submit
                submitting.complete(Unit)
                finish.await()
            }
        }
        runCurrent()
        assertTrue(submitting.isCompleted)

        // the Room snapshot lists m1 as old, but the gate is held: nothing is failed
        assertNull(store.whenNotSending { 1 })
        assertEquals(0, sweep.sweepWhilePaused())
        assertEquals(ProcessingState.Pending, dao.storedState("m1"))
        assertTrue(reports.ids.isEmpty())

        finish.complete(Unit)
        runCurrent()
        assertEquals(1, store.whenNotSending { 1 })
    }
}
