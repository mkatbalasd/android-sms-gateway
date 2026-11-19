package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.capcom.smsgateway.helpers.PhoneHelper
import java.io.IOException

/** Where the forgotten-password screens are (V2-L33). */
sealed class ResetState {
    /** The number is asked for. */
    object EnterPhone : ResetState()

    /**
     * The code screen, shown after EVERY accepted start (the server answers 202 for every
     * number, so this screen says nothing about an account). [resendAtMs] and [expiresAtMs]
     * are counted on this phone from the 202; [dead] is true when the five tries are spent.
     */
    data class EnterCode(
        val resendAtMs: Long,
        val expiresAtMs: Long,
        val dead: Boolean = false,
    ) : ResetState()

    /** The password is changed and the local session is gone: the person signs in again. */
    object Done : ResetState()
}

enum class ResetProblem {
    PHONE_INVALID,
    CODE_LENGTH,
    PASSWORD_SHORT,
    PASSWORD_MISMATCH,

    /** The server refused the password (validation); says nothing about the number. */
    PASSWORD_REJECTED,

    /** One answer for a wrong, missing, used or expired code (the server cannot tell them apart). */
    CODE_REJECTED,

    /** The ten minutes counted on this phone, or the five tries, are spent. Same for every number. */
    CODE_EXPIRED,
    RATE_LIMIT,
    UNAVAILABLE,
    NETWORK,

    /** Any start failure that is not a limit: one neutral copy. */
    GENERIC,
}

data class ResetFailure(val problem: ResetProblem, val waitSeconds: Int? = null)

/**
 * The forgotten-password state machine (V2-L33, contract
 * `docs/plan/v2-sim/app-forgot-password-contract.md` of the platform). No Android types.
 *
 * Rules built in:
 *  - the screen after a start is always the code screen, whatever the number (the server
 *    answers 202 for every number), and every failure of a start is a limit or one neutral copy;
 *  - the number is held in memory only between the start and the end of the flow and is never
 *    logged; the code and the password are parameters of [submit] and are never held;
 *  - a success revokes every server session, so [clearLocalSession] runs and the state becomes
 *    [ResetState.Done] (the person signs in again; the phone stays linked).
 */
