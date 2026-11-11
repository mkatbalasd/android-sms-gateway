package me.capcom.smsgateway.modules.account

/**
 * CARD-21: what the old link leaves in the default preferences (device-protected storage)
 * that unlinking must forget, so the next link does not inherit it: the webhook signing key
 * (it lets whoever holds it forge delivery reports for the old device), the encryption
 * passphrase and the gateway tokens.
 *
 * The registration, the autostart switch and the keep-alive flag have their own steps in
 * [AndroidLocalCleanup.forgetLink].
 */
internal object LinkSecrets {
    val PREFERENCE_KEYS: List<String> = listOf(
        "webhooks.signing_key",
        "encryption.passphrase",
        "gateway.private_token",
        "gateway.fcm_token",
    )

    private const val REGISTRATION_INFO = "gateway.REGISTRATION_INFO"

    /** @param remove removes one preference key (a `SharedPreferences.Editor`, in the app). */
    fun forget(remove: (String) -> Unit) {
        PREFERENCE_KEYS.forEach(remove)
    }

    /**
     * The credential-encrypted copy the migration leaves for a rollback still holds the old
     * link (token, and the password the device-protected copy never had). Unlink clears it
     * there too, so a downgrade after a re-link cannot bring the old link back.
     */
    fun forgetInRollbackCopy(remove: (String) -> Unit) {
        PREFERENCE_KEYS.forEach(remove)
        remove(REGISTRATION_INFO)
    }
}
