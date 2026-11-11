package me.capcom.smsgateway.modules.gateway

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the in-process runner executes. Each function throws on failure; the scheduler retries. */
interface LockedTasks {
    suspend fun pull()

    suspend fun sendPending()

    suspend fun sendState(messageId: String)

    /** @return the delay in ms until the next queued webhook attempt, or `null` when none is waiting. */
    suspend fun drainWebhooks(): Long?
}

/** Exponential backoff: [baseMs], doubled per attempt, capped at [maxMs]. */
class Backoff(
    private val baseMs: Long = 5_000L,
    private val maxMs: Long = 300_000L,
) {
    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 20)
        return minOf(maxMs, baseMs shl shift)
    }
}

/**
 * CARD-21: runs the pipeline inside the process, with plain coroutines, for the time
 * WorkManager cannot run (before the first unlock). No WorkManager call is made here.
 *
 * - one pull at a time, one send run at a time, one webhook drain at a time; a request
 *   that arrives while one is running makes it run once more afterwards;
 * - failures are retried with exponential backoff (state reports give up after
 *   [stateMaxAttempts], like the worker did; the others retry until they succeed);
 * - state is kept by the callers in Room (device-protected), not here.
 */
class InProcessScheduler(
    private val scope: CoroutineScope,
    private val tasks: () -> LockedTasks,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val backoff: Backoff = Backoff(),
    private val stateMaxAttempts: Int = 10,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : WorkScheduler {

    private val lock = Any()

    /** Single-flight loop with retry. */
    private inner class Loop(
        private val name: String,
        private val maxAttempts: Int,
        private val onFinished: () -> Unit = {},
        private val body: suspend () -> Unit,
    ) {
        private var running = false
        private var again = false

        val isRunning: Boolean
            get() = synchronized(lock) { running }

        fun trigger() {
            synchronized(lock) {
                if (running) {
                    again = true
                    return
                }
                running = true
            }
            scope.launch { loop() }
        }

        private suspend fun loop() {
            var released = false
            var attempt = 0
            try {
                while (true) {
                    val failure: Throwable? = try {
                        body()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        e
                    }

                    if (failure != null) {
                        onError(name, failure)
                        attempt++
                        if (attempt >= maxAttempts) {
                            return
                        }
                        delay(backoff.delayMs(attempt - 1))
                        continue
                    }

                    attempt = 0
                    val finished = synchronized(lock) {
                        if (again) {
                            again = false
                            false
                        } else {
                            running = false
                            onFinished()
                            true
                        }
                    }
                    if (finished) {
                        released = true
                        return
                    }
                }
            } finally {
                if (!released) {
                    synchronized(lock) {
                        running = false
                        again = false
                        onFinished()
                    }
                }
            }
        }
    }

    /** One pending delayed trigger. */
    private inner class DelayedTrigger(private val fire: () -> Unit) {
        private var at: Long? = null
        private var job: Job? = null

        fun schedule(target: Long, force: Boolean) {
            synchronized(lock) {
                val current = at
                if (current != null && !force && current <= target) return

                job?.cancel()
                at = target
                job = scope.launch {
                    delay(maxOf(0L, target - now()))
                    val mine = synchronized(lock) {
                        if (at == target) {
                            at = null
                            job = null
                            true
                        } else {
                            false
                        }
                    }
                    if (mine) fire()
                }
            }
        }
    }

    private val pull = Loop("pull", Int.MAX_VALUE) { tasks().pull() }

    private val send = Loop("send", Int.MAX_VALUE) { tasks().sendPending() }
    private val sendDelayed = DelayedTrigger { send.trigger() }

    private val webhooks: Loop by lazy {
        Loop("webhooks", Int.MAX_VALUE) {
            val next = tasks().drainWebhooks()
            if (next != null) {
                webhooksDelayed.schedule(now() + maxOf(0L, next), force = false)
            }
        }
    }
    private val webhooksDelayed: DelayedTrigger by lazy { DelayedTrigger { webhooks.trigger() } }

    private val stateLoops = HashMap<String, Loop>()

    override fun pullMessages() = pull.trigger()

    override fun sendMessages(force: Boolean, scheduleAt: Long) {
        if (scheduleAt - now() > 0) {
            sendDelayed.schedule(scheduleAt, force)
        } else {
            send.trigger()
        }
    }

    override fun sendState(messageId: String) {
        val loop = synchronized(lock) {
            stateLoops.getOrPut(messageId) {
                Loop(
                    "state",
                    stateMaxAttempts,
                    onFinished = { stateLoops.remove(messageId) },
                ) { tasks().sendState(messageId) }
            }
        }
        loop.trigger()
    }

    override fun processWebhookQueue(internetRequired: Boolean) = webhooks.trigger()

    // Not needed before the first unlock: the lists last synced stay in the device-protected
    // database, and the full start after the unlock refreshes both.
    override fun updateWebhooks() = Unit

    override fun updateSettings() = Unit

    /** True while a pull, a send run, a webhook drain or a state report is running or waiting to retry. */
    fun isBusy(): Boolean = synchronized(lock) {
        pull.isRunning || send.isRunning || webhooks.isRunning || stateLoops.values.any { it.isRunning }
    }

    /** Cancels everything that is running or scheduled. */
    fun shutdown() {
        scope.coroutineContext[Job]?.cancelChildren()
    }

    /**
     * The unlock handover: lets the running work finish (at most [timeoutMs]), stops this
     * runner, then calls [handover], which starts WorkManager. Call it from a scope other
     * than this scheduler's own.
     */
    suspend fun drainThenHandover(timeoutMs: Long = DRAIN_TIMEOUT_MS, handover: () -> Unit) {
        withTimeoutOrNull(timeoutMs) {
            while (isBusy()) {
                delay(POLL_MS)
            }
        }
        shutdown()
        handover()
    }

    companion object {
        const val DRAIN_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 100L

        fun create(tasks: () -> LockedTasks): InProcessScheduler = InProcessScheduler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            tasks = tasks,
            // the class name only: a failure text can carry a number or a message body
            onError = { name, e -> Log.w("LockedRunner", "$name failed: ${e.javaClass.simpleName}") },
        )
    }
}