class PasswordResetFlow(
    private val backend: PasswordResetBackend,
    private val clearLocalSession: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow<ResetState>(ResetState.EnterPhone)
    val state: StateFlow<ResetState> = _state

    private var contact: String? = null
    private var locale: String = "ar"
    private var wrongTries = 0

    /** Step 1: asks for a code for [mobileE164]. Null on success; the code screen is then shown. */
    suspend fun requestCode(mobileE164: String, locale: String): ResetFailure? {
        val valid = PhoneHelper.validMobileOrNull(mobileE164)
            ?: return ResetFailure(ResetProblem.PHONE_INVALID)
        this.locale = if (locale == "en") "en" else "ar"
        return start(valid, resend = false)
    }

    /** A new start for the same number (offered after 60 s). */
    suspend fun resend(): ResetFailure? {
        val current = _state.value as? ResetState.EnterCode ?: return ResetFailure(ResetProblem.GENERIC)
        val number = contact ?: return ResetFailure(ResetProblem.GENERIC)
        if (now() < current.resendAtMs) {
            return ResetFailure(ResetProblem.RATE_LIMIT, ((current.resendAtMs - now() + 999) / 1000).toInt())
        }
        return start(number, resend = true)
    }

    private suspend fun start(number: String, resend: Boolean): ResetFailure? {
        try {
            backend.resetStart(ResetStartRequest(number, locale))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            val failure = failureOf(e.error, forVerify = false)
            val current = _state.value
            if (resend && failure.problem == ResetProblem.RATE_LIMIT && current is ResetState.EnterCode) {
                val until = now() + (failure.waitSeconds ?: RESEND_AFTER_S) * 1000L
                _state.value = current.copy(resendAtMs = maxOf(current.resendAtMs, until))
            }
            return failure
        } catch (e: IOException) {
            return ResetFailure(ResetProblem.NETWORK)
        } catch (e: Exception) {
            return ResetFailure(ResetProblem.GENERIC)
        }
        contact = number
        wrongTries = 0
        val at = now()
        _state.value = ResetState.EnterCode(
            resendAtMs = at + RESEND_AFTER_S * 1000L,
            expiresAtMs = at + CODE_LIFETIME_S * 1000L,
        )
        return null
    }

    /**
     * Step 2: the code and the new password. Checked on the device first (length, match,
     * the ten minutes). Null on success, and then [state] is [ResetState.Done].
     */
    suspend fun submit(rawCode: String, newPassword: String, confirm: String): ResetFailure? {
        val current = _state.value as? ResetState.EnterCode ?: return ResetFailure(ResetProblem.GENERIC)
        val number = contact ?: return ResetFailure(ResetProblem.GENERIC)
        val code = OtpDigits.normalize(rawCode)
        if (code.length != CODE_LENGTH) return ResetFailure(ResetProblem.CODE_LENGTH)
        if (newPassword.length < MIN_PASSWORD) return ResetFailure(ResetProblem.PASSWORD_SHORT)
        if (newPassword != confirm) return ResetFailure(ResetProblem.PASSWORD_MISMATCH)
        if (current.dead || now() >= current.expiresAtMs) {
            return ResetFailure(ResetProblem.CODE_EXPIRED)
        }
        try {
            backend.resetVerify(ResetVerifyRequest(number, code, newPassword))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            val failure = failureOf(e.error, forVerify = true)
            if (failure.problem == ResetProblem.CODE_REJECTED && ++wrongTries >= MAX_TRIES) {
                // Five wrong tries kill the code on the server; offer a new one at once.
                _state.value = current.copy(resendAtMs = now(), dead = true)
            }
            return failure
        } catch (e: IOException) {
            return ResetFailure(ResetProblem.NETWORK)
        } catch (e: Exception) {
            return ResetFailure(ResetProblem.GENERIC)
        }
        // Every server session of the account is already revoked: drop the local tokens too.
        // A failure to clear must not hide the success (the stored tokens are dead anyway).
        try {
            clearLocalSession()
        } catch (e: Exception) {
            // nothing: the next refresh is refused and clears the session
        }
        contact = null
        wrongTries = 0
        _state.value = ResetState.Done
        return null
    }

    /** Back to the first step; forgets the number. */
    fun reset() {
        contact = null
        wrongTries = 0
        _state.value = ResetState.EnterPhone
    }

    /** Back from the code screen to the number. */
    fun changeNumber() = reset()

    private fun failureOf(error: AccountError, forVerify: Boolean): ResetFailure {
        val limited = error.code == "RESET_RATE_LIMIT" || error.httpStatus == 429
        return when {
            limited -> ResetFailure(ResetProblem.RATE_LIMIT, error.retryAfterSeconds?.coerceAtLeast(1))
            error.httpStatus == 503 || error.code == "HTTP_503" -> ResetFailure(ResetProblem.UNAVAILABLE)
            forVerify && (error.code == "OTP_INVALID" || error.code == "OTP_EXPIRED") ->
                ResetFailure(ResetProblem.CODE_REJECTED)

            forVerify && error.code == "VALIDATION_ERROR" -> ResetFailure(ResetProblem.PASSWORD_REJECTED)
            // Any other 400 on verify is the same single "code not accepted" answer.
            forVerify && error.httpStatus == 400 -> ResetFailure(ResetProblem.CODE_REJECTED)
            else -> ResetFailure(ResetProblem.GENERIC)
        }
    }

    companion object {
        const val MIN_PASSWORD = 10
        const val CODE_LENGTH = 6
        const val MAX_TRIES = 5

        /** The contract: a resend is offered 60 s after the 202. */
        const val RESEND_AFTER_S = 60

        /** The contract: the code lives 10 minutes; the 202 carries no time, so it is counted here. */
        const val CODE_LIFETIME_S = 600
    }
}
