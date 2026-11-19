package me.capcom.smsgateway.data.entities

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation

data class MessageWithRecipients(
    @Embedded val message: Message,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val recipients: List<MessageRecipient>,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val states: List<MessageState> = emptyList(),
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
) {
    val state: me.capcom.smsgateway.domain.ProcessingState
        get() = when {
            // CARD-24: the recipients were deleted after their retention window; the stored
            // state is all that is left (an empty list would otherwise read as "all Failed")
            recipients.isEmpty() ->
                if (me.capcom.smsgateway.modules.retention.RetentionPolicy.isTextFinal(message.state))
                    message.state
                else
                    me.capcom.smsgateway.domain.ProcessingState.Failed
recipients.any { it.state == me.capcom.smsgateway.domain.ProcessingState.Pending } -> me.capcom.smsgateway.domain.ProcessingState.Pending
            recipients.any { it.state == me.capcom.smsgateway.domain.ProcessingState.Cancelled } -> me.capcom.smsgateway.domain.ProcessingState.Cancelled
            recipients.any { it.state == me.capcom.smsgateway.domain.ProcessingState.Processed } -> me.capcom.smsgateway.domain.ProcessingState.Processed

            recipients.all { it.state == me.capcom.smsgateway.domain.ProcessingState.Failed } -> me.capcom.smsgateway.domain.ProcessingState.Failed
            recipients.all { it.state == me.capcom.smsgateway.domain.ProcessingState.Delivered } -> me.capcom.smsgateway.domain.ProcessingState.Delivered
            else -> me.capcom.smsgateway.domain.ProcessingState.Sent
        }
}
