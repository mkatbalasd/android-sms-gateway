package me.capcom.smsgateway.modules.messages

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.messages.events.MessageStateChangedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rev-fork28-3 L1: [RoomPendingStore.failIfPending] for a message that already moved on.
 * Failing such a message would end (and refund) a message that was, or is being, sent.
 * Each case seeds a state a plain "fail" transition would still accept (Sent, Processed,
 * Delivered, Cancelled all may go to Failed), so an implementation that failed without the
 * "every recipient is Pending" check would turn these cases red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomPendingStoreTest {

    private class Harness(
        val dao: FakeMessagesDao,
        val store: RoomPendingStore,
        val events: MutableList<MessageStateChangedEvent>,
    )

    private fun TestScope.harness(): Harness {
        val dao = FakeMessagesDao()
        val bus = EventBus()
        val machine = MessageStateService(dao, bus)
        val events = mutableListOf<MessageStateChangedEvent>()
        backgroundScope.launch { bus.collect<MessageStateChangedEvent> { events += it } }
        runCurrent()
        return Harness(dao, RoomPendingStore(dao, machine) { SendGate() }, events)
    }

    private suspend fun TestScope.leavesAlone(state: ProcessingState) {
        val h = harness()
        h.dao.seed("m1", messageState = state, seedRecipients = listOf("+111" to state))

        assertFalse("a $state message is not failed", h.store.failIfPending("m1", "TTL expired"))
        runCurrent()

        assertEquals(state, h.dao.storedState("m1"))
        assertEquals(state, h.dao.recipientState("m1", "+111"))
        assertTrue("no state event, so no refund trigger", h.events.isEmpty())
    }

    @Test
    fun aSentMessageIsLeftAlone() = runTest { leavesAlone(ProcessingState.Sent) }

    @Test
    fun aProcessedMessageIsLeftAlone() = runTest { leavesAlone(ProcessingState.Processed) }

    @Test
    fun aDeliveredMessageIsLeftAlone() = runTest { leavesAlone(ProcessingState.Delivered) }

    @Test
    fun aCancelledMessageIsLeftAlone() = runTest { leavesAlone(ProcessingState.Cancelled) }

    @Test
    fun anAlreadyFailedMessageIsNotFailedAgainOrReportedTwice() = runTest {
        val h = harness()
        h.dao.seed("m1")
        assertTrue(h.store.failIfPending("m1", "TTL expired"))
        runCurrent()
        assertEquals(1, h.events.size)

        // the second sweep sees the old snapshot: nothing happens, one event only (one refund)
        assertFalse(h.store.failIfPending("m1", "TTL expired"))
        runCurrent()

        assertEquals(1, h.events.size)
        assertEquals(ProcessingState.Failed, h.dao.storedState("m1"))
    }

    @Test
    fun aMissingMessageIsNotAnError() = runTest {
        val h = harness()

        assertFalse(h.store.failIfPending("gone", "TTL expired"))

        assertTrue(h.events.isEmpty())
    }
}
