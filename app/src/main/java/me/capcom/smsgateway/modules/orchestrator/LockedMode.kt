package me.capcom.smsgateway.modules.orchestrator

import android.content.Context
import android.util.Log
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * CARD-21: what runs before the first unlock after a reboot.
 *
 * Only device-protected storage is touched. No WorkManager call and no account
 * store: the gateway token is enough to pull messages, send them and report states.
 */
object LockedMode : KoinComponent {
    private const val TAG = "LockedMode"

    fun start(context: Context) {
        try {
            val gateway = get<GatewaySettings>()
            if (!gateway.enabled || gateway.registrationInfo == null) {
                return
            }

            Log.d(TAG, "starting before the first unlock")
            SSEForegroundService.start(context)
        } catch (e: Throwable) {
            Log.e(TAG, "can't start before the first unlock: ${e.javaClass.simpleName}")
        }
    }
}
