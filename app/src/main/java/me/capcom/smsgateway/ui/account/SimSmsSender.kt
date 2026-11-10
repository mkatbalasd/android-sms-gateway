package me.capcom.smsgateway.ui.account

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Sends the one verification SMS from a chosen SIM (CARD-12). The text is a
 * platform secret: it goes to the carrier and is never logged or displayed.
 * "Sent" is the radio's answer (the sent-status broadcast), not a delivery report.
 */
class SimSmsSender(context: Context) : LineSmsSender {

    private val appContext: Context = context.applicationContext

    override suspend fun send(slot: Int, to: String, text: String): Boolean {
        val manager = managerFor(slot) ?: return false
        val action = "${appContext.packageName}.LINE_PROOF_SENT.${UUID.randomUUID()}"

        return withTimeoutOrNull(SENT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        runCatching { appContext.unregisterReceiver(this) }
                        if (continuation.isActive) {
                            continuation.resume(resultCode == Activity.RESULT_OK)
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                        receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    appContext.registerReceiver(receiver, IntentFilter(action))
                }
                continuation.invokeOnCancellation {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }

                var flags = PendingIntent.FLAG_ONE_SHOT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Immutable on 31+ is required; it is also right below, nothing fills the intent in.
                    flags = flags or PendingIntent.FLAG_IMMUTABLE
                }
                val sentIntent = PendingIntent.getBroadcast(
                    appContext,
                    0,
                    Intent(action).setPackage(appContext.packageName),
                    flags
                )

                try {
                    manager.sendTextMessage(to, null, text, sentIntent, null)
                } catch (e: Exception) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
    }

    /** The manager of the SIM in [slot] (1-based), by subscription; the default one only on API 21. */
    @Suppress("DEPRECATION")
    private fun managerFor(slot: Int): SmsManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return SmsManager.getDefault()
        }
        val subscriptionId = SubscriptionsHelper.getSubscriptionId(
            appContext, SubscriptionsHelper.slotIndexForSimNumber(slot) ?: return null
        ) ?: return null
        return if (Build.VERSION.SDK_INT < 31) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            appContext.getSystemService(SmsManager::class.java)
                ?.createForSubscriptionId(subscriptionId)
        }
    }

    private companion object {
        const val SENT_TIMEOUT_MS = 30_000L
    }
}
