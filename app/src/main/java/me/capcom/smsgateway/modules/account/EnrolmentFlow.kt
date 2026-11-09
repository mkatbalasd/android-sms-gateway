package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the enrolment flow is doing; the linking screen shows it as three ticks. */
sealed class EnrolmentState {
    object Idle : EnrolmentState()

    /** The account may still be provisioning, or the code was refused once: the server is asked again. */
    object RetryMint : EnrolmentState()

    /** Registering this phone on the gateway account with the one-time code. */
    object Registering : EnrolmentState()

    /** Telling the platform which gateway device this is. */
    object Completing : EnrolmentState()

    object Linked : EnrolmentState()

    /** [reason] is a stable code (a server error code or one of [EnrolmentFlow.Reasons]); never a secret. */
    data class Failed(val reason: String) : EnrolmentState()
}

/** The three operations the flow drives. The app implements them with Android and Ktor. */
interface EnrolmentSteps {
    sealed class Mint {
        data class Ok(val enrolment: Enrolment) : Mint()

        /** The gateway account is not ready yet (409 GATEWAY_ACCOUNT_NOT_READY, or `enrolment: null`). */
        object NotReady : Mint()
        data class Error(val reason: String) : Mint()
    }

    sealed class Register {
        data class Ok(val gatewayDeviceId: String) : Register()

        /** The gateway refused the code (401): used, expired or wrong. */
        object CodeRefused : Register()
        data class Error(val reason: String) : Register()
    }

    sealed class Complete {
        object Ok : Complete()

        /** The listing of the gateway does not show the device yet: retry. */
        object NotListed : Complete()

        /** A network error or a 5xx: retry within the window. */
        data class Transient(val reason: String) : Complete()

        /** Any other refusal (the other 409s, a dead session): the enrolment must restart. */
        data class Terminal(val reason: String) : Complete()
    }

    suspend fun mint(): Mint
    suspend fun register(enrolment: Enrolment): Register
    suspend fun complete(gatewayDeviceId: String, nonce: String): Complete

    /** The registration whose complete is not confirmed yet, if the app died or gave up unsure. */
    suspend fun pendingCompletion(): PendingCompletion?

    /** Stored before the first complete, so a crash can resume it. */
    suspend fun savePendingCompletion(pending: PendingCompletion)

    /**
     * Undoes a registration whose completion was refused for good, and forgets
     * the pending completion. A successful complete clears it by itself.
     */
    suspend fun rollback()
}

/**
 * Pure state machine of the self-enrolment (CARD-08, server side CARD-07):
 *
 * `Idle -> [RetryMint] -> Registering -> Completing -> Linked | Failed(reason)`
 *
 * - no enrolment yet: ask the server every 8 s for about a minute (at most 9
 *   calls; the route allows 10 a minute);
 * - the gateway refuses the code (401): ask the server once more, never loop
 *   (the proxy allows 5 registrations a minute per address);
 * - `complete` that answers "not listed" or hits a transient error: retry every
 *   3 s for 60 s;
 * - any other refusal: stop, undo the registration, report the reason;
 * - the window ends on network errors or 5xx (the outcome is unknown, the
 *   server may have committed): nothing is undone, the pending completion stays
 *   stored and a later run (retry, or the next app start) resumes complete.
 *
 * Time only passes through [pause], so tests run without waiting.
 */
