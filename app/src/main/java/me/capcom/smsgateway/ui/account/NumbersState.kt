package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.account.AppLine
import me.capcom.smsgateway.modules.account.LineVerifyStart
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** One SIM the phone reports. [slot] is the platform's 1-based number; [readableNumber] is only a hint. */
data class SimSlot(val slot: Int, val carrier: String?, val readableNumber: String?)

enum class RowPhase {
    IDLE,
    STARTING,
    SENDING,
    WAITING,
    VERIFIED,

    /** The phone could not send the one verification SMS (no credit, airplane mode). */
    SEND_FAILED,
    TIMED_OUT,

    /** The platform has no line for this country: the keeper must type the number (LOOPBACK). */
    NEEDS_NUMBER,

    /** A check is open on the platform and this screen has no text to resend: retry in [NumberRow.retryMinutes]. */
    OPEN_PROOF,
    FAILED,
}

/** One row of the Numbers screen. Holds no SMS text: the token is sent by the phone and forgotten. */
data class NumberRow(
    val slot: Int,
    val carrier: String?,
    val readableNumber: String?,
    val typed: String = "",
    val ticked: Boolean = true,
    val phase: RowPhase = RowPhase.IDLE,
    val method: String? = null,
    val errorCode: String? = null,
    val verifiedMasked: String? = null,
    val verifiedAt: String? = null,
    val retryMinutes: Int? = null,
) {
    val busy: Boolean
        get() = phase == RowPhase.STARTING || phase == RowPhase.SENDING || phase == RowPhase.WAITING

    val verified: Boolean get() = phase == RowPhase.VERIFIED

    /** Proved by the phone texting itself: weaker than MO, shown as «تحقق ذاتي (أضعف)». */
    val weak: Boolean get() = verified && method == LineVerifyStart.METHOD_LOOPBACK

    /** The number field is shown when Android hides the number or the platform asked for it. */
    val asksForNumber: Boolean
        get() = readableNumber.isNullOrBlank() || phase == RowPhase.NEEDS_NUMBER
}

object NumbersState {

    /** Slots to rows. A slot the platform already verified starts verified and unticked. */
    fun rows(sims: List<SimSlot>, lines: List<AppLine>): List<NumberRow> =
        sims.sortedBy { it.slot }.map { sim ->
            val proved = lines.firstOrNull { it.slot == sim.slot && it.isVerified }
            if (proved != null) {
                NumberRow(
                    slot = sim.slot,
                    carrier = sim.carrier,
                    readableNumber = sim.readableNumber,
                    ticked = false,
                    phase = RowPhase.VERIFIED,
                    method = proved.method,
                    verifiedMasked = proved.msisdnMasked,
                    verifiedAt = proved.verifiedAt,
                )
            } else {
                NumberRow(sim.slot, sim.carrier, sim.readableNumber)
            }
        }

    /** [تحقق الآن] is enabled when something is ticked, not verified and nothing is running. */
    fun canVerify(rows: List<NumberRow>): Boolean =
        rows.none { it.busy } && rows.any { it.ticked && !it.verified }

    /** The keeper may continue when at least one number is verified and nothing is running. */
    fun canContinue(rows: List<NumberRow>): Boolean =
        rows.none { it.busy } && rows.any { it.verified }

    fun update(rows: List<NumberRow>, slot: Int, change: (NumberRow) -> NumberRow): List<NumberRow> =
        rows.map { if (it.slot == slot) change(it) else it }
}

/** Sends one SMS from a SIM slot (1-based). Returns false when the phone could not send it. */
interface LineSmsSender {
    /**
     * False or an exception: the radio definitely did not send. [SendOutcomeUnknown]: the
     * hand-over was made but the radio never answered, so the system may still deliver it.
     */
    suspend fun send(slot: Int, to: String, text: String): Boolean
}

/** The SMS was handed to the system and its sent-status never came: it may still go out later. */
class SendOutcomeUnknown : Exception("sent status not received")

/**
 * The proof of one SIM: ask the platform to start it, send the one SMS from that slot,
 * then poll `GET /v1/app/lines` every [pollIntervalMs] for up to [timeoutMs].
 * One call is one SMS: it never retries the send.
 */
