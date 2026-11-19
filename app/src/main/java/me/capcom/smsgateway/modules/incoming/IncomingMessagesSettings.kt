package me.capcom.smsgateway.modules.incoming

import me.capcom.smsgateway.modules.settings.Exporter
import me.capcom.smsgateway.modules.settings.Importer
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * Settings of the incoming-messages log. This class deliberately has NO way to open the
 * platform-phone role: settings reach it from the local server (PATCH /settings) as well as from
 * the server pull, and a customer phone must never be told to keep every SMS of both SIMs. The
 * role comes only from the server's webhook pull (see PlatformRole). A key such as
 * `platform_phone` in an `incoming` section is simply ignored.
 */
class IncomingMessagesSettings(
    private val storage: KeyValueStorage,
) : Exporter, Importer {
    val lifetimeDays: Int?
        get() = storage.get<Int?>(LIFETIME_DAYS)?.takeIf { it > 0 }

    override fun export(): Map<String, *> {
        return mapOf(
            LIFETIME_DAYS to lifetimeDays,
        )
    }

    override fun import(data: Map<String, *>): Boolean {
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

                else -> false
            }
        }.any { it }
    }

    companion object {
        private const val LIFETIME_DAYS = "lifetime_days"
    }
}
