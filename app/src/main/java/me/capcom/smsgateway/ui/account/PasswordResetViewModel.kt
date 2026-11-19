package me.capcom.smsgateway.ui.account

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.account.PasswordResetFlow
import me.capcom.smsgateway.modules.account.ResetFailure
import me.capcom.smsgateway.modules.account.ResetProblem
import me.capcom.smsgateway.modules.account.ResetState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The sentences for each reset failure: neutral, never about whether a number has an account. */
object ResetMessages {
    fun resourceFor(failure: ResetFailure): Int = when (failure.problem) {
        ResetProblem.PHONE_INVALID -> R.string.intl_phone_error
        ResetProblem.CODE_LENGTH -> R.string.account_reset_err_code_length
        ResetProblem.PASSWORD_SHORT -> R.string.account_reset_err_password_short
        ResetProblem.PASSWORD_MISMATCH -> R.string.account_reset_err_mismatch
        ResetProblem.PASSWORD_REJECTED -> R.string.account_reset_err_password_rejected
        ResetProblem.CODE_REJECTED -> R.string.account_reset_err_code_rejected
        ResetProblem.CODE_EXPIRED -> R.string.account_reset_err_code_expired
        ResetProblem.RATE_LIMIT ->
            if (failure.waitSeconds != null) R.string.account_reset_err_rate_limit_wait
            else R.string.account_reset_err_rate_limit

        ResetProblem.UNAVAILABLE -> R.string.account_reset_err_unavailable
        ResetProblem.NETWORK -> R.string.account_reset_err_network
        ResetProblem.GENERIC -> R.string.account_reset_err_generic
    }

    fun text(context: Context, failure: ResetFailure): String {
        val res = resourceFor(failure)
        return if (res == R.string.account_reset_err_rate_limit_wait) {
            context.getString(res, (((failure.waitSeconds ?: 0) + 59) / 60).coerceAtLeast(1))
        } else {
            context.getString(res)
        }
    }
}

/**
 * Holds the forgotten-password flow across rotation. The number lives only inside the flow;
 * the code and the passwords are never held here: the fragment reads them from its fields,
 * hands them to [submit] and clears the fields.
 */
class PasswordResetViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val flow: PasswordResetFlow by inject()

    val state = MutableLiveData<ResetState>(flow.state.value)
    val busy = MutableLiveData(false)

    /** Shown once by the fragment; it calls [failureShown] afterwards. */
    val failure = MutableLiveData<ResetFailure?>(null)

    /** Starts a fresh flow: the first step, nothing remembered. */
    fun begin() {
        flow.reset()
        state.value = flow.state.value
        failure.value = null
    }

    fun requestCode(mobileE164: String, locale: String) = launchCall {
        flow.requestCode(mobileE164, locale)
    }

    fun resend() = launchCall { flow.resend() }

    fun submit(code: String, password: String, confirm: String) = launchCall {
        flow.submit(code, password, confirm)
    }

    fun changeNumber() {
        flow.changeNumber()
        state.value = flow.state.value
        failure.value = null
    }

    fun failureShown() {
        failure.value = null
    }

    private fun launchCall(call: suspend () -> ResetFailure?) {
        if (busy.value == true) return
        busy.value = true
        failure.value = null
        viewModelScope.launch {
            try {
                failure.value = call()
            } finally {
                state.value = flow.state.value
                busy.value = false
            }
        }
    }
}
