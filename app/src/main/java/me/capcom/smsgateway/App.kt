package me.capcom.smsgateway

import android.app.Application
import android.content.Context
import healthModule
import me.capcom.smsgateway.data.dbModule
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.helpers.MigrationFollowUp
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.orchestrator.LockedMode
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.connection.connectionModule
import me.capcom.smsgateway.modules.device.deviceModule
import me.capcom.smsgateway.modules.encryption.encryptionModule
import me.capcom.smsgateway.modules.events.eventBusModule
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.incoming.incomingModule
import me.capcom.smsgateway.modules.localserver.LocalServerSettings
import me.capcom.smsgateway.modules.localserver.localserverModule
import me.capcom.smsgateway.modules.logs.logsModule
import me.capcom.smsgateway.modules.messages.messagesModule
import me.capcom.smsgateway.modules.mms.mmsModule
import me.capcom.smsgateway.modules.notifications.notificationsModule
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.orchestrator.orchestratorModule
import me.capcom.smsgateway.modules.ping.pingModule
import me.capcom.smsgateway.modules.receiver.receiverModule
import me.capcom.smsgateway.modules.settings.settingsModule
import me.capcom.smsgateway.modules.webhooks.webhooksModule
import me.capcom.smsgateway.receivers.EventsReceiver
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class App: Application() {
    override fun attachBaseContext(base: Context?) {
        // CARD-21: copy preferences + database to device-protected storage, once, before
        // anything opens them (the database is closed here). Never runs while the user is
        // locked; a failure keeps the app on the original data and is reported in onCreate.
        base?.let { DeviceStorage.migrateOnce(it) }
        super.attachBaseContext(base?.let { LocaleHelper.onAttach(it) })
    }

    override fun onCreate() {
        super.onCreate()

        // CARD-21: the copy was skipped because this process started locked, and the user is
        // unlocked by now (the unlock receiver would never fire). Running on the empty
        // device-protected storage would look unlinked and let a re-link be overwritten by the
        // later copy, so restart before anything starts or writes.
        if (MigrationFollowUp.of(DeviceStorage.lastResult, DeviceStorage.isUnlocked(this))
            == MigrationFollowUp.Action.RESTART_NOW
        ) {
            DeviceStorage.exitAndRelaunch(this)
        }

        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(
                eventBusModule,
                settingsModule,
                dbModule,
                logsModule,
                notificationsModule,
                mmsModule,
                messagesModule,
                incomingModule,
                receiverModule,
                encryptionModule,
                me.capcom.smsgateway.modules.gateway.gatewayModule,
                healthModule,
                webhooksModule,
                localserverModule,
                pingModule,
                connectionModule,
                orchestratorModule,
                deviceModule,
                me.capcom.smsgateway.modules.account.accountModule,
                me.capcom.smsgateway.modules.gateway.workSchedulerModule,
            )
        }

        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(
                Thread.getDefaultUncaughtExceptionHandler()!!,
                get()
            )
        )

        instance = this

        EventsReceiver.register(this)

        // Mnaseb SIM: no local server, ever. Clear an old v0.1 flag before the
        // orchestrator starts, so a boot before the UI opens cannot start it.
        get<LocalServerSettings>().enabled = false

        // CARD-21: say it when the copy to device-protected storage failed (the app runs on the
        // original data meanwhile, so it does not look unlinked), and restart at the unlock
        // when the copy was deferred because this process started locked.
        when (MigrationFollowUp.of(DeviceStorage.lastResult, DeviceStorage.isUnlocked(this))) {
            MigrationFollowUp.Action.RESTART_NOW -> Unit // handled first thing in onCreate

            MigrationFollowUp.Action.REPORT_FAILURE -> get<LogsService>().insert(
                LogEntry.Priority.ERROR,
                "DeviceStorage",
                "The copy to device-protected storage failed (${DeviceStorage.lastFailure}); " +
                        "running on the original storage, it is retried at the next start",
            )

            MigrationFollowUp.Action.RESTART_AT_UNLOCK -> DeviceStorage.restartAtUnlock(this)
            MigrationFollowUp.Action.NONE -> Unit
        }

        // CARD-21: a process started before the first unlock (a direct-boot component: the
        // boot receiver, the SSE service restarted by the system, or a delivery report) must
        // not start WorkManager-based work. It starts the locked pipeline instead (idempotent,
        // whichever entry got here first); the full start runs through the unlock handover.
        if (DeviceStorage.isUnlocked(this)) {
            get<OrchestratorService>().start(this, true)
        } else {
            LockedMode.start(this)
        }
    }

    val gatewayService: GatewayService by inject()

    companion object {
        lateinit var instance: App
            private set
    }
}
