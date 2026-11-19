package me.capcom.smsgateway.modules.retention

import me.capcom.smsgateway.domain.ProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD-24: the retention windows are the contract; each boundary is pinned here. */
class RetentionPolicyTest {
    private val h = RetentionPolicy.HOUR_MS
    private val t0 = 1_000_000_000_000L

    // ---- the text of an outgoing message

    @Test
    fun textGoesOnlyWhenEveryRecipientIsInAFinalState() {
        val finals = listOf(
            ProcessingState.Sent, ProcessingState.Delivered, ProcessingState.Failed,
            ProcessingState.Cancelled,
        )
        finals.forEach { assertTrue("$it", RetentionPolicy.textDeletable(listOf(it))) }
        assertTrue(RetentionPolicy.textDeletable(listOf(ProcessingState.Sent, ProcessingState.Failed)))
    }

    @Test
    fun textStaysWhileOneRecipientIsStillToBeSent() {
        assertFalse(RetentionPolicy.textDeletable(listOf(ProcessingState.Pending)))
        assertFalse(RetentionPolicy.textDeletable(listOf(ProcessingState.Processed)))
        assertFalse(
            RetentionPolicy.textDeletable(listOf(ProcessingState.Sent, ProcessingState.Pending))
        )
    }

    @Test
    fun aMessageWithoutRecipientsIsNeverTreatedAsFinal() {
        assertFalse(RetentionPolicy.textDeletable(emptyList()))
    }

    // ---- the recipient number

    @Test
    fun recipientIsKeptForTwentyFourHoursAfterDeliveredFailedOrCancelled() {
        listOf(ProcessingState.Delivered, ProcessingState.Failed, ProcessingState.Cancelled)
            .forEach {
                assertFalse("$it", RetentionPolicy.recipientExpired(it, t0, true, t0 + 24 * h - 1))
                assertTrue("$it", RetentionPolicy.recipientExpired(it, t0, true, t0 + 24 * h))
            }
    }

    @Test
    fun recipientWaitsSeventyTwoHoursAfterSentWhileNoDeliveryReportCame() {
        assertFalse(
            RetentionPolicy.recipientExpired(ProcessingState.Sent, t0, true, t0 + 72 * h - 1)
        )
        assertTrue(RetentionPolicy.recipientExpired(ProcessingState.Sent, t0, true, t0 + 72 * h))
        // 25 hours after Sent is NOT enough while a report may still arrive
        assertFalse(RetentionPolicy.recipientExpired(ProcessingState.Sent, t0, true, t0 + 25 * h))
    }

    @Test
    fun sentIsFinalWhenNoDeliveryReportWasAskedFor() {
        assertTrue(RetentionPolicy.recipientExpired(ProcessingState.Sent, t0, false, t0 + 24 * h))
        assertFalse(
            RetentionPolicy.recipientExpired(ProcessingState.Sent, t0, false, t0 + 24 * h - 1)
        )
    }

    @Test
    fun aRecipientStillToBeSentIsNeverExpired() {
        listOf(ProcessingState.Pending, ProcessingState.Processed).forEach {
            assertFalse("$it", RetentionPolicy.recipientExpired(it, t0, true, t0 + 1000 * h))
        }
    }

    // ---- the id tombstone

    @Test
    fun theBareIdIsKeptThirtyDays() {
        val day = 24 * h
        assertFalse(RetentionPolicy.tombstoneExpired(t0, t0 + 30 * day - 1))
        assertTrue(RetentionPolicy.tombstoneExpired(t0, t0 + 30 * day))
    }

    // ---- incoming

    @Test
    fun incomingTextAndPartiesHaveTheirOwnWindows() {
        assertFalse(RetentionPolicy.incomingTextExpired(t0, t0 + h - 1))
        assertTrue(RetentionPolicy.incomingTextExpired(t0, t0 + h))
        assertFalse(RetentionPolicy.incomingPartiesExpired(t0, t0 + 24 * h - 1))
        assertTrue(RetentionPolicy.incomingPartiesExpired(t0, t0 + 24 * h))
        assertFalse(RetentionPolicy.incomingRowExpired(t0, t0 + 30 * 24 * h - 1))
        assertTrue(RetentionPolicy.incomingRowExpired(t0, t0 + 30 * 24 * h))
    }

    @Test
    fun anUnacknowledgedQueueEntryIsDroppedAfterSeventyTwoHours() {
        assertFalse(RetentionPolicy.queueEntryStale(t0, t0 + 72 * h - 1))
        assertTrue(RetentionPolicy.queueEntryStale(t0, t0 + 72 * h))
    }

    @Test
    fun logsKeepSevenDays() {
        assertEquals(7, RetentionPolicy.LOG_LIFETIME_DAYS)
    }

    @Test
    fun theCleanupRunsAtLeastHourly() {
        assertTrue(RetentionPolicy.CLEANUP_INTERVAL_MINUTES <= 60L)
        assertTrue(RetentionPolicy.CLEANUP_INTERVAL_MINUTES >= 15L) // WorkManager floor
    }

    // ---- inbox ack to the incoming row id

    @Test
    fun anInboxAckMapsToTheIncomingRowId() {
        // InboxUploadService uses hashCode().toUInt().toString(16); IncomingMessagesService uses
        // "text:" + hashCode().toString()
        val hash = -1234567
        val hex = hash.toUInt().toString(16)
        assertEquals("text:$hash", RetentionPolicy.incomingIdForAck("SMS", hex))
        assertEquals("data:$hash", RetentionPolicy.incomingIdForAck("DATA_SMS", hex))
        assertEquals("text:42", RetentionPolicy.incomingIdForAck("SMS", "2a"))
    }

    @Test
    fun anAckOfAnMmsOrABadIdMapsToNothing() {
        assertNull(RetentionPolicy.incomingIdForAck("MMS_DOWNLOADED", "2a"))
        assertNull(RetentionPolicy.incomingIdForAck("SMS", "not-hex"))
    }
}
