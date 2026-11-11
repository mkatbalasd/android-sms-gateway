package me.capcom.smsgateway.modules.webhooks.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.notifications.NotificationsService
import me.capcom.smsgateway.modules.webhooks.NAME
import me.capcom.smsgateway.modules.webhooks.WebhookQueueProcessor
import me.capcom.smsgateway.modules.webhooks.WebhooksSettings
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Worker that processes webhook events from a persistent queue.
 * This replaces the per-event worker approach with a single persistent worker
 * that handles multiple events with priority-based processing.
 *
 * The queue logic lives in [WebhookQueueProcessor] (CARD-21), which the in-process
 * runner also uses before the first unlock; this class only adds WorkManager
 * (foreground info, rescheduling).
 */
class WebhookQueueProcessorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val notificationsSvc: NotificationsService by inject()
    private val logsSvc: LogsService by inject()
    private val settings: WebhooksSettings by inject()

    companion object {
        private const val MIN_BACKOFF_DELAY_MS = 5000L

        // Work name for this worker
        private const val WORK_NAME = "webhook_queue_processor"

        /**
         * Start the queue processor worker.
         * @param initialDelayMs delay before the worker runs (0 for immediate).
         */
        fun start(
            context: Context,
            internetRequired: Boolean = false,
            initialDelayMs: Long = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val workRequest = OneTimeWorkRequestBuilder<WebhookQueueProcessorWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    MIN_BACKOFF_DELAY_MS,
                    TimeUnit.MILLISECONDS
                )
                .apply {
                    if (internetRequired) {
                        setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                    }
                    if (initialDelayMs > 0) {
                        setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    }
                }
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                policy,
                workRequest
            )
        }

        /**
         * Stop any running queue processor workers.
         */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val processor = get<WebhookQueueProcessor>()
        try {
            logsSvc.insert(
                priority = LogEntry.Priority.DEBUG,
                module = NAME,
                message = "Starting webhook queue processing",
                context = mapOf(
                    "attempt" to runAttemptCount,
                )
            )

            // Get foreground info early to ensure proper service startup
            try {
                setForeground(getForegroundInfo())
            } catch (e: Exception) {
                logsSvc.insert(
                    priority = LogEntry.Priority.WARN,
                    module = NAME,
                    message = "Failed to set foreground: ${e.message}",
                    context = mapOf("error" to e.toString())
                )
            }

            val delayMs = processor.drain()

            if (isActive && delayMs != null) {
                logsSvc.insert(
                    priority = LogEntry.Priority.DEBUG,
                    module = NAME,
                    message = "Scheduling next webhook queue processing",
                    context = mapOf(
                        "delayMs" to delayMs,
                    )
                )

                start(
                    context = applicationContext,
                    internetRequired = settings.internetRequired,
                    initialDelayMs = delayMs,
                    policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                )
            }

            logsSvc.insert(
                priority = LogEntry.Priority.INFO,
                module = NAME,
                message = "Webhook queue processing completed",
                context = mapOf(
                    "attempt" to runAttemptCount,
                )
            )

            return@withContext Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logsSvc.insert(
                priority = LogEntry.Priority.ERROR,
                module = NAME,
                message = "Webhook queue processing failed: ${e.message}",
                context = mapOf(
                    "error" to e.toString(),
                    "stackTrace" to e.stackTraceToString()
                )
            )

            // Use linear backoff for retries
            return@withContext Result.retry()
        } finally {
            processor.close()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationId = NotificationsService.NOTIFICATION_ID_WEBHOOK_WORKER
        val notification = notificationsSvc.makeNotification(
            applicationContext,
            notificationId,
            applicationContext.getString(R.string.processing_webhook_queue)
        )

        return ForegroundInfo(notificationId, notification)
    }
}
