package me.capcom.smsgateway.ui.account

import android.content.Context
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.account.AccountError

/** An error for the keeper: a stable [code] and, when it helps, one [number] (attempts left, minutes to wait). */
data class UiError(val code: String, val number: Int? = null) {
    companion object {
        const val NETWORK = "NETWORK"
        const val GENERIC = "GENERIC"

        fun from(error: AccountError): UiError {
            val number = when (error.code) {
                "OTP_INVALID" -> error.attemptsLeft
                else -> error.retryAfterSeconds?.let { (it + 59) / 60 }?.coerceAtLeast(1)
            }
            return UiError(error.code, number)
        }
    }
}

/** The sentences of the design for each failure. Nothing here shows a code, a key or a token. */
object AccountMessages {

    fun text(context: Context, error: UiError): String {
        val number = error.number
        return when (val res = resourceFor(error)) {
            R.string.account_err_otp_invalid_left,
            R.string.account_err_rate_limit_wait -> context.getString(res, number ?: 0)

            else -> context.getString(res)
        }
    }

    private fun resourceFor(error: UiError): Int = when (error.code) {
        UiError.NETWORK -> R.string.account_err_network
        "HTTP_404" -> R.string.account_err_closed
        "SIGNUP_COUNTRY_NOT_ALLOWED" -> R.string.account_err_country
        "TENANT_NAME_TAKEN" -> R.string.account_err_name_taken
        "OTP_INVALID" ->
            if (error.number != null) R.string.account_err_otp_invalid_left
            else R.string.account_err_otp_invalid

        "OTP_EXPIRED" -> R.string.account_err_otp_expired
        "LOGIN_RATE_LIMIT", "SIGNUP_RATE_LIMIT", "TOO_MANY_REQUESTS", "HTTP_429", "ENROLMENT_TOO_MANY" ->
            if (error.number != null) R.string.account_err_rate_limit_wait
            else R.string.account_err_rate_limit

        "OTP_CHANNEL_UNAVAILABLE", "HTTP_503" -> R.string.account_err_channel
        "VALIDATION_ERROR" -> R.string.account_err_validation
        "ACCOUNT_LOCKED" -> R.string.account_err_locked
        "TENANT_SUSPENDED" -> R.string.account_err_suspended
        "APP_SESSION_INVALID", "APP_SESSION_REVOKED", "NO_SESSION" -> R.string.account_err_session
        // Signing in again on a linked phone with another account: refused, nothing is stored.
        "ACCOUNT_MISMATCH" -> R.string.account_err_mismatch
        "GATEWAY_ACCOUNT_NOT_READY" -> R.string.account_err_not_ready
        "ENROLMENT_CODE_REFUSED" -> R.string.account_err_enrolment_code
        "ENROLMENT_SERVER_MISMATCH" -> R.string.account_err_enrolment_server
        "ENROLMENT_NOT_LISTED" -> R.string.account_err_not_listed
        // CARD-12: unlink with a wrong password, and the app routes after linking.
        "INVALID_CREDENTIALS" -> R.string.account_err_wrong_password
        "OWNER_REQUIRED" -> R.string.account_err_owner_only
        "SANDBOX_DAILY_LIMIT" -> R.string.account_err_daily_limit
        "NO_PLATFORM_LINE" -> R.string.account_err_no_platform_line
        "NUMBER_HELD" -> R.string.account_err_number_held
        "LINE_PROOF_OPEN" -> R.string.account_err_proof_open
        "BAD_RESPONSE" -> R.string.account_err_generic
        else ->
            if (error.code.startsWith("ENROLMENT")) R.string.account_err_enrolment
            else R.string.account_err_generic
    }
}
