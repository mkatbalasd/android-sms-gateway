package me.capcom.smsgateway.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import me.capcom.smsgateway.modules.account.AccountError
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AppClient

/**
 * «افتح لوحة التحكم»: asks for the one-time workspace link and opens it in a
 * Custom Tab, or in the browser when there is none. The link is a credential for
 * 60 seconds: it is held in a local variable only, never logged, never shown,
 * never stored.
 */
object HandoffOpener {

    /** Returns true when a browser was started. Throws [AccountException] or an IOException from the request. */
    suspend fun open(context: Context, client: AppClient): Boolean {
        val uri = Uri.parse(client.handoffUrl())
        if (uri.scheme != "https" || uri.host.isNullOrEmpty()) {
            throw AccountException(AccountError("BAD_RESPONSE", 200))
        }
        return launch(context, uri)
    }

    private fun launch(context: Context, uri: Uri): Boolean {
        try {
            val intent = CustomTabsIntent.Builder().build()
            if (context !is android.app.Activity) {
                intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent.launchUrl(context, uri)
            return true
        } catch (e: ActivityNotFoundException) {
            // No Custom Tabs provider: the plain browser intent works everywhere.
        } catch (e: RuntimeException) {
            // A provider that fails to start: same fallback.
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
