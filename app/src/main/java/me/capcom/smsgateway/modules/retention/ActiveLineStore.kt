package me.capcom.smsgateway.modules.retention

import android.content.SharedPreferences

/**
 * CARD-24: the SIM slots (1-based) that hold a non-retired line of this phone (ACTIVE or a
 * PENDING claim), as last reported by the platform. Device-protected, so the receiver can decide
 * before the first unlock. Slot numbers only: no number, no account value.
 *
 * [slots] is null when the list was never fetched (no app session, a phone linked only through
 * the gateway, a fresh install, or after an unlink): the filter then FAILS OPEN, the behaviour
 * before CARD-24. An empty set means "fetched, no line": nothing is accepted.
 */
class ActiveLineStore(private val prefs: SharedPreferences) {
    fun slots(): Set<Int>? = try {
        parse(prefs.getString(KEY, null))
    } catch (e: Exception) {
        null
    }

    fun record(slots: Set<Int>) {
        try {
            prefs.edit().putString(KEY, encode(slots)).commit()
        } catch (e: Exception) {
            // the old value stays; the next fetch retries
        }
    }

    /** A proof was started on [slot]: its replies must arrive before the next fetch. */
    fun addSlot(slot: Int) {
        val next = withSlot(slots(), slot) ?: return
        record(next)
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

        /** null = never fetched (fail open). */
        fun parse(value: String?): Set<Int>? = value?.let { decode(it) }

        /** Adds a slot to a known list; a list never fetched stays unknown (fail open). */
        fun withSlot(current: Set<Int>?, slot: Int): Set<Int>? = current?.plus(slot)
    }
}
