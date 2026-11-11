package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.account.PauseGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD V2-L23: a paused phone neither pulls nor sends, and its reporting keeps running.
 * Every gate must refuse BEFORE the call it guards.
 */
internal class PauseGatesTest {

    private val gate = "PauseGate.isPaused()"

    @After
    fun restoreProvider() {
        PauseGate.provider = PauseGate.defaultProvider
    }

    private fun assertGatedBefore(file: String, marker: String, guarded: String) {
        val body = PauseGateSources.functionBody(PauseGateSources.read(file), marker)
        val gateAt = body.indexOf(gate)
        val sinkAt = body.indexOf(guarded)
        assertTrue("$file: $marker must check $gate", gateAt >= 0)
        assertTrue("$file: $guarded not found in $marker", sinkAt >= 0)
        assertTrue("$file: $gate must come before $guarded", gateAt < sinkAt)
    }

    // region the gate itself

    @Test
    fun theGateFollowsTheStore() {
        PauseGate.provider = { true }
        assertTrue(PauseGate.isPaused())
        PauseGate.provider = { false }
        assertFalse(PauseGate.isPaused())
    }

    @Test
    fun withoutAStoreTheGateIsOpen() {
        // No Koin in a JVM test: the default provider fails open, never closed.
        assertFalse(PauseGate.defaultProvider())
    }

    // endregion

    // region each gate refuses while paused

    @Test
    fun getNewMessagesRefusesWhilePaused() =
        assertGatedBefore("modules/gateway/GatewayService.kt", "internal suspend fun getNewMessages(", "api.getMessages(")

    @Test
    fun thePullWorkerRefusesToBeScheduledWhilePaused() =
        assertGatedBefore(
            "modules/gateway/workers/PullMessagesWorker.kt", "fun start(context: Context)", "WorkManager.getInstance"
        )

    @Test
    fun theLiveStreamRefusesToStartWhilePaused() =
        assertGatedBefore(
            "modules/gateway/services/SSEForegroundService.kt", "fun start(context: Context)", "startForegroundService"
        )

    @Test
    fun theSendWorkerSendsNothingWhilePaused() =
        assertGatedBefore(
            "modules/messages/workers/SendMessagesWorker.kt", "override suspend fun doWork(", "sendPendingMessages"
        )

    @Test
    fun theNetworkReconnectRefusesWhilePaused() {
        val source = PauseGateSources.read("modules/gateway/services/SSEForegroundService.kt")
        val at = source.indexOf("connectionService.onNetworkAvailable = {")
        assertTrue("the reconnect hook is gone", at >= 0)
        val hook = source.substring(at, source.indexOf("}", source.indexOf("reconnectNow", at)) + 1)
        assertTrue("onNetworkAvailable must check $gate", hook.contains(gate))
        assertTrue("$gate must come before reconnectNow", hook.indexOf(gate) < hook.indexOf("reconnectNow"))
    }

    // endregion

    // region a pause never stops reporting

    @Test
    fun pausingStopsOnlyThePullAndTheSend() {
        val gateway = PauseGateSources.functionBody(
            PauseGateSources.read("modules/gateway/GatewayService.kt"), "fun pausePulling("
        )
        // The events receiver turns state events (no replay) into durable reports: it must stay up.
        assertFalse(gateway.contains("eventsReceiver"))
        assertFalse(gateway.contains("InboxUploadWorker"))
        assertTrue(gateway.contains("PullMessagesWorker.stop"))
        assertTrue(gateway.contains("SSEForegroundService.stop"))

        val messages = PauseGateSources.functionBody(
            PauseGateSources.read("modules/messages/MessagesService.kt"), "fun pauseSending("
        )
        assertTrue(messages.contains("SendMessagesWorker.stop"))

        val orchestrator = PauseGateSources.functionBody(
            PauseGateSources.read("modules/orchestrator/OrchestratorService.kt"), "fun pause("
        )
        assertEquals(
            listOf("gatewaySvc.pausePulling(context)", "messagesSvc.pauseSending(context)"),
            orchestrator.lines().map { it.trim() }.filter { it.contains("Svc.") }
        )
        assertFalse(orchestrator.contains(".stop("))
    }

    // endregion
}
