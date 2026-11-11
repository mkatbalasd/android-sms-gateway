package me.capcom.smsgateway.modules.messages

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.gateway.PauseGateSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pause that lands during a delay of the send loop (rate limit, work hours, send
 * interval) must not let the message in hand go: the gate is checked again right before the send.
 */
internal class SendUnlessPausedTest {

    private var paused = false
    private val sends = mutableListOf<String>()

    /** The shape of the loop body: wait, then the last check, then the send. */
    private suspend fun oneIteration(duringDelay: () -> Unit): Boolean? {
        delay(1L) // stands for the rate limit or the work-hours wait
        duringDelay()
        return sendUnlessPaused({ paused }) {
            sends.add("m1")
            true
        }
    }

    @Test
    fun aPauseSetDuringTheDelayMeansNoSend() = runBlocking {
        val result = oneIteration { paused = true }

        assertNull("paused: the send is not made", result)
        assertEquals(emptyList<String>(), sends)
    }

    @Test
    fun withoutAPauseTheMessageIsSent() = runBlocking {
        val result = oneIteration { }

        assertEquals(true, result)
        assertEquals(listOf("m1"), sends)
    }

    @Test
    fun aGateThatThrowsCountsAsPaused() {
        val result = sendUnlessPaused({ throw IllegalStateException("unreadable") }) {
            sends.add("m1")
            true
        }

        assertNull(result)
        assertEquals(emptyList<String>(), sends)
    }

    @Test
    fun theSendLoopChecksAgainRightBeforeEachSend() {
        val loop = PauseGateSources.functionBody(
            PauseGateSources.read("modules/messages/MessagesService.kt"), "private suspend fun drainPendingMessages("
        )
        val recheck = loop.indexOf("sendUnlessPaused(")
        assertTrue("the loop must use sendUnlessPaused", recheck >= 0)
        for (wait in listOf("delay(1L)", "applyLimit()", "applyWorkHoursLimit()")) {
            assertTrue("$wait must come before the last pause check", loop.indexOf(wait) in 0 until recheck)
        }
        assertEquals("the only sendMessage call is inside the check", 1, Regex("sendMessage\\(message\\)").findAll(loop).count())
        assertTrue(loop.indexOf("sendMessage(message)") > recheck)
    }

    @Test
    fun everySendPathChecksThePause() {
        // SendMessagesWorker and LockedRunner call sendPendingMessages (no wait between their
        // check and the loop); the loop holds every wait and has its own last check.
        for (file in listOf("modules/messages/workers/SendMessagesWorker.kt", "modules/gateway/LockedRunner.kt")) {
            val source = PauseGateSources.read(file)
            assertTrue(file, source.contains("PauseGate.isPaused()"))
            assertTrue(file, source.indexOf("PauseGate.isPaused()") < source.indexOf("sendPendingMessages()"))
        }
    }
}
