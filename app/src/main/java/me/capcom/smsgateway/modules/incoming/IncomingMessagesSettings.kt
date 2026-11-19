package me.capcom.smsgateway.modules.incoming

import me.capcom.smsgateway.modules.settings.Exporter
import me.capcom.smsgateway.modules.settings.Importer
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

class IncomingMessagesSettings(
    private val storage: KeyValueStorage,
    /**
     * CARD-24: the explicit platform-phone role, applied to the device-protected store the
     * receiver reads. Called with the parsed `platform_phone` value, and with false when the
     * `incoming` answer has no such key. The role is per GATEWAY ACCOUNT, not per device.
     */
    private val onPlatformPhone: (Boolean) -> Unit = {},
) : Exporter, Importer {
    val lifetimeDays: Int?
        get() = storage.get<Int?>(LIFETIME_DAYS)?.takeIf { it > 0 }

    override fun export(): Map<String, *> {
        return mapOf(
            LIFETIME_DAYS to lifetimeDays,
        )
    }

    override fun import(data: Map<String, *>): Boolean {
        // rev-sim35-1 L1: an answer without the key is "not the platform phone": an account that
        // stops sending `true` closes the role again (the role is never left open by silence)
        if (PLATFORM_PHONE !in data) onPlatformPhone(false)
        return data.map { (key, value) ->
            when (key) {
                LIFETIME_DAYS -> {
                    val lifetimeDays = value?.toString()?.toFloat()?.toInt()
                    if (lifetimeDays != null && lifetimeDays < 1) {
                        throw IllegalArgumentException("Log lifetime days must be >= 1")
                    }

                    val changed = this.lifetimeDays != lifetimeDays
                    storage.set(key, lifetimeDays?.toString())

                    changed
                }

                PLATFORM_PHONE -> {
                    onPlatformPhone(parsePlatformPhone(value))
                    // applied at once by the receiver; no restart needed
                    false
                }

                else -> false
            }
        }.any { it }
    }

    companion object {
        private const val LIFETIME_DAYS = "lifetime_days"

        /** The server setting `incoming.platform_phone`: the explicit role of the platform phone. */
        const val PLATFORM_PHONE = "platform_phone"

        /**
         * rev-sim35-2 L7: the worker pulls the account's WHOLE settings answer. When it has no
         * `incoming` section the role is closed (a deleted section must not leave it open).
         * Only the full pull uses this; a partial update (local server) does not.
         */
        fun sectionMissing(fullSettings: Map<String, *>): Boolean = fullSettings["incoming"] !is Map<*, *>

        /** Only a real `true` (or the text "true") opens the role; everything else is closed. */
        fun parsePlatformPhone(value: Any?): Boolean =
            value == true || (value is String && value.trim().equals("true", ignoreCase = true))
    }
}
