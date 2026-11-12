package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.data.dao.MessagesDao
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.account.PendingStamp
import me.capcom.smsgateway.modules.account.PendingStore

/** V2-L23: the local queue of pending messages, read and failed through the same state machine as every other transition. */
class RoomPendingStore(
    private val dao: MessagesDao,
    private val machine: MessageStateService,
    /** The send loop's gate; looked up lazily (the messages service is created after this store). */
    private val sendGate: () -> SendGate,
) : PendingStore {

    override suspend fun pending(): List<PendingStamp> = dao.selectPendingStamps()

    override suspend fun fail(id: String, reason: String) {
        machine.transitionRecipients(id, ProcessingState.Failed, reason)
    }

    override suspend fun failIfPending(id: String, reason: String): Boolean =
        machine.failIfPending(id, reason)

    /** Skipped (null) while a send is in progress: a message mid-submit is still Pending. */
    override suspend fun <T> whenNotSending(block: suspend () -> T): T? =
        sendGate().exclusiveOrNull(block)
}