class EnrolmentFlow(
    private val steps: EnrolmentSteps,
    private val pause: suspend (millis: Long) -> Unit,
) {
    object Reasons {
        const val NOT_READY = "GATEWAY_ACCOUNT_NOT_READY"
        const val CODE_REFUSED = "ENROLMENT_CODE_REFUSED"
        const val NOT_LISTED = "ENROLMENT_NOT_LISTED"
        const val NETWORK = "NETWORK"
    }

    private val mutableState = MutableStateFlow<EnrolmentState>(EnrolmentState.Idle)
    val state: StateFlow<EnrolmentState> get() = mutableState

    /**
     * Runs the whole enrolment. [initial] is the enrolment the verify call
     * carried, or null when it carried none. Returns the final state.
     */
    suspend fun run(initial: Enrolment?): EnrolmentState {
        if (initial == null) {
            // A registration that was never confirmed: finish it, never register again.
            steps.pendingCompletion()?.let { return complete(it.gatewayDeviceId, it.nonce) }
        }
        var enrolment = initial
        if (enrolment == null) {
            enrolment = when (val minted = pollMint()) {
                is EnrolmentSteps.Mint.Ok -> minted.enrolment
                EnrolmentSteps.Mint.NotReady -> return finish(EnrolmentState.Failed(Reasons.NOT_READY))
                is EnrolmentSteps.Mint.Error -> return finish(EnrolmentState.Failed(minted.reason))
            }
        }

        var remintUsed = false
        var registeredId: String? = null
        while (registeredId == null) {
            mutableState.value = EnrolmentState.Registering
            when (val registered = steps.register(enrolment!!)) {
                is EnrolmentSteps.Register.Ok -> registeredId = registered.gatewayDeviceId

                EnrolmentSteps.Register.CodeRefused -> {
                    if (remintUsed) {
                        return finish(EnrolmentState.Failed(Reasons.CODE_REFUSED))
                    }
                    remintUsed = true
                    enrolment = when (val minted = pollMint()) {
                        is EnrolmentSteps.Mint.Ok -> minted.enrolment
                        EnrolmentSteps.Mint.NotReady ->
                            return finish(EnrolmentState.Failed(Reasons.NOT_READY))

                        is EnrolmentSteps.Mint.Error ->
                            return finish(EnrolmentState.Failed(minted.reason))
                    }
                }

                is EnrolmentSteps.Register.Error ->
                    return finish(EnrolmentState.Failed(registered.reason))
            }
        }

        val nonce = enrolment!!.nonce.orEmpty()
        steps.savePendingCompletion(PendingCompletion(checkNotNull(registeredId), nonce))
        return complete(registeredId, nonce)
    }

    private suspend fun complete(gatewayDeviceId: String, nonce: String): EnrolmentState {
        mutableState.value = EnrolmentState.Completing
        var attempt = 0
        var lastReason = Reasons.NOT_LISTED
        var unknown = false
        while (attempt < COMPLETE_ATTEMPTS) {
            if (attempt > 0) pause(COMPLETE_INTERVAL_MS)
            attempt++
            when (val result = steps.complete(gatewayDeviceId, nonce)) {
                EnrolmentSteps.Complete.Ok -> return finish(EnrolmentState.Linked)
                EnrolmentSteps.Complete.NotListed -> {
                    lastReason = Reasons.NOT_LISTED
                    unknown = false
                }

                is EnrolmentSteps.Complete.Transient -> {
                    lastReason = result.reason
                    unknown = true
                }

                is EnrolmentSteps.Complete.Terminal -> {
                    steps.rollback()
                    return finish(EnrolmentState.Failed(result.reason))
                }
            }
        }
        if (unknown) {
            // The last answer was lost or a 5xx: the server may have committed. Keep the
            // registration and the pending completion; a retry resumes complete.
            return finish(EnrolmentState.Failed(lastReason))
        }
        // The server answered that the device is not listed: the phone is not linked as far
        // as the platform knows, so the registration is undone and a retry starts afresh.
        steps.rollback()
        return finish(EnrolmentState.Failed(lastReason))
    }

    private suspend fun pollMint(): EnrolmentSteps.Mint {
        var attempt = 0
        while (true) {
            mutableState.value = EnrolmentState.RetryMint
            attempt++
            when (val minted = steps.mint()) {
                is EnrolmentSteps.Mint.Ok -> return minted
                is EnrolmentSteps.Mint.Error -> return minted
                EnrolmentSteps.Mint.NotReady -> {
                    if (attempt >= MINT_ATTEMPTS) return minted
                    pause(MINT_INTERVAL_MS)
                }
            }
        }
    }

    private fun finish(state: EnrolmentState): EnrolmentState {
        mutableState.value = state
        return state
    }

    companion object {
        // POST /v1/app/enrolment is throttled to 10 calls a minute (CARD-07) and the
        // SANDBOX tenant to 10 requests a second: one call every 8 s is 7.5 a minute.
        const val MINT_INTERVAL_MS = 8_000L
        const val MINT_ATTEMPTS = 9 // the first call plus 8 more, 64 s in all
        const val COMPLETE_INTERVAL_MS = 3_000L
        const val COMPLETE_ATTEMPTS = 21 // the first call plus 20 more, 60 s in all
    }
}
