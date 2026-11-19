package me.capcom.smsgateway.modules.gateway.workers

import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.gateway.GatewayApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class StateReportDedupTest {

    private fun report(
        id: String,
        state: ProcessingState,
        recipient: ProcessingState = state,
        error: String? = null,
        states: Map<ProcessingState, Date> = mapOf(state to Date(1_000)),
    ) = GatewayApi.MessagePatchRequest(
        id, state, listOf(GatewayApi.RecipientState("+100", recipient, error)), states
    )

    @Test
    fun theSameFinalStateIsPostedOnce() {
        val d = StateReportDedup()
        val sent = report("m1", ProcessingState.Sent)
        assertFalse(d.isDuplicate(sent))
        d.markPosted(sent)
        assertTrue(d.isDuplicate(report("m1", ProcessingState.Sent)))
    }

    @Test
    fun aFailedPostIsNotRecordedSoTheRetryStillPosts() {
        val d = StateReportDedup()
        val sent = report("m1", ProcessingState.Sent)
        assertFalse(d.isDuplicate(sent)) // the post then throws: markPosted is not called
        assertFalse(d.isDuplicate(sent))
    }

    @Test
    fun everyDistinctTransitionIsPosted() {
        val d = StateReportDedup()
        val steps = listOf(
            report("m1", ProcessingState.Processed),
            report("m1", ProcessingState.Sent, states = mapOf(ProcessingState.Processed to Date(1), ProcessingState.Sent to Date(2))),
            report("m1", ProcessingState.Delivered, states = mapOf(ProcessingState.Delivered to Date(3))),
        )
        for (s in steps) {
            assertFalse(d.isDuplicate(s))
            d.markPosted(s)
        }
        assertTrue(d.isDuplicate(steps.last()))
    }

    @Test
    fun aChangedRecipientErrorOrOtherIdIsNotADuplicate() {
        val d = StateReportDedup()
        d.markPosted(report("m1", ProcessingState.Failed, error = "a"))
        assertFalse(d.isDuplicate(report("m1", ProcessingState.Failed, error = "b")))
        assertFalse(d.isDuplicate(report("m2", ProcessingState.Failed, error = "a")))
    }

    @Test
    fun theMemoryIsBounded() {
        val d = StateReportDedup(maxEntries = 2)
        d.markPosted(report("a", ProcessingState.Sent))
        d.markPosted(report("b", ProcessingState.Sent))
        d.markPosted(report("c", ProcessingState.Sent))
        assertFalse(d.isDuplicate(report("a", ProcessingState.Sent))) // evicted: posts again, harmless
        assertTrue(d.isDuplicate(report("c", ProcessingState.Sent)))
    }
}
