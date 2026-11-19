package me.capcom.smsgateway.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** A recipient in a final state, with the time it got there (CARD-24). */
data class RecipientRetentionRow(
    val messageId: String,
    val phoneNumber: String,
    val state: String,
    val withDeliveryReport: Boolean,
    val stateAt: Long,
)

/** A message whose recipients are all gone: only the bare id is left. */
data class TombstoneRow(
    val id: String,
    val finalAt: Long,
)

/**
 * CARD-24: the queries of the retention clean-up. A separate DAO on purpose: the state
 * machine's [MessagesDao] keeps its contract (and its test fakes).
 */
@Dao
interface RetentionDao {
    /**
     * Replaces the content of every message whose recipients are ALL in a final state
     * (Sent, Delivered, Failed, Cancelled) by an empty content of the same type; [id] null
     * means every message. A message with a recipient still to be sent is never touched.
     * The row stays (state, ids, times): it is the repeat guard.
     */
    @Query(
        """
        UPDATE message SET content = CASE type WHEN 'Data' THEN :data WHEN 'Mms' THEN :mms ELSE :text END
        WHERE (:id IS NULL OR id = :id)
          AND EXISTS (SELECT 1 FROM messagerecipient r WHERE r.messageId = message.id)
          AND NOT EXISTS (
              SELECT 1 FROM messagerecipient r
              WHERE r.messageId = message.id
                AND r.state NOT IN ('Sent', 'Delivered', 'Failed', 'Cancelled'))
          AND content <> CASE type WHEN 'Data' THEN :data WHEN 'Mms' THEN :mms ELSE :text END
        """
    )
    fun scrubFinalContent(id: String?, text: String, data: String, mms: String): Int

    @Query(
        """
        SELECT r.messageId AS messageId, r.phoneNumber AS phoneNumber, r.state AS state,
               m.withDeliveryReport AS withDeliveryReport,
               COALESCE(
                   (SELECT MAX(s.updatedAt) FROM recipientstate s
                    WHERE s.messageId = r.messageId AND s.phoneNumber = r.phoneNumber
                      AND s.state = r.state),
                   m.createdAt) AS stateAt
        FROM messagerecipient r JOIN message m ON m.id = r.messageId
        WHERE r.state IN ('Sent', 'Delivered', 'Failed', 'Cancelled')
        """
    )
    fun selectFinalRecipients(): List<RecipientRetentionRow>

    /** Deletes the recipient and, by cascade, its state history (which holds the number too). */
    @Query("DELETE FROM messagerecipient WHERE messageId = :messageId AND phoneNumber = :phoneNumber")
    fun deleteRecipient(messageId: String, phoneNumber: String)

    @Query(
        """
        SELECT m.id AS id,
               COALESCE(
                   (SELECT MAX(s.updatedAt) FROM messagestate s
                    WHERE s.messageId = m.id
                      AND s.state IN ('Sent', 'Delivered', 'Failed', 'Cancelled')),
                   m.createdAt) AS finalAt
        FROM message m
        WHERE m.state IN ('Sent', 'Delivered', 'Failed', 'Cancelled')
          AND NOT EXISTS (SELECT 1 FROM messagerecipient r WHERE r.messageId = m.id)
        """
    )
    fun selectTombstones(): List<TombstoneRow>

    @Query("DELETE FROM message WHERE id = :id")
    fun deleteMessage(id: String)

    @Transaction
    fun deleteRecipients(rows: List<RecipientRetentionRow>) {
        rows.forEach { deleteRecipient(it.messageId, it.phoneNumber) }
    }

    // ---- incoming

    @Query("UPDATE incoming_messages SET contentPreview = '' WHERE id = :id AND contentPreview <> ''")
    fun scrubIncomingTextById(id: String): Int

    @Query("UPDATE incoming_messages SET contentPreview = '' WHERE contentPreview <> '' AND createdAt <= :before")
    fun scrubIncomingText(before: Long): Int

    @Query(
        """
        UPDATE incoming_messages SET sender = '', recipient = NULL
        WHERE (sender <> '' OR recipient IS NOT NULL) AND createdAt <= :before
        """
    )
    fun scrubIncomingParties(before: Long): Int

    @Query("DELETE FROM incoming_messages WHERE createdAt <= :before")
    fun deleteIncoming(before: Long): Int

    // ---- queues nobody acknowledged

    @Query("DELETE FROM gateway__inbox_upload WHERE message_created_at <= :before")
    fun deleteStaleInboxUploads(before: Long): Int

    @Query(
        """
        SELECT id FROM webhook_queue
        WHERE (status IN ('completed', 'permanently_failed') AND created_at <= :finalBefore)
           OR (status NOT IN ('completed', 'permanently_failed') AND created_at <= :staleBefore)
        """
    )
    fun selectExpiredWebhookIds(finalBefore: Long, staleBefore: Long): List<String>

    @Query("DELETE FROM webhook_queue WHERE id IN (:ids)")
    fun deleteWebhooks(ids: List<String>)
}
