package me.capcom.smsgateway.modules.gateway

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.account.PauseStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** CARD V2-L23: the drop rule runs on the pulled message BEFORE it is enqueued. */
internal class PauseHoldPullTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private class Store(
        override var pausedAtMs: Long? = null,
        override var resumedAtMs: Long? = null,
        override var holdMaxMin: Int? = null,
    ) : PauseStore

    private fun message(createdAtMs: Long?): GatewayApi.Message =
        Gson().fromJson("""{"id":"m1","phoneNumbers":["+1000","+2000"]}""", GatewayApi.Message::class.java)
            .copy(createdAt = createdAtMs?.let { Date(it) })

    private suspend fun pull(message: GatewayApi.Message, store: Store): Pair<Boolean, GatewayApi.MessagePatchRequest?> {
        var sent: GatewayApi.MessagePatchRequest? = null
        val dropped = handlePauseHold(message, store, now) { sent = it }
        return dropped to sent
    }

    @Test
    fun aMessageHeldTooLongIsFailedAsTtlExpiredAndNeverEnqueued() = runBlocking {
        val store = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 10 * minute)

        val (dropped, patch) = pull(message(now - 120 * minute), store)

        assertTrue("the caller must return before the enqueue", dropped)
        val request = patch ?: throw AssertionError("expected a Failed patch")
        assertEquals("m1", request.id)
        assertEquals(ProcessingState.Failed, request.state)
        assertEquals(listOf("+1000", "+2000"), request.recipients.map { it.phoneNumber })
        assertTrue(request.recipients.all { it.state == ProcessingState.Failed && it.error == "TTL expired" })
        assertEquals(setOf(ProcessingState.Failed), request.states.keys)
    }

    @Test
    fun aMessageHeldForAShortTimeIsLeftToTheNormalPath() = runBlocking {
        val store = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 1 * minute)

        val (dropped, patch) = pull(message(now - 30 * minute), store)

        assertFalse(dropped)
        assertNull(patch)
    }

    @Test
    fun aMessageCreatedAfterTheResumeIsNeverDropped() = runBlocking {
        val store = Store(pausedAtMs = now - 500 * minute, resumedAtMs = now - 100 * minute)

        val (dropped, patch) = pull(message(now - 90 * minute), store)

        assertFalse(dropped)
        assertNull(patch)
    }

    @Test
    fun theHoldLimitComesFromThePlatformValue() = runBlocking {
        val store = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 10 * minute, holdMaxMin = 20)

        assertTrue(pull(message(now - 25 * minute), store).first)
        assertFalse(pull(message(now - 25 * minute), Store(store.pausedAtMs, store.resumedAtMs, 30)).first)
    }

    @Test
    fun aPhoneThatNeverPausedOrNoCreationTimeDropsNothing() = runBlocking {
        assertFalse(pull(message(now - 900 * minute), Store()).first)
        val paused = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 10 * minute)
        assertFalse(pull(message(null), paused).first)
    }

    // region the order: the drop runs BEFORE the enqueue

    private val calls = mutableListOf<String>()

    private suspend fun enqueueFlow(message: GatewayApi.Message, store: Store) =
        enqueueUnlessHeld(message, store, now, { calls.add("report:" + it.id) }) { calls.add("enqueue") }

    @Test
    fun aStaleMessageIsReportedAndNeverEnqueued() = runBlocking {
        val store = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 10 * minute)

        enqueueFlow(message(now - 120 * minute), store)

        assertEquals(listOf("report:m1"), calls)
    }

    @Test
    fun aFreshMessageIsEnqueuedAndNotReported() = runBlocking {
        val store = Store(pausedAtMs = now - 200 * minute, resumedAtMs = now - 10 * minute)

        enqueueFlow(message(now - 5 * minute), store)

        assertEquals(listOf("enqueue"), calls)
    }

    @Test
    fun processMessageEnqueuesOnlyThroughTheDropRule() {
        // GatewayService itself cannot be built in a JVM test: pin the wiring in its source.
        val source = PauseGateSources.read("modules/gateway/GatewayService.kt")
        val body = PauseGateSources.functionBody(source, "private suspend fun processMessage(")
        assertTrue("processMessage must go through enqueueUnlessHeld", body.contains("enqueueUnlessHeld("))
        assertFalse(
            "processMessage must not enqueue directly: the lambda is the only enqueue",
            body.replace(Regex("enqueueUnlessHeld\\([^}]*\\}"), "").contains("enqueueMessage(")
        )
        assertEquals(
            "one enqueue call in the whole service",
            1, Regex("messagesService\\.enqueueMessage\\(").findAll(source).count()
        )
    }

    // endregion
}
