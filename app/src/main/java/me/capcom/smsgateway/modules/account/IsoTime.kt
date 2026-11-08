package me.capcom.smsgateway.modules.account

import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Parses the ISO-8601 UTC timestamps of the platform (`2026-11-08T10:00:00.000Z`,
 * also with an offset). java.time is not used: minSdk is 21 and the project has
 * no core library desugaring.
 *
 * The result is only used to show a countdown. Whether a code is still valid
 * is always decided by the server, never by comparing with the phone clock.
 */
object IsoTime {
    private val pattern = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})$"
    )

    /** Epoch milliseconds, or null when [value] is not a timestamp of that form. */
    fun parseMillis(value: String?): Long? {
        if (value == null) return null
        val m = pattern.matchEntire(value.trim()) ?: return null
        val g = m.groupValues
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(
            g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
            g[4].toInt(), g[5].toInt(), g[6].toInt()
        )
        val fraction = g[7]
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toInt()
        val zone = g[8]
        val offsetMinutes = if (zone == "Z") {
            0
        } else {
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            if (zone[0] == '-') -minutes else minutes
        }
        return calendar.timeInMillis + millis - offsetMinutes * 60_000L
    }

    /**
     * Whole seconds from [nowMillis] to [target], never below zero and never
     * above [maxSeconds]: a phone clock that is far off must not show a
     * countdown of hours.
     */
    fun secondsUntil(target: String?, nowMillis: Long, maxSeconds: Int): Int {
        val millis = parseMillis(target) ?: return 0
        val seconds = ((millis - nowMillis + 999) / 1000).toInt()
        return seconds.coerceIn(0, maxSeconds)
    }
}
