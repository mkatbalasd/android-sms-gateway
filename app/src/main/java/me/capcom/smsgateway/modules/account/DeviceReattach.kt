package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.CancellationException
import java.io.IOException

sealed class ReattachOutcome {
    object Attached : ReattachOutcome()

    /** [code] is a stable error code (never a nonce, a ciphertext or a token). */
    data class Failed(val code: String) : ReattachOutcome()
}

/**
 * After a linked phone signs in again its new app session is not yet tied to the phone on
 * the platform. This proves the phone holds the gateway device's E2E key: the platform
 * sends a random nonce encrypted to that key, the phone decrypts it and sends it back.
 *
 * It never unlinks, never enrols and never touches the gateway registration. The nonce and
 * the ciphertext live only in locals of [run]; nothing is logged or stored.
 *
 * [decrypt] is the phone's own E2E decrypt (the same one used for E2E messages).
 */
class DeviceReattach(
    private val client: AppClient,
    private val settings: AccountSettings,
    private val deviceId: () -> String?,
    private val decrypt: suspend (ciphertext: String) -> String,
) {
    suspend fun run(): ReattachOutcome {
        val externalId = deviceId()?.takeIf { it.isNotEmpty() }
            ?: return ReattachOutcome.Failed(DEVICE_NOT_FOUND)
        try {
            val challenge = client.reattachChallenge(externalId)
            val challengeId = challenge.challengeId?.takeIf { it.isNotEmpty() }
            val ciphertext = challenge.ciphertext?.takeIf { it.isNotEmpty() }
            if (challengeId == null || ciphertext == null) return ReattachOutcome.Failed("BAD_RESPONSE")

            val nonce = try {
                decrypt(ciphertext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ReattachOutcome.Failed(DECRYPT_FAILED)
            }

            val result = client.reattach(challengeId, nonce)
            if (result.attached != true) return ReattachOutcome.Failed("BAD_RESPONSE")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            // The session is already tied to the phone: nothing left to do.
            if (e.error.code != SESSION_ALREADY_ATTACHED) return ReattachOutcome.Failed(e.error.code)
        } catch (e: IOException) {
            return ReattachOutcome.Failed("NETWORK")
        } catch (e: Exception) {
            return ReattachOutcome.Failed("GENERIC")
        }
        settings.reattachPending = false
        return ReattachOutcome.Attached
    }

    companion object {
        const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
        const val SESSION_ALREADY_ATTACHED = "SESSION_ALREADY_ATTACHED"
        const val DECRYPT_FAILED = "REATTACH_DECRYPT"
    }
}
