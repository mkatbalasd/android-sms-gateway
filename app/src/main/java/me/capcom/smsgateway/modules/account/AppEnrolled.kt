package me.capcom.smsgateway.modules.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.ui.dialogs.KeepAliveGuideDialogFragment

/**
 * True for an install that linked itself through an app session (the customer
 * phone). A phone linked by a QR or a typed code, such as the platform phone,
 * is not: it keeps every upstream screen.
 */
object AppEnrolled {
    fun check(accountSettings: AccountSettings, gatewaySettings: GatewaySettings): Boolean =
        accountSettings.linked && gatewaySettings.registrationInfo != null
}

/**
 * «فصل هذا الهاتف»: the platform retires the phone after the password check,
 * then the app forgets the link and the next start opens the welcome screen.
 * The password goes to the platform and is dropped.
 */
class AppUnlink(
    context: Context,
    private val client: AppClient,
    private val accountSettings: AccountSettings,
    private val gatewaySettings: GatewaySettings,
    private val settingsHelper: SettingsHelper,
    private val orchestrator: OrchestratorService,
) {
    private val appContext: Context = context.applicationContext

    suspend fun run(password: String) {
        client.unlink(password)
        // The same cleanup as a failed enrolment: stop sending, forget the registration.
        withContext(Dispatchers.Main) { orchestrator.stop(appContext) }
        gatewaySettings.registrationInfo = null
        settingsHelper.autostart = false
        accountSettings.reset()
        KeepAliveGuideDialogFragment.forget(appContext)
    }
}
