package me.capcom.smsgateway.modules.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * CARD-24: the periodic clean-up (unique, hourly) and the one run at every full start.
 * No network and no battery constraint: deleting data must not wait for either.
 */
class RetentionWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val retention: RetentionService by inject()

    override suspend fun doWork(): Result = try {
        retention.refreshActiveLines()
        retention.runCleanup()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.retry()
    }

    companion object {
        const val PERIODIC_NAME = "RetentionWorker"
        const val NOW_NAME = "RetentionWorkerNow"

        fun start(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<RetentionWorker>(
                RetentionPolicy.CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
            wm.enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RetentionWorker>().build(),
            )
        }
    }
}
