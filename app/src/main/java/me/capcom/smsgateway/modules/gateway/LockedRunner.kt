package me.capcom.smsgateway.modules.gateway

import android.content.Context
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.webhooks.WebhookQueueProcessor
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.Date

/**
 * CARD-21: the real work of the in-process runner, before the first unlock. Each task is
 * the body of the matching worker, without WorkManager:
 *
 * - [pull]: `PullMessagesWorker` (gateway token, device-protected storage);
 * - [sendPending]: `SendMessagesWorker` (SMS only; see [MessagesService.sendPendingMessages]);
 * - [sendState]: `SendStateWorker`;
 * - [drainWebhooks]: `WebhookQueueProcessorWorker` (signed with the device-protected signing key).
 *
 * Nothing here reads the account store. The pause is read from the device-protected
 * [me.capcom.smsgateway.modules.account.DevicePauseStore] (through `PauseGate`, which fails
 * closed): a pull ([GatewayService.getNewMessages], then `enqueueUnlessHeld`) and a send
 * both refuse while paused or while the pause state cannot be read.
 */
class LockedRunner(
    private val context: Context,
    private val gatewayService: GatewayService,
    private val messagesService: MessagesService,
) : LockedTasks, KoinComponent {

    override suspend fun pull() {
        gatewayService.getNewMessages(context)
    }

    override suspend fun sendPending() {
        // The body of SendMessagesWorker skips this check on its own; here it is the gate.
        if (me.capcom.smsgateway.modules.account.PauseGate.isPaused()) return
        messagesService.sendPendingMessages()
    }

    override suspend fun sendState(messageId: String) {
        val message = messagesService.getMessage(messageId) ?: return

        gatewayService.sendState(
            GatewayApi.MessagePatchRequest(
                message.message.id,
                message.message.state,
                message.recipients.map {
                    GatewayApi.RecipientState(
                        it.phoneNumber,
                        it.state,
                        it.error
                    )
                },
                message.states.associate { it.state to Date(it.updatedAt) },
            )
        )
    }

    override suspend fun drainWebhooks(): Long? {
        val processor = get<WebhookQueueProcessor>()
        try {
            return processor.drain()
        } finally {
            processor.close()
        }
    }
}
