package me.capcom.smsgateway.modules.account

import android.content.Context
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.webhooks.WebhooksSettings
import java.io.IOException

/**
 * The Android side of [EnrolmentFlow]: asks the platform for an enrolment,
 * registers this phone on the gateway account with the one-time code under a
 * name that ends with the server's nonce, and tells the platform which gateway
 * device it is.
 *
 * Nothing here logs or displays the login code, the signing key or a token.
 */
class AppEnrolmentSteps(
    context: Context,
    private val repository: AccountRepository,
    private val gateway: GatewayService,
    private val gatewaySettings: GatewaySettings,
    private val webhooksSettings: WebhooksSettings,
    private val settingsHelper: SettingsHelper,
    private val orchestrator: OrchestratorService,
) : EnrolmentSteps {

    private val appContext: Context = context.applicationContext

    override suspend fun mint(): EnrolmentSteps.Mint = try {
        val enrolment = repository.mintEnrolment()
        if (enrolment == null) {
            EnrolmentSteps.Mint.NotReady
        } else {
            EnrolmentSteps.Mint.Ok(enrolment)
        }
    } catch (e: AccountException) {
        if (e.error.code == NOT_READY) {
            EnrolmentSteps.Mint.NotReady
        } else {
            EnrolmentSteps.Mint.Error(e.error.code)
        }
    } catch (e: IOException) {
        EnrolmentSteps.Mint.Error(EnrolmentFlow.Reasons.NETWORK)
    }

    override suspend fun register(enrolment: Enrolment): EnrolmentSteps.Register {
        EnrolmentChecks.problem(enrolment, GatewaySettings.PUBLIC_URL)?.let {
            return EnrolmentSteps.Register.Error(it)
        }
        val code = checkNotNull(enrolment.loginCode)
        val nonce = checkNotNull(enrolment.nonce)
        val key = checkNotNull(enrolment.signingKey)

        // The pinned key is in place before the service that signs with it
        // starts, so the first webhook is already signed with it.
        webhooksSettings.setSigningKey(key)
        gatewaySettings.enabled = true
        // A half-finished earlier attempt must not turn this into a token refresh.
        gatewaySettings.registrationInfo = null

        try {
            gateway.registerDevice(
                appContext,
                null,
                GatewayService.RegistrationMode.WithCode(code),
                nonce,
            )
        } catch (e: ResponseException) {
            return when (e.response.status.value) {
                401 -> EnrolmentSteps.Register.CodeRefused
                429 -> EnrolmentSteps.Register.Error(TOO_MANY)
                else -> EnrolmentSteps.Register.Error(REGISTER_FAILED)
            }
        } catch (e: IOException) {
            return EnrolmentSteps.Register.Error(EnrolmentFlow.Reasons.NETWORK)
        }

        val id = gatewaySettings.registrationInfo?.id
            ?: return EnrolmentSteps.Register.Error(REGISTER_FAILED)

        settingsHelper.autostart = true
        // The same call, on the same thread, as the Start button of the home screen.
        withContext(Dispatchers.Main) { orchestrator.start(appContext, false) }
        return EnrolmentSteps.Register.Ok(id)
    }

    override suspend fun complete(gatewayDeviceId: String, nonce: String): EnrolmentSteps.Complete =
        try {
            repository.completeEnrolment(gatewayDeviceId, nonce)
            EnrolmentSteps.Complete.Ok
        } catch (e: AccountException) {
            val status = e.error.httpStatus
            when {
                e.error.code == NOT_LISTED -> EnrolmentSteps.Complete.NotListed
                status == 429 || status >= 500 ->
                    EnrolmentSteps.Complete.Transient("HTTP_$status")

                else -> EnrolmentSteps.Complete.Terminal(e.error.code)
            }
        } catch (e: IOException) {
            EnrolmentSteps.Complete.Transient(EnrolmentFlow.Reasons.NETWORK)
        }

    override suspend fun rollback() {
        gatewaySettings.registrationInfo = null
        settingsHelper.autostart = false
        withContext(Dispatchers.Main) { orchestrator.stop(appContext) }
    }

    private companion object {
        const val NOT_READY = "GATEWAY_ACCOUNT_NOT_READY"
        const val NOT_LISTED = "ENROLMENT_NOT_LISTED"
        const val TOO_MANY = "ENROLMENT_TOO_MANY"
        const val REGISTER_FAILED = "ENROLMENT_REGISTER_FAILED"
    }
}
