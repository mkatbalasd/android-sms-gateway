package me.capcom.smsgateway.modules.retention

import android.content.SharedPreferences

/**
 * CARD-24: what the receiver needs to decide, before the first unlock, whether an incoming SMS
 * is kept and forwarded. Device-protected. Slot numbers, times and one flag only: no number, no
 * account value.
 *
 *  - [slots]: the SIM slots (1-based) that hold a non-retired line of this phone (ACTIVE or a
 *    PENDING claim), as last reported by the platform. null = the list was never received.
 *    An empty set means "received, no line": nothing is accepted.
 *  - the number-proof window: a proof was started on a slot, so its reply is accepted for a
 *    short, bounded time even before any list arrived.
 *  - [isPlatformPhone]: the explicit role of the platform phone, set only from a server setting
 *    (see IncomingMessagesSettings; it is per gateway account, not per device). DEFAULT CLOSED (false). It is never inferred from a
 *    missing list.
 *
 * A CUSTOMER phone FAILS CLOSED: with no list, only an open proof window accepts. A list that
 * cannot be refreshed is kept as it was (nothing here ever replaces a list on a failure).
 */
class ActiveLineStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun slots(): Set<Int>? = try {
        parse(prefs.getString(KEY, null))
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun record(slots: Set<Int>) {
        try {
            prefs.edit().putString(KEY, encode(slots)).commit()
        } catch (e: Exception) {
            // the old value stays; the next fetch retries
        }
    }

    /** The explicit platform-phone role. Anything unreadable is "not the platform phone". */
    fun isPlatformPhone(): Boolean = try {
        prefs.getBoolean(KEY_PLATFORM_PHONE, false)
    } catch (e: Exception) {
        false
    }

    fun setPlatformPhone(value: Boolean) {
        try {
            prefs.edit().putBoolean(KEY_PLATFORM_PHONE, value).commit()
        } catch (e: Exception) {
            // the old value stays
        }
    }

    /**
     * A proof was started on [slot]: its replies are accepted for [windowMs], even with no list.
     * A known list also gets the slot, so the reply is still accepted once the window is over
     * and before the next fetch.
     */
    @Synchronized // rev-sim35-1 L6: its read-modify-write must not overwrite a list recorded meanwhile
    fun addSlot(slot: Int, windowMs: Long = DEFAULT_PROOF_WINDOW_MS) {
        val now = clock()
        try {
            val windows = withProof(parseProof(prefs.getString(KEY_PROOF, null)), slot, now + windowMs, now)
            val editor = prefs.edit().putString(KEY_PROOF, encodeProof(windows))
            withSlot(slots(), slot)?.let { editor.putString(KEY, encode(it)) }
            editor.commit()
        } catch (e: Exception) {
            // recording must never fail the caller; the next fetch lists the slot
        }
    }

    /** True when the SMS on [simNumber] is kept and forwarded. */
    fun accepts(simNumber: Int?): Boolean {
        val proof = try {
            activeProofSlots(parseProof(prefs.getString(KEY_PROOF, null)), clock())
        } catch (e: Exception) {
            emptySet()
        }
        return IncomingSlotFilter.accept(simNumber, slots(), isPlatformPhone(), proof)
    }

    fun clear() {
        try {
            prefs.edit().remove(KEY).remove(KEY_PROOF).remove(KEY_PLATFORM_PHONE).commit()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    companion object {
        const val FILE = "mnaseb_active_lines"
        private const val KEY = "slots"
        private const val KEY_PROOF = "proof"
        private const val KEY_PLATFORM_PHONE = "platform_phone"

        const val DEFAULT_PROOF_WINDOW_MS = 15 * 60_000L
        private const val MIN_PROOF_WINDOW_MS = 60_000L
        private const val MAX_PROOF_WINDOW_MS = 60 * 60_000L
        private const val PROOF_GRACE_MS = 2 * 60_000L

        /** The window of a proof: the platform's own expiry (seconds), bounded, plus a short grace. */
        fun proofWindowMs(expiresInSeconds: Long?): Long {
            val base = expiresInSeconds?.takeIf { it > 0 }?.times(1000L) ?: DEFAULT_PROOF_WINDOW_MS
            return base.coerceIn(MIN_PROOF_WINDOW_MS, MAX_PROOF_WINDOW_MS) + PROOF_GRACE_MS
        }

        fun encode(slots: Set<Int>): String = slots.sorted().joinToString(",")

        fun decode(value: String?): Set<Int> =
            value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

        /** null = never received. */
        fun parse(value: String?): Set<Int>? = value?.let { decode(it) }

        /** Adds a slot to a known list; a list never received stays unknown. */
        fun withSlot(current: Set<Int>?, slot: Int): Set<Int>? = current?.plus(slot)

        /** slot -> end of its proof window (epoch ms). */
        fun encodeProof(windows: Map<Int, Long>): String =
            windows.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

        fun parseProof(value: String?): Map<Int, Long> =
            value.orEmpty().split(',').mapNotNull {
                val parts = it.trim().split(':')
                val slot = parts.getOrNull(0)?.toIntOrNull()
                val end = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && slot != null && end != null) slot to end else null
            }.toMap()

        /** Opens (or extends) the window of [slot] and drops the windows that already ended. */
        fun withProof(current: Map<Int, Long>, slot: Int, endsAtMs: Long, nowMs: Long): Map<Int, Long> =
            current.filterValues { it > nowMs }.toMutableMap().also {
                it[slot] = maxOf(endsAtMs, it[slot] ?: 0L)
            }

        fun activeProofSlots(windows: Map<Int, Long>, nowMs: Long): Set<Int> =
            windows.filterValues { it > nowMs }.keys
    }
}
