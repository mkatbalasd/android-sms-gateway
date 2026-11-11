package me.capcom.smsgateway.modules.gateway

import android.content.Context
import me.capcom.smsgateway.modules.account.StateReporter
import me.capcom.smsgateway.modules.gateway.workers.SendStateWorker

/**
 * V2-L23: reports a local message state through the same durable WorkManager job as every
 * other state report (retries, network constraint), whatever the pause state is.
 */
class WorkerStateReporter(private val context: Context) : StateReporter {
    override fun report(messageId: String) {
        SendStateWorker.start(context, messageId)
    }
}
