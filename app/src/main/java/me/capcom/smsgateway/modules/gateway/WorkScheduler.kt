package me.capcom.smsgateway.modules.gateway

import android.content.Context
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.gateway.workers.SendStateWorker
import me.capcom.smsgateway.modules.gateway.workers.SettingsUpdateWorker
import me.capcom.smsgateway.modules.gateway.workers.WebhooksUpdateWorker
import me.capcom.smsgateway.modules.messages.workers.SendMessagesWorker
import me.capcom.smsgateway.modules.webhooks.workers.WebhookQueueProcessorWorker
import org.koin.dsl.module

/**
 * CARD-21: the one door through which the pipeline (pull, send, state report, webhooks)
 * asks for background work.
 *
 * - unlocked: [WorkManagerScheduler], exactly the workers used before;
 * - locked (before the first unlock): [InProcessScheduler]; WorkManager cannot run then.
 *
 * [ModeAwareScheduler] picks one per call, so a process that started locked switches
 * to WorkManager by itself once the user unlocks.
 */
interface WorkScheduler {
    /** Pull the pending messages of the gateway now (and keep the periodic pull in WorkManager mode). */
    fun pullMessages()

    /**
     * Run the pending-messages sender. [scheduleAt] is epoch ms; a past time or
     * [me.capcom.smsgateway.modules.messages.workers.SendMessagesWorker.IMMEDIATE] means now.
     * [force] replaces an already scheduled run (WorkManager: REPLACE, otherwise KEEP).
     */
    fun sendMessages(force: Boolean, scheduleAt: Long)

    /** Report the current state of one message to the gateway. */
    fun sendState(messageId: String)

    /** Process the device webhook queue. */
    fun processWebhookQueue(internetRequired: Boolean)

    /** Refresh the webhooks list from the gateway. */
    fun updateWebhooks()

    /** Refresh the settings from the gateway. */
    fun updateSettings()
}

/** The behaviour before CARD-21: one WorkManager worker per request. */
class WorkManagerScheduler(private val context: Context) : WorkScheduler {
    override fun pullMessages() = PullMessagesWorker.start(context)

    override fun sendMessages(force: Boolean, scheduleAt: Long) =
        SendMessagesWorker.start(context, force, scheduleAt)

    override fun sendState(messageId: String) = SendStateWorker.start(context, messageId)

    override fun processWebhookQueue(internetRequired: Boolean) =
        WebhookQueueProcessorWorker.start(context, internetRequired)

    override fun updateWebhooks() = WebhooksUpdateWorker.start(context)

    override fun updateSettings() = SettingsUpdateWorker.start(context)
}

/**
 * Routes each call by the current lock state. The providers are lazy: while locked the
 * WorkManager scheduler is never even created, so no WorkManager call can happen.
 */
class ModeAwareScheduler(
    private val isUnlocked: () -> Boolean,
    private val workManager: () -> WorkScheduler,
    private val inProcess: () -> WorkScheduler,
) : WorkScheduler {
    private val target: WorkScheduler
        get() = if (isUnlocked()) workManager() else inProcess()

    override fun pullMessages() = target.pullMessages()

    override fun sendMessages(force: Boolean, scheduleAt: Long) =
        target.sendMessages(force, scheduleAt)

    override fun sendState(messageId: String) = target.sendState(messageId)

    override fun processWebhookQueue(internetRequired: Boolean) =
        target.processWebhookQueue(internetRequired)

    override fun updateWebhooks() = target.updateWebhooks()

    override fun updateSettings() = target.updateSettings()
}

val workSchedulerModule = module {
    single<WorkScheduler> {
        val context = get<Context>()
        ModeAwareScheduler(
            isUnlocked = { DeviceStorage.isUnlocked(context) },
            workManager = { WorkManagerScheduler(context) },
            inProcess = { get<InProcessScheduler>() },
        )
    }
    single { LockedRunner(get(), get(), get()) }
    single<LockedTasks> { get<LockedRunner>() }
    single { InProcessScheduler.create { get<LockedTasks>() } }
}
