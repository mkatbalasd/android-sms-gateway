package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.account.StateReporter

/**
 * V2-L23 / CARD-21 round 7: reports a local message state through [WorkScheduler.sendState],
 * the one door of the pipeline: a durable WorkManager job (retries, network constraint)
 * once the user is unlocked, the in-process runner before the first unlock (WorkManager
 * cannot run then). Whatever the pause state is.
 */
class WorkerStateReporter(private val scheduler: WorkScheduler) : StateReporter {
    override fun report(messageId: String) {
        scheduler.sendState(messageId)
    }
}
