package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.settings.Exporter
import me.capcom.smsgateway.modules.settings.Importer
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

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

    var registrationInfo: GatewayApi.DeviceRegisterResponse?
        get() = storage.get(REGISTRATION_INFO)
        // CARD-21: the gateway password is never stored (it would sit in device-protected storage).
        set(value) = storage.set(REGISTRATION_INFO, value?.copy(password = null))

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