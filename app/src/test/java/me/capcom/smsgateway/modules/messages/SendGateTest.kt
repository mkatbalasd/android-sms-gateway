package me.capcom.smsgateway.modules.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-21 (review rev-fork17-1, high): at the unlock handover two send loops can exist,
 * the in-process one and the WorkManager one. Both read the pending message first and
 * mark it only after the send, so without a gate the same message is sent twice.
 *
 * The model below has the same shape as `MessagesService.sendPendingMessages`: take the
 * oldest pending message, send it (takes time), then mark it processed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendGateTest {

    private class Store(ids: List<String>) {
        private val pending = ids.toMutableList()
        val sent = mutableListOf<String>()

        fun oldestPending(): String? = pending.firstOrNull()

        fun markProcessed(id: String) {
            pending.remove(id)
        }
    }

    /** One send loop: read the oldest pending, "send" it (it takes 10 ms), mark it processed. */
    private suspend fun drain(store: Store, gate: SendGate?) {
        suspend fun loop() {
            while (true) {
                val id = store.oldestPending() ?: return
                delay(10)
                store.sent += id
                store.markProcessed(id)
            }
        }
        if (gate == null) loop() else gate.exclusive { loop() }
    }

    private fun TestScope.twoSenders(store: Store, gate: SendGate?) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        launch(dispatcher) { drain(store, gate) }
        launch(dispatcher) { drain(store, gate) }
        advanceUntilIdle()
    }

    @Test
    fun withoutTheGateTwoLoopsSendTheSameMessageTwice() = runTest {
        // Control: proves the model is racy, so the test below means something.
        val store = Store(listOf("m1"))
        twoSenders(store, gate = null)
        assertEquals(listOf("m1", "m1"), store.sent)
    }

    @Test
    fun withTheGateTwoConcurrentSendersSendEachMessageExactlyOnce() = runTest {
        val store = Store(listOf("m1", "m2", "m3"))
        twoSenders(store, SendGate())
        assertEquals(listOf("m1", "m2", "m3"), store.sent)
    }

    @Test
    fun theSecondSenderRunsAfterTheFirstAndFindsNothingLeft() = runTest {
        val gate = SendGate()
        val order = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val a = launch(dispatcher) {
            gate.exclusive {
                order += "a-start"
                delay(50)
                order += "a-end"
            }
        }
        val b = launch(dispatcher) {
            gate.exclusive {
                order += "b-start"
            }
        }
        advanceUntilIdle()
        joinAll(a, b)
        assertEquals(listOf("a-start", "a-end", "b-start"), order)
    }

    @Test
    fun theGateIsReleasedWhenTheHolderThrows() = runTest {
        val gate = SendGate()
        try {
            gate.exclusive { throw IllegalStateException("send failed") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertFalse(gate.isHeld)
        var ran = false
        gate.exclusive { ran = true }
        assertTrue(ran)
    }

    @Test
    fun theGateIsReleasedWhenTheHolderIsCancelled() = runTest {
        val gate = SendGate()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val holder = launch(dispatcher) {
            gate.exclusive { delay(10_000) }
        }
        // the holder is parked inside the gate (virtual time has not moved); cancel it,
        // as the handover shutdown does
        runCurrent()
        assertTrue(gate.isHeld)
        holder.cancel(CancellationException("shutdown"))
        advanceUntilIdle()
        assertFalse(gate.isHeld)

        var ran = false
        gate.exclusive { ran = true }
        assertTrue(ran)
    }
}
