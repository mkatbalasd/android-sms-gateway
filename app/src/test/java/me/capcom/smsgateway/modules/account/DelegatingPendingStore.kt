package me.capcom.smsgateway.modules.account

/**
 * Test base for in-memory [PendingStore] fakes: failIfPending is a plain fail and the send
 * gate is never held. [PendingStore] itself has no such defaults, so a production store
 * cannot inherit them by accident.
 */
internal abstract class DelegatingPendingStore : PendingStore {
    override suspend fun failIfPending(id: String, reason: String): Boolean {
        fail(id, reason)
        return true
    }

    override suspend fun <T> whenNotSending(block: suspend () -> T): T? = block()
}
