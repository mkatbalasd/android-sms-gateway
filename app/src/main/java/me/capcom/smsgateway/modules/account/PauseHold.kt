package me.capcom.smsgateway.modules.account

/**
 * CARD V2-L23: the owner's «إيقاف مؤقت» of a phone.
 *
 * While paused the platform holds the messages of the phone for at most
 * [PAUSE_HOLD_MAX_MIN] minutes, then cancels them (EXPIRED) and gives the
 * balance back. The phone must therefore never send a message that old late.
 */
const val PAUSE_HOLD_MAX_MIN = 60

object PauseHold {

    private const val MINUTE_MS = 60_000L

    /** A message created before this instant is too old to be sent after a pause. */
    fun cutoffMs(nowMs: Long): Long = nowMs - PAUSE_HOLD_MAX_MIN * MINUTE_MS

    /** Strictly older than [PAUSE_HOLD_MAX_MIN] minutes. A clock behind the server never makes a message stale. */
    fun isStale(createdAtMs: Long, nowMs: Long): Boolean = createdAtMs < cutoffMs(nowMs)

    /** The screen is "paused" when the phone remembers it or the platform says so. */
    fun isPaused(pausedAtMs: Long?, serverPaused: Boolean?): Boolean =
        pausedAtMs != null || serverPaused == true

    /** Boot, app start and the keep-alive paths start the services only when this is true. */
    fun shouldAutostart(autostartEnabled: Boolean, pausedAtMs: Long?): Boolean =
        autostartEnabled && pausedAtMs == null
}

/** What the phone remembers about the pause; [AccountSettings] is the production store. */
interface PauseStore {
    /** When the owner paused this phone (local clock), null when it is not paused. */
    var pausedAtMs: Long?

    /** Set on resume: the next pull of messages still drops the stale ones. */
    var sweepAfterResume: Boolean
}

/** The services of the phone as the pause control sees them. */
interface ServiceSwitch {
    fun start()
    fun stop()
}

/**
 * Pause and resume. Pause asks the platform first: if the platform cannot be
 * reached the phone keeps running, so the two sides never disagree silently.
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
        services.stop()
        store.pausedAtMs = clock()
    }

    /**
     * Stale messages are dropped before the services start, so nothing old is
     * sent late. If the platform call fails the flag stays and the screen
     * keeps showing "paused" with a retry; the call is idempotent.
     */
    suspend fun resume() {
        sweep.sweep()
        store.sweepAfterResume = true
        services.start()
        client.resumeDevice()
        store.pausedAtMs = null
    }
}

/** A pending message as the sweep sees it. */
data class PendingStamp(val id: String, val createdAt: Long)

/** The local queue the sweep works on. */
interface PendingStore {
    suspend fun pending(): List<PendingStamp>
    suspend fun fail(id: String, reason: String)
}

/** Marks every pending message older than [PAUSE_HOLD_MAX_MIN] minutes Failed ("TTL expired"). */
class StaleSweep(
    private val store: PendingStore,
    private val settings: PauseStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** @return how many messages were dropped. */
    suspend fun sweep(): Int {
        val now = clock()
        var dropped = 0
        for (message in store.pending()) {
            if (!PauseHold.isStale(message.createdAt, now)) continue
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

    /** Called after a pull of messages: the first one after a resume sweeps once more. */
    suspend fun onPullDone() {
        if (!settings.sweepAfterResume) return
        sweep()
        settings.sweepAfterResume = false
    }

    companion object {
        /** The platform maps this reason to EXPIRED. */
        const val REASON = "TTL expired"
    }
}
