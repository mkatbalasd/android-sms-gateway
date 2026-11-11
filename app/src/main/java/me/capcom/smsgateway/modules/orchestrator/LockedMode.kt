package me.capcom.smsgateway.modules.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.InProcessScheduler
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.orchestrator.LockedPipelineGate.FullStart
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
 *
 * Entries: [start] is called by every component that can run while the user is locked
 * (the boot receiver and the process start in `App`), idempotently. The full start is
 * requested through [requestFullStart], so it never runs next to this pipeline: while
 * this pipeline exists the full start belongs to the unlock handover (the loops finish,
 * then everything starts), and the single send gate covers the rest.
 */
object LockedMode : KoinComponent {
    private const val TAG = "LockedRunner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = LockedPipelineGate()
    private var unlockReceiver: BroadcastReceiver? = null

    fun start(context: Context) {
        val app = context.applicationContext
        try {
            val gateway = get<GatewaySettings>()
            val shouldStart = LockedPipelineGate.shouldStartLocked(
                unlocked = DeviceStorage.isUnlocked(app),
                enabled = gateway.enabled,
                linked = gateway.registrationInfo != null,
                migrated = DeviceStorage.isMigrated(app),
                sdkInt = Build.VERSION.SDK_INT,
            )
            if (!shouldStart) {
                return
            }

            if (gate.tryStart()) {
                Log.d(TAG, "starting before the first unlock")
                try {
                    get<GatewayService>().startLocked()
                    get<WebHooksService>().startLocked()
                    registerUnlockReceiver(app)
                    logKeystoreProbe()
                } catch (e: Throwable) {
                    gate.finish()
                    throw e
                }

                // the unlock can land between the check above and the receiver registration
                if (DeviceStorage.isUnlocked(app)) {
                    onUnlocked(app)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "can't start before the first unlock: ${e.javaClass.simpleName}")
            return
        }

        try {
            SSEForegroundService.start(app)
        } catch (e: Throwable) {
            // a background start can be refused; the in-process loops still run
            Log.w(TAG, "can't start the stream service: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The only door of the full start for the boot receiver: while the locked pipeline
     * exists the full start is left to the handover (after the drain), otherwise it runs now.
     */
    fun requestFullStart(context: Context) {
        val app = context.applicationContext
        when (gate.requestFullStart()) {
            FullStart.RUN_NOW -> get<OrchestratorService>().start(app, true)
            FullStart.START_HANDOVER -> runHandover(app)
            FullStart.WAIT_FOR_HANDOVER -> Log.d(TAG, "full start waits for the handover")
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
    @Synchronized
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
        if (gate.beginHandover()) {
            runHandover(app)
        }
    }

    /** The gate has already moved to the handover phase for the caller. */
    private fun runHandover(app: Context) {
        Log.d(TAG, "unlocked, handing over to the full start")
        synchronized(this) {
            try {
                unlockReceiver?.let { app.unregisterReceiver(it) }
            } catch (e: Throwable) {
                // already gone
            }
            unlockReceiver = null
        }

        scope.launch {
            try {
                get<InProcessScheduler>().drainThenHandover {
                    // the loops have finished (or were stopped); now the full start (WorkManager)
                    get<OrchestratorService>().start(app, true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "handover failed: ${e.javaClass.simpleName}")
            } finally {
                gate.finish()
            }
        }
    }
}
