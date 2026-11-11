package me.capcom.smsgateway.helpers

/**
 * Decides what to do about a quiet SSE stream (CARD V2-L19). Pure: no Android types, the
 * time comes from [clock] (milliseconds, monotonic).
 *
 * A half-open connection raises no error: OkHttp keeps waiting up to its 1 h read timeout
 * while nothing arrives. The stream is therefore treated as dead after [reconnectAfterMs]
 * of silence, and while it is merely quiet (more than [pullAfterMs]) the caller pulls the
 * pending messages itself instead of waiting for WorkManager's 15-minute floor.
 */
class SseWatchdog(
    private val clock: () -> Long,
    private val reconnectAfterMs: Long = WATCHDOG_SILENCE_MS,
    private val pullAfterMs: Long = PULL_SILENCE_MS,
) {
    enum class Action { NONE, PULL, RECONNECT }

    @Volatile
    private var lastActivityAt = clock()

    /** Something arrived (bytes, an event, or a fresh connection): the silence restarts. */
    fun onActivity() {
        lastActivityAt = clock()
    }

    /** Milliseconds since the last activity. */
    fun silenceMs(): Long = clock() - lastActivityAt

    /**
     * What the periodic tick should do now. [Action.RECONNECT] also restarts the clock, so
     * the new connection gets a full window before the watchdog fires again.
     */
    fun check(): Action {
        val silent = silenceMs()
        return when {
            silent > reconnectAfterMs -> {
                onActivity()
                Action.RECONNECT
            }

            silent > pullAfterMs -> Action.PULL
            else -> Action.NONE
        }
    }

    companion object {
        /** Nothing for this long: the stream is presumed half-open, reconnect. */
        const val WATCHDOG_SILENCE_MS = 3 * 60_000L

        /** Quiet for this long: pull the pending messages on the tick. */
        const val PULL_SILENCE_MS = 60_000L

        /** How often the watchdog looks. */
        const val TICK_MS = 60_000L
    }
}
