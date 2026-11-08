package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.gateway.LoginCodeSanitizer

/**
 * Turns what the keeper typed (or what the SIM reported) into the E.164 form
 * the platform wants: `+` and 8 to 15 digits. Arabic-Indic digits, spaces,
 * hyphens, dots, brackets and invisible direction marks are accepted.
 *
 * A number without `+` or `00` is read in the country of the SIM
 * ([defaultCountryCode], for example 967) when it starts with the trunk zero.
 * Without a country the number is refused rather than guessed.
 */
object MobileInput {
    private val e164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun normalize(raw: String, defaultCountryCode: Int?): String? {
        val compact = OtpDigits.ascii(LoginCodeSanitizer.clean(raw))
            .filter { it != '-' && it != '(' && it != ')' && it != '.' }
        if (compact.isEmpty()) return null

        val candidate = when {
            compact.startsWith("+") -> "+" + compact.drop(1).filter { it in '0'..'9' }
            compact.startsWith("00") -> "+" + compact.drop(2).filter { it in '0'..'9' }
            defaultCountryCode != null && compact.startsWith("0") ->
                "+" + defaultCountryCode + compact.trimStart('0').filter { it in '0'..'9' }

            else -> return null
        }
        return candidate.takeIf { e164.matches(it) }
    }

    /** True when the text is more likely a phone number than an e-mail address. */
    fun looksLikePhone(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotEmpty() && !trimmed.contains('@')
    }
}
