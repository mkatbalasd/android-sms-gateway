package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.modules.gateway.GatewaySettings

/**
 * True for an install that linked itself through an app session (the customer
 * phone). A phone linked by a QR or a typed code, such as the platform phone,
 * is not: it keeps every upstream screen.
 */
object AppEnrolled {
    fun check(accountSettings: AccountSettings, gatewaySettings: GatewaySettings): Boolean =
        accountSettings.linked && gatewaySettings.registrationInfo != null
}

/** What «فصل هذا الهاتف» clears on the phone. Each step is idempotent. */
interface LocalCleanup {
    /** Stops the sending service so nothing more leaves this phone. */
    suspend fun stopService()

    /**
     * Fails every message that is not final and deletes the local message, inbox,
     * inbox-upload and webhook queues (and the log), so nothing of the old link is sent
     * later, after a re-link, or read on the upstream screens.
     */
    suspend fun purgeData()

    /** Forgets the gateway registration, start on boot and the account (tokens, tenant, link flag). */
    suspend fun forgetLink()
}

/**
 * «فصل هذا الهاتف»: the platform retires the phone after the password check, then the
 * app clears itself and the next start opens the welcome screen. The password goes to
 * the platform and is dropped.
 *
 * Nothing is cleared when the platform refuses (wrong password) or cannot be reached
 * (offline). A session the platform no longer knows, or a phone it no longer lists,
 * means the phone is already unlinked there: the same local cleanup runs, because it
 * only lowers privilege and gives up no secret. The cleanup cannot be cancelled by a
 * screen that goes away.
 */
class AppUnlink(
    private val client: AppClient,
    private val local: LocalCleanup,
) {
    suspend fun run(password: String) {
        try {
            client.unlink(password)
        } catch (e: AccountException) {
            if (e.error.code !in ALREADY_UNLINKED) throw e
        }
        withContext(NonCancellable) {
            local.stopService()
            local.purgeData()
            local.forgetLink()
        }
    }

    companion object {
        /** Answers that mean the phone is already unlinked on the platform. */
        val ALREADY_UNLINKED = setOf("APP_SESSION_INVALID", "NOT_FOUND")
    }
}
