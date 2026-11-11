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

    /** Boot, app start and the keep-alive paths start the services only when this is true. */
    fun shouldAutostart(autostartEnabled: Boolean, paused: Boolean): Boolean =
        autostartEnabled && !paused

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

/** What the phone remembers about the last pause; [AccountSettings] is the production store. */
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
 * Reads the pause from every service that could start or pull. Fails open: a store that
 * cannot be read is "not paused", so a storage fault never silences a phone for good.
 */
object PauseGate {
    fun isPaused(): Boolean = try {
        GlobalContext.getOrNull()?.get<AccountSettings>()?.isPaused == true
    } catch (e: Exception) {
        false
    }
}

/** The services of the phone as the pause control sees them. */
interface ServiceSwitch {
    fun start()
    fun stop()
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
        // Remembered before the stop, so a crash in between still blocks the autostart.
        store.resumedAtMs = null
        store.pausedAtMs = clock()
        services.stop()
    }

    /**
     * Order: the local queue is swept, then the platform is told, then the services start.
     * Messages created before the resume are judged by the drop rule on every later pull.
     * If the platform cannot be reached nothing started and the phone stays paused: retry.
     */
    suspend fun resume() {
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
        services.stop()
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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** @return how many messages were dropped. */
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One message that cannot be failed must not keep the others waiting to be sent late.
            }
        }
        return dropped
    }

    companion object {
        /** The platform maps this reason to EXPIRED. */
        const val REASON = "TTL expired"
    }
}
