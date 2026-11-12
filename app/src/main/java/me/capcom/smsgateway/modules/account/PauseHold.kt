package me.capcom.smsgateway.modules.account

import org.koin.core.context.GlobalContext

/**
 * CARD V2-L23: the owner's «إيقاف مؤقت» of a phone.
 *
 * While the phone is paused the platform holds its messages. The platform no
 * longer expires the messages it already handed to the gateway, so the phone
 * itself fails a message that waited longer than the hold limit ("TTL expired",
 * mapped to EXPIRED and refunded by the platform) instead of sending it late.
 * The limit is the platform's `pause_hold_max_min` from app-home; [PAUSE_HOLD_MAX_MIN]
 * is the fallback.
 */
const val PAUSE_HOLD_MAX_MIN = 60

object PauseHold {

    private const val MINUTE_MS = 60_000L

    /** The limit in force: the platform's own value when it sent one, else [PAUSE_HOLD_MAX_MIN]. */
    fun holdMaxMin(fromPlatform: Int?): Int = fromPlatform?.takeIf { it > 0 } ?: PAUSE_HOLD_MAX_MIN

    /**
     * How long a message has been held: from the later of its creation and the start of
     * the pause. Strictly longer than the limit means it is too old to send.
     */
    fun heldTooLong(createdAtMs: Long, pausedAtMs: Long, nowMs: Long, holdMaxMin: Int): Boolean =
        nowMs - maxOf(createdAtMs, pausedAtMs) > holdMaxMin * MINUTE_MS

    /**
     * The drop rule for a message the gateway hands over after a resume. It applies to
     * messages created before the resume of the last pause interval only; a message
     * created after the resume is an ordinary one. A clock behind the server never
     * makes a message too old.
     */
    fun dropOnPull(
        createdAtMs: Long,
        pausedAtMs: Long?,
        resumedAtMs: Long?,
        nowMs: Long,
        holdMaxMin: Int,
    ): Boolean {
        if (pausedAtMs == null || resumedAtMs == null) return false
        if (createdAtMs >= resumedAtMs) return false
        return heldTooLong(createdAtMs, pausedAtMs, nowMs, holdMaxMin)
    }

    /** The screen is "paused" when the phone remembers it or the platform says so. */
    fun isPaused(local: Boolean, serverPaused: Boolean?): Boolean = local || serverPaused == true

    /**
     * The server says paused and this phone does not know it (a new sign-in, a data clear,
     * a reinstall): the phone takes the pause over. [requestedAtMs] is when the answer was
     * asked for: an answer asked for before this phone's own resume is stale and ignored.
     */
    fun shouldRestore(
        serverPaused: Boolean?,
        store: PauseStore,
        requestedAtMs: Long,
    ): Boolean =
        serverPaused == true && !store.isPaused && (store.resumedAtMs ?: 0L) < requestedAtMs
}

/** What the phone remembers about the last pause; [DevicePauseStore] is the production store. */
interface PauseStore {
    /** Start of the last pause (local clock). Kept after the resume: the drop rule needs it. */
    var pausedAtMs: Long?

    /** When the last pause ended; null while paused. */
    var resumedAtMs: Long?

    /** The platform's `pause_hold_max_min`, last seen in app-home. */
    var holdMaxMin: Int?

    val isPaused: Boolean get() = pausedAtMs != null && resumedAtMs == null
}

/**
 * Reads the pause from every service that could start, pull or send, locked or unlocked.
 * Fails CLOSED (CARD-21 round 7): a pause state that cannot be read (no store yet, a
 * storage fault, the first unlock not reached with nothing copied) counts as "paused", so
 * the phone never sends on a state it could not read. The source is the device-protected
 * [DevicePauseStore], readable before the first unlock.
 */
object PauseGate {
    internal val defaultProvider: () -> Boolean = {
        try {
            GlobalContext.getOrNull()?.get<PauseStore>()?.isPaused ?: true
        } catch (e: Exception) {
            true
        }
    }

    /** Where the answer comes from; a test replaces it. */
    @Volatile
    internal var provider: () -> Boolean = defaultProvider

    fun isPaused(): Boolean = provider()
}

