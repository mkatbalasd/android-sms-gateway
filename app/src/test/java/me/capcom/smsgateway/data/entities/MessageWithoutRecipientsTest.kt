package me.capcom.smsgateway.data.entities

import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.ProcessingState
import org.junit.Assert.assertEquals
import org.junit.Test

/** CARD-24: after the recipients are deleted the stored state is what the message reports. */
class MessageWithoutRecipientsTest {
    private fun message(state: ProcessingState) = Message(
        id = "m1",
        withDeliveryReport = true,
        simNumber = null,
        validUntil = null,
        scheduleAt = null,
        isEncrypted = false,
        skipPhoneValidation = false,
        priority = 0,
        source = EntitySource.Cloud,
        content = """{"text":""}""",
        state = state,
    )

    @Test
    fun aDeliveredMessageWithoutRecipientsStaysDelivered() {
        val row = MessageWithRecipients(message(ProcessingState.Delivered), emptyList())
        assertEquals(ProcessingState.Delivered, row.state)
    }

    @Test
    fun aSentMessageWithoutRecipientsStaysSent() {
        val row = MessageWithRecipients(message(ProcessingState.Sent), emptyList())
        assertEquals(ProcessingState.Sent, row.state)
    }

    @Test
    fun aPendingMessageWithoutRecipientsStillReadsAsFailed() {
        val row = MessageWithRecipients(message(ProcessingState.Pending), emptyList())
        assertEquals(ProcessingState.Failed, row.state)
    }

    @Test
    fun recipientsStillDecideWhenThereAreSome() {
        val row = MessageWithRecipients(
            message(ProcessingState.Sent),
            listOf(MessageRecipient("m1", "+1", ProcessingState.Pending)),
        )
        assertEquals(ProcessingState.Pending, row.state)
    }
}
