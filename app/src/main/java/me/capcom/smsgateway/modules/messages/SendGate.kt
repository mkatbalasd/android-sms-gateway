package me.capcom.smsgateway.modules.messages

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CARD-21: one send loop at a time, in the whole process.
 *
 * At the unlock handover two loops can exist: the in-process one (started while the user
 * is locked) and the WorkManager one (started by the full start). Both read the oldest
 * pending message first and mark it only after the send, so without a gate the same
 * message is sent twice. Both loops run in this one process, so a process-wide mutex is
 * the claim: the second loop waits, then finds nothing left to send.
 *
 * Released on normal return, on exception and on cancellation (`Mutex.withLock`).
 */
class SendGate {
    private val mutex = Mutex()

    val isHeld: Boolean
        get() = mutex.isLocked

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
