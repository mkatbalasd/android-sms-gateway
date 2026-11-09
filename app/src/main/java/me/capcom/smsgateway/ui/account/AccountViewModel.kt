package me.capcom.smsgateway.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AccountRepository
import me.capcom.smsgateway.modules.account.AccountSettings
import me.capcom.smsgateway.modules.account.AppEnrolmentSteps
import me.capcom.smsgateway.modules.account.Enrolment
import me.capcom.smsgateway.modules.account.EnrolmentFlow
import me.capcom.smsgateway.modules.account.EnrolmentState
import me.capcom.smsgateway.modules.account.PendingVerification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

enum class AccountScreen { WELCOME, PERMISSIONS, CREATE, SIGN_IN, CODE, LINKING }

/**
 * The state of the first-run account screens. It survives rotation; if the
 * process dies the sign-up or login waiting for its code is restored from the
 * encrypted store, and a signed-in but unlinked phone resumes the enrolment.
 *
 * The password is read from the form, handed to the repository and forgotten:
 * no field of this class holds it.
 */
class AccountViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val repository: AccountRepository by inject()
    private val settings: AccountSettings by inject()
    private val stepsProvider: AppEnrolmentSteps by inject()

    val screen = MutableLiveData(AccountScreen.WELCOME)
    val busy = MutableLiveData(false)

    /** Shown once by the fragment on screen; the fragment calls [errorShown] afterwards. */
    val error = MutableLiveData<UiError?>(null)

    /** A string resource to show once as plain information. */
    val info = MutableLiveData<Int?>(null)

    val pending = MutableLiveData<PendingVerification?>(null)
    val enrolment = MutableLiveData<EnrolmentState>(EnrolmentState.Idle)

    /** The code reader filled the field. */
    val autofilled = MutableLiveData(false)

    private var afterPermissions = AccountScreen.CREATE
    private var enrolmentJob: Job? = null

    init {
        val waiting = settings.pending
        when {
            waiting != null -> {
                pending.value = waiting
                screen.value = AccountScreen.CODE
            }

            settings.hasSession && !settings.linked -> {
                screen.value = AccountScreen.LINKING
                startEnrolment(null)
            }
        }
    }

    //region Navigation

    fun chooseCreate() = requirePermissionsThen(AccountScreen.CREATE)

    fun chooseSignIn() = requirePermissionsThen(AccountScreen.SIGN_IN)

    private fun requirePermissionsThen(target: AccountScreen) {
        if (AccountPermissions.missing(getApplication<Application>()).isEmpty()) {
            screen.value = target
        } else {
            afterPermissions = target
            screen.value = AccountScreen.PERMISSIONS
        }
    }

    fun permissionsDone() {
        screen.value = afterPermissions
    }

    /** The system Back key. Returns false when the activity should close. */
    fun back(): Boolean = when (screen.value) {
        AccountScreen.PERMISSIONS, AccountScreen.CREATE, AccountScreen.SIGN_IN -> {
            screen.value = AccountScreen.WELCOME
            true
        }

        AccountScreen.CODE -> {
            startOver()
            true
        }

        // While the phone is being linked there is nothing to go back to; after a failure
        // the keeper may leave the screen, and after success the activity closes.
        AccountScreen.LINKING -> when (enrolment.value) {
            is EnrolmentState.Linked -> false
            is EnrolmentState.Failed -> {
                screen.value = AccountScreen.WELCOME
                true
            }

            else -> true
        }

        else -> false
    }

    fun startOver() {
        repository.discardPending()
        pending.value = null
        autofilled.value = false
        screen.value = AccountScreen.WELCOME
    }

    fun errorShown() {
        error.value = null
    }

    fun infoShown() {
        info.value = null
    }

    //endregion

    //region Sign-up, sign-in, code

    fun submitSignup(businessName: String, mobileE164: String, password: String, locale: String) {
        launchBusy {
            val started = withContext(Dispatchers.IO) {
                repository.startSignup(businessName, mobileE164, password, locale)
            }
            showCode(started)
        }
    }

    fun submitSignIn(contact: String, password: String, locale: String) {
        launchBusy {
            val started = withContext(Dispatchers.IO) {
                repository.startLogin(contact, password, locale)
            }
            showCode(started)
        }
    }

    private fun showCode(started: PendingVerification) {
        autofilled.value = false
        pending.value = started
        screen.value = AccountScreen.CODE
    }

    fun submitCode(code: String, fromSms: Boolean = false) {
        val kind = settings.pending?.kind
        launchBusy(onFailure = { failure ->
            // A dead code (expired, too many attempts) cannot be retried: start over.
            if (failure.code == "OTP_EXPIRED") {
                repository.discardPending()
                pending.value = null
                screen.value = if (kind == PendingVerification.KIND_LOGIN) {
                    AccountScreen.SIGN_IN
                } else {
                    AccountScreen.CREATE
                }
            }
        }) {
            autofilled.value = fromSms
            val outcome = withContext(Dispatchers.IO) { repository.verify(code) }
            pending.value = null
            if (!outcome.signedIn) {
                // A repeat of a finished sign-up: no second session is minted from a spent code.
                info.value = R.string.account_signup_signin_next
                screen.value = AccountScreen.SIGN_IN
            } else {
                screen.value = AccountScreen.LINKING
                startEnrolment(outcome.enrolment)
            }
        }
    }

    fun resend() {
        launchBusy {
            val renewed = withContext(Dispatchers.IO) { repository.resend() }
            pending.value = renewed
        }
    }

    //endregion

    //region Enrolment

    /** Runs the enrolment; [initial] is what the verify call carried (null: ask the server). */
    fun startEnrolment(initial: Enrolment?) {
        if (enrolmentJob?.isActive == true) return
        val flow = EnrolmentFlow(stepsProvider) { delay(it) }
        enrolmentJob = viewModelScope.launch {
            val watcher = launch { flow.state.collect { enrolment.value = it } }
            try {
                val result = withContext(Dispatchers.IO) { flow.run(initial) }
                enrolment.value = result
                if (result is EnrolmentState.Linked) {
                    withContext(Dispatchers.IO) { settings.markLinked() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the app from here: a registration, if any, stays pending.
                enrolment.value = EnrolmentState.Failed(UiError.GENERIC)
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Try again after a failure. A new run asks the server for a fresh enrolment. */
    fun retryEnrolment() {
        if (!settings.hasSession) {
            // The refresh was refused (or never existed): only a new sign-in brings an enrolment.
            screen.value = AccountScreen.SIGN_IN
            return
        }
        startEnrolment(null)
    }

    //endregion

    private fun launchBusy(
        onFailure: (UiError) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (busy.value == true) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                val failure = UiError.from(e.error)
                error.value = failure
                onFailure(failure)
            } catch (e: IOException) {
                error.value = UiError(UiError.NETWORK)
            } catch (e: Exception) {
                error.value = UiError(UiError.GENERIC)
            } finally {
                busy.value = false
            }
        }
    }
}
