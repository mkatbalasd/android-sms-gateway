package me.capcom.smsgateway.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.account.TestRecipient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import java.util.UUID

sealed class TestUiState {
    object Idle : TestUiState()

    /** Sent, not yet at its destination: «في الطريق…». */
    object OnItsWay : TestUiState()
    data class Done(val outcome: TestOutcome) : TestUiState()
}

/** F1-9: one tap, one message, one visible result. Survives rotation of the dialog. */
class TestMessageViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val client: AppClient by inject()

    val recipients = MutableLiveData<List<TestRecipient>?>(null)
    val recipientsError = MutableLiveData<UiError?>(null)
    val state = MutableLiveData<TestUiState>(TestUiState.Idle)

    private var job: Job? = null

    // One key per attempt: a repeated tap after a lost answer is the same message, not a second one.
    private var key: String? = null
    private var keyFor: Pair<String, String>? = null

    fun loadRecipients() {
        if (recipients.value != null) return
        viewModelScope.launch {
            try {
                recipients.value = client.testRecipients()
                recipientsError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                recipientsError.value = UiError.from(e.error)
            } catch (e: IOException) {
                recipientsError.value = UiError(UiError.NETWORK)
            } catch (e: Exception) {
                recipientsError.value = UiError(UiError.GENERIC)
            }
        }
    }

    fun send(recipientId: String, text: String) {
        if (job?.isActive == true) return
        if (!TestMessagePolling.textValid(text)) return
        val pair = recipientId to text
        if (keyFor != pair) {
            key = UUID.randomUUID().toString()
            keyFor = pair
        }
        val idempotencyKey = checkNotNull(key)
        state.value = TestUiState.OnItsWay
        job = viewModelScope.launch {
            val outcome = try {
                TestMessagePolling.run(
                    client = client,
                    idempotencyKey = idempotencyKey,
                    recipientId = recipientId,
                    text = text,
                    delayMs = { delay(it) },
                    nowMs = { System.currentTimeMillis() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestOutcome.Refused(UiError.GENERIC)
            }
            // A finished attempt is over: the next tap is a new message.
            if (outcome !is TestOutcome.Refused) {
                key = null
                keyFor = null
            }
            state.value = TestUiState.Done(outcome)
        }
    }

    fun reset() {
        if (job?.isActive != true) state.value = TestUiState.Idle
    }
}
