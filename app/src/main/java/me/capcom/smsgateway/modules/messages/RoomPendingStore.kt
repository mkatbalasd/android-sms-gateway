package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.data.dao.MessagesDao
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.modules.account.PendingStamp
import me.capcom.smsgateway.modules.account.PendingStore

/** V2-L23: the local queue of pending messages, read and failed through the same state machine as every other transition. */
class RoomPendingStore(
    private val dao: MessagesDao,
    private val machine: MessageStateService,
) : PendingStore {

    override suspend fun pending(): List<PendingStamp> = dao.selectPendingStamps()

    override suspend fun fail(id: String, reason: String) {
        machine.transitionRecipients(id, ProcessingState.Failed, reason)
    }
}
