package me.capcom.smsgateway.modules.retention

import android.content.SharedPreferences

/**
 * CARD-24: the SIM slots (1-based) that hold an active line of the account, as last reported by
 * the platform. Kept in device-protected storage so the receiver can decide before the first
 * unlock. It holds slot numbers only: no number, no account value.
 *
 * Empty (never fetched, or no active line) means nothing is accepted: [IncomingSlotFilter].
 */
class ActiveLineStore(private val prefs: SharedPreferences) {
    fun slots(): Set<Int> = try {
        decode(prefs.getString(KEY, null))
    } catch (e: Exception) {
        emptySet()
    }

    fun record(slots: Set<Int>) {
        try {
            prefs.edit().putString(KEY, encode(slots)).commit()
        } catch (e: Exception) {
            // the old value stays; the next fetch retries
        }
    }

    fun clear() {
        try {
            prefs.edit().remove(KEY).commit()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    companion object {
        const val FILE = "mnaseb_active_lines"
        private const val KEY = "slots"

        fun encode(slots: Set<Int>): String = slots.sorted().joinToString(",")

        fun decode(value: String?): Set<Int> =
            value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }
}
