package me.capcom.smsgateway.modules.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.InProcessScheduler
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * CARD-21: what runs before the first unlock after a reboot.
 *
 * Only device-protected storage is touched. No WorkManager call and no account
 * store: the gateway token is enough to pull messages, send them and report states.
 * The pipeline runs through the in-process scheduler (`ModeAwareScheduler` picks it
 * while locked); the SSE service carries the stream and the heartbeat.
 */
object LockedMode : KoinComponent {
    private const val TAG = "LockedRunner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var unlockReceiver: BroadcastReceiver? = null

    @Synchronized
    fun start(context: Context) {
        try {
            val gateway = get<GatewaySettings>()
            if (!gateway.enabled || gateway.registrationInfo == null) {
                return
            }

            Log.d(TAG, "starting before the first unlock")
            if (!started) {
                started = true
                get<GatewayService>().startLocked()
                get<WebHooksService>().startLocked()
                registerUnlockReceiver(context.applicationContext)
                logKeystoreProbe()
            }
            SSEForegroundService.start(context)
        } catch (e: Throwable) {
            Log.e(TAG, "can't start before the first unlock: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Acceptance evidence for A5: logs once per locked start whether the Keystore opens
     * and how many keys it lists. Never the alias, never key material. No key flag is changed.
     */
    private fun logKeystoreProbe() {
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            Log.d(TAG, "keystore opened while locked, entries=${ks.size()}")
        } catch (e: Throwable) {
            Log.w(TAG, "keystore did not open while locked: ${e.javaClass.simpleName}")
        }
    }

    /** `ACTION_USER_UNLOCKED` is sent to runtime-registered receivers only. */
    private fun registerUnlockReceiver(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onUnlocked(app)
            }
        }
        unlockReceiver = receiver
        app.registerReceiver(receiver, IntentFilter("android.intent.action.USER_UNLOCKED"))
    }

    private fun onUnlocked(app: Context) {
        Log.d(TAG, "unlocked, handing over to the full start")
        try {
            app.unregisterReceiver(unlockReceiver)
        } catch (e: Throwable) {
            // already gone
        }
        unlockReceiver = null

        scope.launch {
            try {
                get<InProcessScheduler>().drainThenHandover {
                    // the full start (WorkManager); message ids + PerMessageLock prevent double sends
                    get<OrchestratorService>().start(app, true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "handover failed: ${e.javaClass.simpleName}")
            }
            synchronized(this@LockedMode) { started = false }
        }
    }
}
