package me.capcom.smsgateway.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.account.AppClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The Numbers step (F1-6): the SIMs of this phone, each verified by one SMS.
 * State is the pure [NumberRow] list; every platform call goes through [NumbersVerifier].
 */
class NumbersViewModel(application: Application) : AndroidViewModel(application), KoinComponent {

    private val client: AppClient by inject()

    val rows = MutableLiveData<List<NumberRow>>(emptyList())

    /** True once the SIMs were read (so an empty list means "no SIM", not "not loaded"). */
    val loaded = MutableLiveData(false)

    private var started = false

    private val verifier = NumbersVerifier(
        client = client,
        sender = SimSmsSender(application),
        delayMs = { delay(it) },
        nowMs = { System.currentTimeMillis() },
        guard = ProofAttemptGuard(PrefsAttemptLog(application)) { System.currentTimeMillis() },
        // The real runtime grant, read at each check; never a cached or assumed value.
        canSendSms = { AccountPermissions.canSendSms(application) },
    )

    /** Reads the SIM cards and what the platform already verified. Idempotent. */
    fun load() {
        if (started) return
        started = true
        viewModelScope.launch {
            val sims = SubscriptionsHelper.getActiveSimCards(getApplication<Application>()).map {
                SimSlot(it.simNumber, it.carrierName, it.phoneNumber, it.countryIso)
            }
            val lines = try {
                client.lines()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            rows.value = NumbersState.rows(sims, lines)
            loaded.value = true
        }
    }

    fun tick(slot: Int, ticked: Boolean) = change(slot) { it.copy(ticked = ticked) }

    /** The typed number is kept without redrawing the screen. */
    fun type(slot: Int, text: String) {
        rows.value = NumbersState.update(rows.value.orEmpty(), slot) { it.copy(typed = text) }
    }

    /** One tap, one SMS per ticked slot, all slots at the same time. */
    fun verifyTicked() {
        val current = rows.value.orEmpty()
        if (!NumbersState.canVerify(current)) return
        val targets = current.filter { it.ticked && !it.verified }
        // SEND_SMS first: without it nothing is started, the keeper is asked to allow it.
        if (!AccountPermissions.canSendSms(getApplication<Application>())) {
            permissionDenied()
            return
        }
        // Mark them busy before the first suspension, so a second tap finds them running.
        targets.forEach { target -> change(target.slot) { it.copy(phase = RowPhase.STARTING) } }
        viewModelScope.launch {
            targets.map { target ->
                async {
                    val result = try {
                        verifier.verify(target) { phase -> change(target.slot) { it.copy(phase = phase) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        target.copy(phase = RowPhase.FAILED, errorCode = UiError.GENERIC)
                    }
                    change(target.slot) { result.copy(typed = it.typed) }
                }
            }.awaitAll()
        }
    }

    /** SEND_SMS is not granted: the ticked rows say so; nothing was started and nothing was sent. */
    fun permissionDenied() {
        rows.value = NumbersState.markPermissionNeeded(rows.value.orEmpty())
    }

    private fun change(slot: Int, change: (NumberRow) -> NumberRow) {
        rows.value = NumbersState.update(rows.value.orEmpty(), slot, change)
    }
}
