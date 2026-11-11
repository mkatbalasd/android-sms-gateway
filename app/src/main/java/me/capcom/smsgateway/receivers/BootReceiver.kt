package me.capcom.smsgateway.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.orchestrator.LockedMode
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Direct-boot aware (CARD-21): it also receives `LOCKED_BOOT_COMPLETED`, before the
 * first unlock. Everything it touches while locked is device-protected storage.
 */
class BootReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        val action = BootPolicy.decide(
            action = intent.action,
            isUnlocked = DeviceStorage.isUnlocked(context),
            autostart = get<SettingsHelper>().autostart,
            sdkInt = Build.VERSION.SDK_INT,
        )

        Log.d("BootReceiver", "${intent.action} -> $action")

        when (action) {
            BootPolicy.Action.IGNORE -> Unit
            BootPolicy.Action.START_FULL -> get<OrchestratorService>().start(context, true)
            BootPolicy.Action.START_LOCKED -> LockedMode.start(context)
        }
    }
}
