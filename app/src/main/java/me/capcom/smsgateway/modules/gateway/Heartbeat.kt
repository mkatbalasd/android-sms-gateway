package me.capcom.smsgateway.modules.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CARD-21: how often the phone shows itself to the platform. The platform marks a
 * phone offline after 15 minutes without contact and polls every 5 minutes, so one
 * authenticated pull every 5 minutes gives three chances per window.
 */
object HeartbeatSchedule {
    const val DEFAULT_SECONDS = 300
    const val FLOOR_SECONDS = 60

    /** [configuredSeconds] is the ping interval the server may have imported; `null` means default. */
    fun intervalMs(configuredSeconds: Int?): Long =
        maxOf(configuredSeconds ?: DEFAULT_SECONDS, FLOOR_SECONDS) * 1000L
}

/**
 * Runs [beat] at start and then every [HeartbeatSchedule] interval, independent of
 * WorkManager (it works before the first unlock). A failed beat is simply the next one's problem.
 */
class Heartbeat(
    private val scope: CoroutineScope,
    private val configuredSeconds: () -> Int?,
    private val beat: suspend () -> Unit,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                try {
                    beat()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // the next beat retries
                }
                delay(HeartbeatSchedule.intervalMs(configuredSeconds()))
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
