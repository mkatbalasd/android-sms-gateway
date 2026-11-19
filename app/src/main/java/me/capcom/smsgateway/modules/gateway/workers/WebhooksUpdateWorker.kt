package me.capcom.smsgateway.modules.gateway.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.retention.ActiveLineStore
import me.capcom.smsgateway.modules.retention.PlatformRole
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookDTO
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

class WebhooksUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val gatewaySvc: GatewayService = get()
        val webhookSvc: WebHooksService = get()

        try {
            val pulled = gatewaySvc.getWebHooks()
            // The platform-phone role comes only from this pull (a device-scoped marker hook);
            // it is set before the sync, so a database failure there cannot leave it stale. A
            // failed pull throws above this line and keeps the last value.
            PlatformRole.apply(get<ActiveLineStore>(), get<GatewaySettings>().deviceId) {
                pulled.map { PlatformRole.Hook(it.event, it.url, it.deviceId, EntitySource.Cloud) }
            }
            webhookSvc.sync(EntitySource.Cloud, pulled.map { it.toDTO() })
        } catch (th: Throwable) {
            th.printStackTrace()
            return Result.retry()
        }
        return Result.success()
    }

    private fun GatewayApi.WebHook.toDTO(): WebHookDTO {
        return WebHookDTO(
            id = id,
            deviceId = null,
            url = url,
            event = event,
            source = EntitySource.Cloud,
        )
    }

    companion object {
        private const val NAME = "WebhooksUpdateWorker"

        fun start(context: Context) {
            val work = PeriodicWorkRequestBuilder<WebhooksUpdateWorker>(
                24,
                TimeUnit.HOURS
            )
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
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    work
                )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(NAME)
        }
    }
}