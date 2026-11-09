package me.capcom.smsgateway.modules.gateway

/**
 * The name this phone registers under on the gateway account.
 *
 * Upstream behaviour (no suffix): `<MANUFACTURER>/<PRODUCT>`.
 *
 * Self-enrolment (CARD-07/08): `Mnaseb SIM/<MODEL up to 40>/<nonce>`. The
 * platform proves that the device was created by this app session by checking
 * that the raw name ends with `/<nonce>`, so the nonce is always last and the
 * whole name stays under the gateway's 128-character limit.
 */
object DeviceNames {
    const val ENROLMENT_PREFIX = "Mnaseb SIM"
    const val MODEL_MAX = 40
    const val NAME_MAX = 128

    /** A nonce the server minted: short letters and digits (8 Crockford base32 characters today). */
    private val nonceShape = Regex("^[A-Za-z0-9]{4,32}$")

    fun isValidNonce(nonce: String?): Boolean = nonce != null && nonceShape.matches(nonce)

    fun forRegistration(
        manufacturer: String,
        product: String,
        model: String,
        nameSuffix: String?,
    ): String {
        if (nameSuffix == null) {
            return "$manufacturer/$product"
        }
        require(isValidNonce(nameSuffix)) { "invalid enrolment nonce" }
        val safeModel = cleanModel(model)
        return "$ENROLMENT_PREFIX/$safeModel/$nameSuffix".also {
            check(it.length <= NAME_MAX)
        }
    }

    // Slashes would blur the name's segments; control and format characters
    // are dropped; the length is capped.
    private fun cleanModel(model: String): String {
        val cleaned = LoginCodeSanitizer.stripFormatChars(model)
            .filter { !Character.isISOControl(it) }
            .replace('/', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MODEL_MAX)
            .trim()
        return cleaned.ifEmpty { "Android" }
    }
}
