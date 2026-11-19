package me.capcom.smsgateway.modules.retention

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.data.dao.RetentionDao
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.gateway.inbox.InboxUploadEntity
import me.capcom.smsgateway.modules.mms.MmsAttachmentStorage
import me.capcom.smsgateway.modules.webhooks.WebhookPayloadStorage
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * CARD-24: deletes what the phone no longer needs (see [RetentionPolicy]).
 *
 * Immediate: [onMessageStateChanged] (a final state), [onInboxAcknowledged] (the server has the
 * SMS). Periodic: [runCleanup], from [RetentionWorker] (at least hourly, only while the user is
 * unlocked: WorkManager lives in credential-encrypted storage) and at every full start.
 * Everything here works on device-protected storage, so the immediate part also runs while the
 * phone is locked.
 *
 * Nothing here logs a number or a text. A failing step never stops the others.
 */
class RetentionService(
    private val context: Context,
    private val dao: RetentionDao,
    private val lineStore: ActiveLineStore,
) : KoinComponent {
    // Resolved only when used: the heavy ones need credential-encrypted storage or the session.
    private val db: AppDatabase by lazy { get<AppDatabase>() }
    private val payloads: WebhookPayloadStorage by lazy { get<WebhookPayloadStorage>() }
    private val attachments: MmsAttachmentStorage by lazy { get<MmsAttachmentStorage>() }
    private val appClient: AppClient by lazy { get<AppClient>() }

    /** What a run changed, counts only. */
    data class Summary(
        var texts: Int = 0,
        var recipients: Int = 0,
        var tombstones: Int = 0,
        var incoming: Int = 0,
        var queues: Int = 0,
        var rollbackCopies: Int = 0,
        var walTruncated: Boolean = false,
        var failedSteps: Int = 0,
    )

    /** A recipient reached a state: when every recipient is final the text goes now. */
    fun onMessageStateChanged(id: String) {
        try {
            dao.scrubFinalContent(
                id,
                RetentionPolicy.SCRUBBED_TEXT,
                RetentionPolicy.SCRUBBED_DATA,
                RetentionPolicy.SCRUBBED_MMS,
            )
        } catch (e: Exception) {
            Log.w(TAG, "text clean-up failed: ${e.javaClass.simpleName}")
        }
    }

    /** The server acknowledged these inbox uploads: the local copy of their text goes now. */
    fun onInboxAcknowledged(entities: List<InboxUploadEntity>) {
        for (entity in entities) {
            try {
                RetentionPolicy.incomingIdForAck(entity.type, entity.messageId)
                    ?.let { dao.scrubIncomingTextById(it) }
            } catch (e: Exception) {
                Log.w(TAG, "incoming clean-up failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Asks the platform which lines are active (the hook of the client records them). */
    suspend fun refreshActiveLines() {
        if (!DeviceStorage.isUnlocked(context)) return
        try {
            appClient.lines()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // not linked, offline or signed out: what is known stays
        }
    }

    fun runCleanup(now: Long = RetentionClock.now()): Summary {
        val summary = Summary()
        fun step(name: String, block: () -> Int): Int = try {
            block()
        } catch (e: Exception) {
            summary.failedSteps++
            Log.w(TAG, "clean-up step $name failed: ${e.javaClass.simpleName}")
            0
        }

        summary.texts = step("text") {
            dao.scrubFinalContent(
                null,
                RetentionPolicy.SCRUBBED_TEXT,
                RetentionPolicy.SCRUBBED_DATA,
                RetentionPolicy.SCRUBBED_MMS,
            )
        }

        summary.recipients = step("recipients") {
            val expired = dao.selectFinalRecipients().filter {
                val state = runCatching { ProcessingState.valueOf(it.state) }.getOrNull()
                    ?: return@filter false
                RetentionPolicy.recipientExpired(state, it.stateAt, it.withDeliveryReport, now)
            }
            dao.deleteRecipients(expired)
            expired.size
        }

        summary.tombstones = step("tombstones") {
            val expired = dao.selectTombstones()
                .filter { RetentionPolicy.tombstoneExpired(it.finalAt, now) }
            expired.forEach { dao.deleteMessage(it.id) }
            expired.size
        }

        summary.incoming = step("incoming") {
            dao.scrubIncomingText(now - RetentionPolicy.INCOMING_TEXT_GRACE_MS) +
                    dao.scrubIncomingParties(now - RetentionPolicy.INCOMING_PARTIES_MS) +
                    dao.deleteIncoming(now - RetentionPolicy.INCOMING_ROW_MS)
        }

        summary.queues = step("queues") {
            val stale = now - RetentionPolicy.QUEUE_STALE_MS
            val ids = dao.selectExpiredWebhookIds(now - RetentionPolicy.RECIPIENT_AFTER_FINAL_MS, stale)
            ids.forEach { payloads.delete(it) }
            ids.chunked(500).forEach { dao.deleteWebhooks(it) }
            ids.size + dao.deleteStaleInboxUploads(stale)
        }

        if (DeviceStorage.isUnlocked(context)) {
            step("attachments") {
                attachments.cleanup(1)
                0
            }
            summary.rollbackCopies = step("rollback") { DeviceStorage.removeRollbackCopies(context) }
        }

        step("checkpoint") {
            summary.walTruncated = truncateWal()
            0
        }
        return summary
    }

    /**
     * Folds the write-ahead log into the database file and empties it, so nothing deleted above
     * stays in the log. A reader can hold it up for a moment: retried a few times.
     */
    private fun truncateWal(): Boolean {
        val database = db.openHelper.writableDatabase
        repeat(WAL_TRIES) { attempt ->
            val busy = database.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                if (it.moveToFirst()) it.getInt(0) else 1
            }
            if (busy == 0) return true
            if (attempt < WAL_TRIES - 1) Thread.sleep(WAL_RETRY_MS)
        }
        return false
    }

    private companion object {
        const val TAG = "Retention"
        const val WAL_TRIES = 5
        const val WAL_RETRY_MS = 200L
    }
}
