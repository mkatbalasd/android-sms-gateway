package me.capcom.smsgateway.helpers

/**
 * Mnaseb SIM keep-alive guide: pure state, no Android types.
 *
 * [batteryExempt] is null when the system cannot tell (Android older than 6).
 * The maker's autostart screen cannot be read, so [oemConfirmed] is the user's tick.
 */
data class KeepAliveGuideState(
    val batteryExempt: Boolean?,
    val autostart: Boolean,
    val oemConfirmed: Boolean,
) {
    enum class Row { ON, OFF, UNKNOWN }

    val battery: Row
        get() = when (batteryExempt) {
            true -> Row.ON
            false -> Row.OFF
            null -> Row.UNKNOWN
        }

    val boot: Row get() = if (autostart) Row.ON else Row.OFF

    val oem: Row get() = if (oemConfirmed) Row.ON else Row.OFF

    /** Every row is on. An unknown battery state does not count as set. */
    val allSet: Boolean get() = battery == Row.ON && boot == Row.ON && oem == Row.ON

    companion object {
        /**
         * Offer the guide on app start: only after it was shown once (the link
         * happened) and while something is still not set. It never blocks sending.
         */
        fun shouldOfferOnStart(shownBefore: Boolean, state: KeepAliveGuideState): Boolean =
            shownBefore && !state.allSet
    }
}
