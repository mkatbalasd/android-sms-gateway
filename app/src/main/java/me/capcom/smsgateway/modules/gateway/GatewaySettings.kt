package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.settings.Exporter
import me.capcom.smsgateway.modules.settings.Importer
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * CARD-21: the gateway password, in memory only. [GatewaySettings] is created per injection
 * (a Koin factory), so the value lives here, once per process, and is never written anywhere.
 */
internal object SessionPassword {
    @Volatile
    private var password: String? = null

    fun remember(info: GatewayApi.DeviceRegisterResponse?) {
        password = info?.password
    }

    fun merge(stored: GatewayApi.DeviceRegisterResponse?): GatewayApi.DeviceRegisterResponse? {
        val kept = password ?: return stored
        return stored?.copy(password = kept)
    }

    fun forget() {
        password = null
    }
}

class GatewaySettings(
    private val storage: KeyValueStorage,
) : Exporter, Importer {
    enum class NotificationChannel {
        AUTO,
        SSE_ONLY,
    }

    var enabled: Boolean
        get() = storage.get<Boolean>(ENABLED) ?: false
        set(value) = storage.set(ENABLED, value)

    val deviceId: String?
        get() = registrationInfo?.id

    /**
     * CARD-21: the gateway password is never stored (it would sit in device-protected
     * storage). It is kept in memory only, for the rest of this process, so the screens that
     * were given it at registration or at a password change still work in this session
     * ("Set (hidden)", login code without a prompt). After a restart it is gone and the
     * screens ask for it, as they do for an account whose password was never shown.
     */
    var registrationInfo: GatewayApi.DeviceRegisterResponse?
        get() = SessionPassword.merge(storage.get<GatewayApi.DeviceRegisterResponse>(REGISTRATION_INFO))
        set(value) {
            SessionPassword.remember(value)
            storage.set(REGISTRATION_INFO, value?.copy(password = null))
        }

    /**
     * When the gateway last got a good answer from the server (a pull, a device update, a
     * settings read). The home screen shows the phone's connection from this, never from
     * the app session. Not a secret; it is a time only.
     */
    var lastContactAtMs: Long?
        get() = storage.get<Long>(LAST_CONTACT_AT_MS)
        private set(value) = storage.set(LAST_CONTACT_AT_MS, value)

    fun clearContact() {
        lastContactAtMs = null
    }

    fun recordContact(nowMs: Long = System.currentTimeMillis()) {
        lastContactAtMs = nowMs
    }

    var fcmToken: String?
        get() = storage.get(FCM_TOKEN)
        set(value) = storage.set(FCM_TOKEN, value)

    val username: String?
        get() = registrationInfo?.login
    val password: String?
        get() = registrationInfo?.password
    val hasPassword: Boolean
        get() = registrationInfo?.password != null

    fun clearPassword() {
        registrationInfo = registrationInfo?.copy(password = null)
    }

    // Mnaseb SIM: the server is fixed to the Mnaseb private server (decision
    // 0008). A stored cloud_url (old settings screen or remote import) is ignored.
    val serverUrl: String
        get() = PUBLIC_URL
    val privateToken: String?
        get() = storage.get<String>(PRIVATE_TOKEN)

    val notificationChannel: NotificationChannel
        get() = storage.get<NotificationChannel>(NOTIFICATION_CHANNEL) ?: NotificationChannel.AUTO

    companion object {
        private const val REGISTRATION_INFO = "REGISTRATION_INFO"
        private const val ENABLED = "ENABLED"
        private const val FCM_TOKEN = "fcm_token"
        private const val LAST_CONTACT_AT_MS = "last_contact_at_ms"

        private const val CLOUD_URL = "cloud_url"
        private const val PRIVATE_TOKEN = "private_token"
        private const val NOTIFICATION_CHANNEL = "notification_channel"

        // Mnaseb SIM: the default (and only intended) server is the private
        // Mnaseb server; the public upstream cloud is not offered.
        const val PUBLIC_URL = "https://smsgate.mnaseb.com/api/mobile/v1"
    }

    override fun export(): Map<String, *> {
        return mapOf(
            CLOUD_URL to serverUrl,
            NOTIFICATION_CHANNEL to notificationChannel.name,
        )
    }

    override fun import(data: Map<String, *>): Boolean {
        return data.map {
            when (it.key) {
                // Mnaseb SIM (decision 0008): a remote settings import must never
                // re-point the server or replace the token it is reached with.
                CLOUD_URL, PRIVATE_TOKEN -> false

                NOTIFICATION_CHANNEL -> {
                    val newValue = it.value?.let { NotificationChannel.valueOf(it.toString()) }
                        ?: NotificationChannel.AUTO
                    val changed = notificationChannel != newValue

                    storage.set(it.key, newValue.name)

                    changed
                }

                else -> false
            }
        }.any { it }
    }
}