package me.capcom.smsgateway.modules.messages

/**
 * The last check before a message is handed to the radio. A pause that lands while the send
 * loop waits (rate limit, work hours, send interval) must not let one more message go.
 *
 * @param isPaused the pause gate; it fails closed (an unreadable state is "paused"), and an
 * exception from it also counts as paused.
 * @return the result of [send], or `null` when paused ([send] was not called).
 */
internal inline fun sendUnlessPaused(isPaused: () -> Boolean, send: () -> Boolean): Boolean? {
    val paused = try {
        isPaused()
    } catch (e: Exception) {
        true
    }
    return if (paused) null else send()
}
