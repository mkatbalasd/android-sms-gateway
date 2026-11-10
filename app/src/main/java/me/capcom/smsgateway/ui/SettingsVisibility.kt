package me.capcom.smsgateway.ui

/**
 * Which entries of `root_preferences.xml` a customer sees (CARD-12, V2-D11).
 *
 * An install linked through the account (the customer phone) hides the upstream
 * settings and shows «فصل هذا الهاتف». The QR-path install (the platform phone)
 * keeps every screen. Entries are hidden, never removed: upstream code still
 * reads their defaults.
 */
object SettingsVisibility {

    const val DEVICE = "settings.device"
    const val CLOUD_SERVER = "settings.cloud_server"
    const val MESSAGES = "settings.messages"
    const val INBOX = "settings.inbox"
    const val WEBHOOKS = "settings.webhooks"
    const val ENCRYPTION = "settings.encryption"
    const val PING = "settings.ping"
    const val LOGS = "settings.logs"

    /** Shown only for an app-enrolled install. */
    const val UNLINK_CATEGORY = "account.category"
    const val UNLINK = "account.unlink"

    /** Kept for everybody: battery exemption and language ("system") and the version ("information"). */
    val KEPT_KEYS = listOf("system", "system.disable_battery_optimizations", "app.language")

    private val HIDDEN_FOR_APP_ENROLLED =
        listOf(DEVICE, CLOUD_SERVER, MESSAGES, INBOX, WEBHOOKS, ENCRYPTION, PING, LOGS)

    /** The keys to hide. Nothing is hidden on the QR-path install. */
    fun hiddenKeys(appEnrolled: Boolean): List<String> =
        if (appEnrolled) HIDDEN_FOR_APP_ENROLLED else emptyList()

    /** The keys to show beyond the defaults of the XML. */
    fun shownKeys(appEnrolled: Boolean): List<String> =
        if (appEnrolled) listOf(UNLINK_CATEGORY) else emptyList()

    /** Every key this object mentions: each must exist in `root_preferences.xml`. */
    fun allKeys(): List<String> =
        HIDDEN_FOR_APP_ENROLLED + listOf(UNLINK_CATEGORY, UNLINK) + KEPT_KEYS
}