class NumbersVerifier(
    private val client: AppClient,
    private val sender: LineSmsSender,
    private val delayMs: suspend (Long) -> Unit,
    private val nowMs: () -> Long,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val guard: ProofAttemptGuard = ProofAttemptGuard(MemoryAttemptLog(), nowMs),
) {

    /** Slots with a proof in progress: a second trigger on the same slot is a no-op. */
    private val running = HashSet<Int>()

    /**
     * A started proof whose SMS could not be sent. Kept in memory only, for one resend while
     * the platform's token is still valid (it blocks a new start until it expires); never
     * stored, logged or shown.
     */
    private class Unsent(val method: String, val to: String, val text: String, val validUntilMs: Long) {
        override fun toString() = "Unsent(redacted)"
    }

    private val unsent = HashMap<Int, Unsent>()

    /** Runs the proof for [row] and returns the row in its final phase. [onPhase] reports the steps. */
    suspend fun verify(row: NumberRow, onPhase: (RowPhase) -> Unit = {}): NumberRow {
        synchronized(running) { if (!running.add(row.slot)) return row }
        try {
            return verifyOnce(row, onPhase)
        } finally {
            synchronized(running) { running.remove(row.slot) }
        }
    }

    private suspend fun verifyOnce(row: NumberRow, onPhase: (RowPhase) -> Unit): NumberRow {
        onPhase(RowPhase.STARTING)

        // The last tap's SMS did not leave and its token is still open: send that same text again.
        val kept = unsent[row.slot]
        if (kept != null) {
            if (nowMs() < kept.validUntilMs) return send(row, kept, onPhase)
            unsent.remove(row.slot)
        }

        val start = try {
            client.verifyLine(row.slot, row.typed)
        } catch (e: AccountException) {
            return when (e.error.code) {
                // The platform has no line for the country: only the typed number can prove the SIM.
                "NO_PLATFORM_LINE" -> row.copy(phase = RowPhase.NEEDS_NUMBER, errorCode = e.error.code)
                // A check is open and this screen holds no text to resend (a new screen, or the
                // text was sent): say so and when to retry. The platform's token lives 10 minutes.
                "LINE_PROOF_OPEN" -> row.copy(
                    phase = RowPhase.OPEN_PROOF,
                    errorCode = e.error.code,
                    retryMinutes = PROOF_TTL_MINUTES,
                )

                else -> row.copy(phase = RowPhase.FAILED, errorCode = e.error.code)
            }
        } catch (e: IOException) {
            return row.copy(phase = RowPhase.FAILED, errorCode = UiError.NETWORK)
        }

        val method = start.method
        val to = start.to
        val text = start.text
        if (method !in KNOWN_METHODS || text.isNullOrBlank() || !plausibleDestination(to)) {
            return row.copy(phase = RowPhase.FAILED, errorCode = "BAD_RESPONSE")
        }
        val ttlMs = (start.expiresIn ?: PROOF_TTL_MINUTES * 60L).coerceIn(1L, 3_600L) * 1000L
        return send(row, Unsent(method!!, to!!, text, nowMs() + ttlMs), onPhase)
    }

    private suspend fun send(row: NumberRow, proof: Unsent, onPhase: (RowPhase) -> Unit): NumberRow {
        // One token, one SMS: record the attempt durably before the radio is asked.
        val key = ProofAttemptGuard.key(row.slot, proof.to, proof.text)
        if (!guard.tryBegin(key)) {
            unsent.remove(row.slot)
            return row.copy(phase = RowPhase.OPEN_PROOF, method = proof.method, retryMinutes = PROOF_TTL_MINUTES)
        }
        onPhase(RowPhase.SENDING)
        // MO: to the platform line. LOOPBACK: to the typed number, which the platform returns as `to`.
        val sent = try {
            sender.send(row.slot, proof.to, proof.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendOutcomeUnknown) {
            // It may still leave the phone: never resend it, never give the attempt back.
            unsent.remove(row.slot)
            return await(row.copy(method = proof.method), onPhase)
        } catch (e: Exception) {
            false
        }
        if (!sent) {
            // A definite failure: this one text may be resent once, by an explicit next tap.
            guard.release(key)
            unsent[row.slot] = proof
            return row.copy(phase = RowPhase.SEND_FAILED, method = proof.method)
        }
        unsent.remove(row.slot)
        return await(row.copy(method = proof.method), onPhase)
    }

    private suspend fun await(row: NumberRow, onPhase: (RowPhase) -> Unit): NumberRow {
        onPhase(RowPhase.WAITING)
        val began = nowMs()
        while (nowMs() - began < timeoutMs) {
            delayMs(pollIntervalMs)
            val lines = try {
                client.lines()
            } catch (e: IOException) {
                continue // a dropped request is retried on the next tick
            } catch (e: AccountException) {
                return row.copy(phase = RowPhase.FAILED, errorCode = e.error.code)
            }
            val proved = lines.firstOrNull {
                it.slot == row.slot && it.isVerified && it.verifiedAt != row.verifiedAt
            }
            if (proved != null) {
                return row.copy(
                    phase = RowPhase.VERIFIED,
                    ticked = false,
                    method = proved.method ?: row.method,
                    verifiedMasked = proved.msisdnMasked,
                    verifiedAt = proved.verifiedAt,
                    errorCode = null,
                )
            }
        }
        return row.copy(phase = RowPhase.TIMED_OUT)
    }

    companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val TIMEOUT_MS = 90_000L
        private val KNOWN_METHODS = setOf(LineVerifyStart.METHOD_MO, LineVerifyStart.METHOD_LOOPBACK)
        private const val PROOF_TTL_MINUTES = 10
        private val DESTINATION = Regex("^\\+?[0-9]{3,15}$")

        /** A phone number in international form or a short code: digits only, 3 to 15, one optional plus. */
        fun plausibleDestination(to: String?): Boolean = to != null && DESTINATION.matches(to)
    }
}
