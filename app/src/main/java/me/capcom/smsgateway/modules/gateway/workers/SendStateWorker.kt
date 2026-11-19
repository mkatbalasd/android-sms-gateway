package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.messages.MessagesService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Date
import java.util.concurrent.TimeUnit

class SendStateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val messagesService: MessagesService by inject()
    private val gatewayService: GatewayService by inject()
    override suspend fun doWork(): Result {
        try {
            // success, not failure: a failure would fail the reports queued behind this one
            val messageId = inputData.getString(MESSAGE_ID) ?: return Result.success()
            val message = messagesService.getMessage(messageId) ?: return Result.success()

            val request = GatewayApi.MessagePatchRequest(
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
            // V2-L47: every state change enqueues a run and each run posts the CURRENT state, so
            // a late run for an earlier change would post the same final state a second time.
            if (StateReportDedup.shared.isDuplicate(request)) return Result.success()

            val sent = withContext(Dispatchers.IO) { gatewayService.sendState(request) }
            // nothing was sent (device not registered): do not remember it as posted
            if (sent) StateReportDedup.shared.markPosted(request)
            return Result.success()
        } catch (th: Throwable) {
            th.printStackTrace()
            return if (shouldRetry(this.runAttemptCount)) {
                Result.retry()
            } else {
                // Result.failure() would also mark every report queued behind this one on the
                // same unique chain as FAILED without an attempt, losing a later Failed/Delivered
                // report. Give up on THIS report only (id logged, no payload) and let the next
                // one make its own attempts.
                Log.w("SendStateWorker", "giving up a state report for message ${inputData.getString(MESSAGE_ID)}")
                Result.success()
            }
        }
    }

    companion object {
        private const val RETRY_COUNT = 10

        /** Retry-exhaustion decision: true while another attempt is allowed. */
        internal fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < RETRY_COUNT

        private const val MESSAGE_ID = "messageId"

        fun start(context: Context, messageId: String) {
            val work = OneTimeWorkRequestBuilder<SendStateWorker>()
                .setInputData(workDataOf(MESSAGE_ID to messageId))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // V2-L47: one chain per message id. APPEND_OR_REPLACE runs the reports of one id one
            // after the other (never two at once, which is what let two runs post the same state
            // together) and keeps every queued report, so each state change is still reported in
            // order. doWork never returns failure (it gives up with success after the last retry), because a
            // failed link would mark every report queued behind it as failed unattempted. Repeated identical state
            // is dropped in doWork by StateReportDedup.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("send-state:$messageId", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}