/**
 * The services of the phone as the pause control sees them. A pause stops only the
 * pulling and the sending of messages: reporting (state events, delivery reports,
 * the inbox) keeps running, because state events have no replay.
 */
interface ServiceSwitch {
    /** Pause: no pull, no SSE or push triggered pull, no sending. Reporting stays up. */
    fun pause()

    /** Resume: the full start of every service. */
    fun start()

    /** Makes sure the reporting path is up (it is idempotent); called before the resume sweep. */
    fun keepReporting()
}

/** Reports the state of one local message to the platform, durably (WorkManager in production). */
interface StateReporter {
    fun report(messageId: String)
}

/**
 * Pause and resume. Pause asks the platform first: if the platform cannot be
 * reached the phone keeps running, so the two sides never disagree silently.
 * Resume asks the platform before the services start, for the same reason.
 * Errors ([AccountException], [java.io.IOException]) reach the caller untouched.
 */
class PauseController(
    private val client: AppClient,
    private val store: PauseStore,
    private val services: ServiceSwitch,
    private val sweep: StaleSweep,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun pause() {
        client.pauseDevice()
        // Remembered before the stop, so a crash in between still keeps the pause.
        store.resumedAtMs = null
        store.pausedAtMs = clock()
        services.pause()
    }

    /**
     * Order: the reporting path is made sure, the local queue is swept (its failures are
     * reported at once), then the platform is told, then the services start.
     * Messages created before the resume are judged by the drop rule on every later pull.
     * If the platform cannot be reached nothing started and the phone stays paused: retry.
     */
    suspend fun resume() {
        services.keepReporting()
        sweep.sweep()
        client.resumeDevice()
        store.resumedAtMs = clock()
        services.start()
    }

    /** A pause the platform knows and this phone does not: take it over and stop the services. */
    fun restore(serverPaused: Boolean?, serverPausedAtMs: Long?, requestedAtMs: Long): Boolean {
        if (!PauseHold.shouldRestore(serverPaused, store, requestedAtMs)) return false
        store.resumedAtMs = null
        store.pausedAtMs = serverPausedAtMs ?: clock()
        services.pause()
        return true
    }
}

/** A pending message as the sweep sees it. */
data class PendingStamp(val id: String, val createdAt: Long)

/** The local queue the sweep works on. */
interface PendingStore {
    suspend fun pending(): List<PendingStamp>
    suspend fun fail(id: String, reason: String)
}

/** Marks every pending message that was held longer than the limit Failed ("TTL expired"). */
class StaleSweep(
    private val store: PendingStore,
    private val settings: PauseStore,
    private val reporter: StateReporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Every failure is reported at once through [reporter] (a durable job), not through the
     * event bus alone: that has no replay, and the sweep runs while the phone is still paused.
     * @return how many messages were dropped.
     */
    suspend fun sweep(): Int {
        val pausedAt = settings.pausedAtMs ?: return 0
        val now = clock()
        val holdMax = PauseHold.holdMaxMin(settings.holdMaxMin)
        var dropped = 0
        for (message in store.pending()) {
            if (!PauseHold.heldTooLong(message.createdAt, pausedAt, now, holdMax)) continue
            try {
                store.fail(message.id, REASON)
                dropped++
                reporter.report(message.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One message that cannot be failed must not keep the others waiting to be sent late.
            }
        }
        return dropped
    }

    /**
     * rev-fork24-1 L6 (in-flight money): the same sweep, run while the phone is paused and
     * not only at the owner's resume. A phone paused SILENTLY (lost or corrupt pause file)
     * is unknown to the platform, which has already handed it messages and no longer
     * expires them; without this they wait for the owner to tap Resume. Does nothing when
     * the phone is not paused (the kept [PauseStore.pausedAtMs] of an old pause must not
     * fail fresh messages) or when the state cannot be read (it is retried on the next call).
     */
    suspend fun sweepWhilePaused(): Int {
        val paused = try {
            settings.isPaused
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (paused) sweep() else 0
    }

    companion object {
        /** The platform maps this reason to EXPIRED. */
        const val REASON = "TTL expired"
    }
}
