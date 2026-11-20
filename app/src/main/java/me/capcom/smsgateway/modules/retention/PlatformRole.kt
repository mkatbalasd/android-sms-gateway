package me.capcom.smsgateway.modules.retention

import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent

/**
 * The explicit platform-phone role (default CLOSED). It has exactly one source: the webhook list
 * the server hands this phone (GET /webhooks, WebhooksUpdateWorker). Nothing the local server
 * accepts (PATCH /settings, POST /webhooks) and no settings import can open it, because a
 * customer phone must never be talked into keeping every SMS of both SIMs.
 *
 * The role is a device-scoped MARKER webhook: event `system:ping`, and a url whose query is
 * exactly `mnaseb_role=platform_phone`. The pinned gateway server (v1.51.1) drops unknown
 * settings sections but stores and returns webhooks, which is why the marker rides on one.
 */
object PlatformRole {
    const val MARKER_QUERY = "mnaseb_role=platform_phone"
    private val MARKER_EVENT = WebHookEvent.SystemPing

    /** One hook as seen by the role check ([deviceId] null = the pull did not say). */
    data class Hook(
        val event: WebHookEvent,
        val url: String,
        val deviceId: String?,
        val source: EntitySource,
    )

    /**
     * True only for an absolute http(s) URL with a host whose real raw query is exactly the marker
     * and that has no fragment. Parsed with [java.net.URI] (the text after the first `?` inside a
     * fragment is not a query); the raw query is compared, so an encoded variant never matches.
     * A malformed URL is false and never throws.
     */
    fun isMarkerUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        return (scheme == "https" || scheme == "http") &&
            !uri.host.isNullOrEmpty() &&
            uri.rawFragment == null &&
            uri.rawQuery == MARKER_QUERY
    }

    /**
     * True when one of [hooks] is the marker for this device. Only a hook pulled from the server
     * (source Cloud) counts: a Local hook (written through the local server) never does. When the
     * pull returns a deviceId it must equal [ownDeviceId] (an unknown own id never matches);
     * when the pull returns none the account-scoped marker is the role (the role is then per
     * gateway account, which is how the platform phone's account is used).
     */
    fun fromWebhooks(hooks: List<Hook>, ownDeviceId: String?): Boolean = hooks.any {
        it.source == EntitySource.Cloud &&
            it.event == MARKER_EVENT &&
            isMarkerUrl(it.url) &&
            (it.deviceId == null || (ownDeviceId != null && it.deviceId == ownDeviceId))
    }

    /**
     * Applies one pull to [store]. A successful pull sets the role (marker present = open, no
     * marker = closed). A pull that throws leaves the last value untouched and rethrows, so the
     * caller can retry: a failure never opens the role and never closes an open one.
     */
    suspend fun apply(store: ActiveLineStore, ownDeviceId: String?, pull: suspend () -> List<Hook>) {
        val hooks = pull()
        store.setPlatformPhone(fromWebhooks(hooks, ownDeviceId))
    }
}
