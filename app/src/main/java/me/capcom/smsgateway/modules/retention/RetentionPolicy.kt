package me.capcom.smsgateway.modules.retention

import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.e2e.E2eOverride
import me.capcom.smsgateway.modules.account.AppLine

/**
 * CARD-24 (V2-L54): the phone is a relay, not an archive. Pure rules, no Android and no
 * database, so every window is pinned by a JVM test.
 *
 * Outgoing: the text goes as soon as every recipient is in a final state; the recipient number
 * goes 24 hours after its final state, or 72 hours after Sent while a delivery report may still
 * come; the bare message id stays [TOMBSTONE_MS] so a repeat of the same id is refused (the
 * enqueue refuses an id that already has a row).
 * Incoming: only SMS on a slot with an active line of the account are kept at all
 * ([IncomingSlotFilter]); the text goes at the server's acknowledgement, and at the latest
 * [INCOMING_TEXT_GRACE_MS] after the message (the local copy is display only; the queues that
 * deliver it are separate).
 */
object RetentionPolicy {
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 24 * HOUR_MS

    const val RECIPIENT_AFTER_FINAL_MS = 24 * HOUR_MS
    const val RECIPIENT_AFTER_SENT_MS = 72 * HOUR_MS
    const val TOMBSTONE_MS = 30 * DAY_MS

    const val INCOMING_TEXT_GRACE_MS = HOUR_MS
    const val INCOMING_PARTIES_MS = 24 * HOUR_MS
    const val INCOMING_ROW_MS = 30 * DAY_MS

    /** Queued deliveries (webhooks, inbox uploads) nobody acknowledged. */
    const val QUEUE_STALE_MS = 72 * HOUR_MS

    const val LOG_LIFETIME_DAYS = 7

    /** WorkManager's own floor is 15 minutes. */
    const val CLEANUP_INTERVAL_MINUTES = 60L

    /** The JSON a scrubbed message keeps in place of its content (parses to an empty content). */
    const val SCRUBBED_TEXT = """{"text":""}"""
    const val SCRUBBED_DATA = """{"data":"","port":0}"""
    const val SCRUBBED_MMS = """{"attachments":[]}"""

    private val TEXT_FINAL = setOf(
        ProcessingState.Sent,
        ProcessingState.Delivered,
        ProcessingState.Failed,
        ProcessingState.Cancelled,
    )

    fun isTextFinal(state: ProcessingState): Boolean = state in TEXT_FINAL

    /** The text may go once no recipient is left to send to. An empty list is not final. */
    fun textDeletable(recipientStates: Collection<ProcessingState>): Boolean =
        recipientStates.isNotEmpty() && recipientStates.all { it in TEXT_FINAL }

    fun recipientExpired(
        state: ProcessingState,
        stateAtMs: Long,
        withDeliveryReport: Boolean,
        nowMs: Long,
    ): Boolean {
        val age = nowMs - stateAtMs
        return when (state) {
            ProcessingState.Delivered,
            ProcessingState.Failed,
            ProcessingState.Cancelled -> age >= RECIPIENT_AFTER_FINAL_MS

            ProcessingState.Sent ->
                age >= if (withDeliveryReport) RECIPIENT_AFTER_SENT_MS else RECIPIENT_AFTER_FINAL_MS

            ProcessingState.Pending, ProcessingState.Processed -> false
        }
    }

    fun tombstoneExpired(finalAtMs: Long, nowMs: Long): Boolean = nowMs - finalAtMs >= TOMBSTONE_MS

    fun incomingTextExpired(createdAtMs: Long, nowMs: Long): Boolean =
        nowMs - createdAtMs >= INCOMING_TEXT_GRACE_MS

    fun incomingPartiesExpired(createdAtMs: Long, nowMs: Long): Boolean =
        nowMs - createdAtMs >= INCOMING_PARTIES_MS

    fun incomingRowExpired(createdAtMs: Long, nowMs: Long): Boolean =
        nowMs - createdAtMs >= INCOMING_ROW_MS

    fun queueEntryStale(createdAtMs: Long, nowMs: Long): Boolean =
        nowMs - createdAtMs >= QUEUE_STALE_MS

    /**
     * The `incoming_messages` row id of an acknowledged inbox upload. The upload id is
     * `hashCode().toUInt().toString(16)`; the row id is `"text:" + hashCode()`. Only SMS and
     * data SMS can be mapped; anything else (MMS) is left to the time window.
     */
    fun incomingIdForAck(type: String, messageIdHex: String): String? {
        val prefix = when (type) {
            "SMS" -> "text:"
            "DATA_SMS" -> "data:"
            else -> return null
        }
        val hash = messageIdHex.toLongOrNull(16)?.toInt() ?: return null
        return prefix + hash
    }
}

/** The retention clock. The only offset is the emulatorE2e build's time-shift hook (0 elsewhere). */
object RetentionClock {
    fun now(): Long = System.currentTimeMillis() + E2eOverride.retentionShiftMs
}

/** Which incoming SMS are kept and forwarded: those on a slot holding an active line. */
object IncomingSlotFilter {
    /** [simNumber] is the 1-based SIM number the receiver derives; unknown (null) is never accepted. */
    fun accept(simNumber: Int?, activeSlots: Set<Int>): Boolean =
        simNumber != null && simNumber in activeSlots

    /** Slots of the lines the platform proved: active, with a verification time. */
    fun activeSlots(lines: List<AppLine>): Set<Int> =
        lines.filter { it.isVerified }.mapNotNull { it.slot }.toSet()
}
