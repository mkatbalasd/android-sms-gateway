package me.capcom.smsgateway.modules.gateway.workers

import me.capcom.smsgateway.modules.gateway.GatewayApi

/**
 * V2-L47: remembers the last state report that the server accepted, per message id, so that two
 * [SendStateWorker] runs that find the same stored state (a late run for "Processed" and the run
 * for "Sent" both read "Sent") post it once.
 *
 * Only a report that was posted successfully is recorded ([markPosted] after the call returns), so
 * a failed post is retried and is never suppressed. A report that differs in any field (message
 * state, a recipient state or error, the state timestamps) is a new transition and is posted.
 * The memory is per process and bounded: after a restart the first run posts again, which is
 * at-least-once and harmless (the platform ignores a repeated state).
 */
class StateReportDedup(private val maxEntries: Int = 256) {
    private val lastPosted = object : LinkedHashMap<String, GatewayApi.MessagePatchRequest>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GatewayApi.MessagePatchRequest>?) =
            size > maxEntries
    }

    @Synchronized
    fun isDuplicate(request: GatewayApi.MessagePatchRequest): Boolean =
        lastPosted[request.id] == request

    @Synchronized
    fun markPosted(request: GatewayApi.MessagePatchRequest) {
        lastPosted[request.id] = request
    }

    companion object {
        /** Process-wide instance used by [SendStateWorker]. */
        val shared = StateReportDedup()
    }
}
