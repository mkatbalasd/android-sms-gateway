package me.capcom.smsgateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.orchestrator.LockedMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * CARD-21: the target of our own relaunch alarm (see `DeviceStorage.exitAndRelaunch`), after the
 * process exited to run the deferred storage copy. NOT exported: only our own PendingIntent
 * reaches it, so no other app can trigger a start through it.
 */
class RelaunchReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BootPolicy.RELAUNCH) return

        val action = BootPolicy.decide(
            action = intent.action,
            isUnlocked = DeviceStorage.isUnlocked(context),
            autostart = get<SettingsHelper>().autostart,
            sdkInt = Build.VERSION.SDK_INT,
        )
        Log.d("RelaunchReceiver", "relaunch -> $action")

        when (action) {
            BootPolicy.Action.START_FULL -> LockedMode.requestFullStart(context)
            BootPolicy.Action.START_LOCKED -> LockedMode.start(context)
            BootPolicy.Action.IGNORE -> Unit
        }
    }
}
