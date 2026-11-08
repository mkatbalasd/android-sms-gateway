package me.capcom.smsgateway.modules.account

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import android.util.Log

/**
 * Picks the 6-digit sign-up or sign-in code out of the SMS the platform line
 * sends. Pure Kotlin, so it is covered by JVM tests.
 *
 * A message counts only when its sender is the platform line the server named
 * ([expectedSender], in full international form; the phone may show it in
 * national form) and its text holds exactly one group of six digits. After the
 * first match the matcher is finished and ignores everything else.
 *
 * Nothing is logged or stored here: a message that does not match is dropped
 * without a trace, and the code is handed to [onCode] and forgotten.
 */
class OtpSmsMatcher(
    private val expectedSender: String,
    private val onCode: (String) -> Unit,
) {
    @Volatile
    private var finished = false

    val isFinished: Boolean get() = finished

    /** Returns true when this message supplied the code. */
    @Synchronized
    fun onMessage(originatingAddress: String?, body: String?): Boolean {
        if (finished) return false
        if (!senderMatches(expectedSender, originatingAddress)) return false
        val code = extractCode(body) ?: return false
        finished = true
        onCode(code)
        return true
    }

    companion object {
        private const val MIN_SUFFIX_DIGITS = 7
        private val sixDigits = Regex("(?<!\\d)(\\d{6})(?!\\d)")

        /** Exactly one group of six digits (Arabic-Indic digits count), else null. */
        fun extractCode(body: String?): String? {
            if (body.isNullOrEmpty()) return null
            val text = OtpDigits.ascii(body)
            val matches = sixDigits.findAll(text).toList()
            return if (matches.size == 1) matches[0].groupValues[1] else null
        }

        /**
         * +967700000001 equals 00967700000001, 0700000001 and 700000001: the
         * digits are compared after the leading zeros are dropped, and the
         * shorter one must be a suffix of the longer one with at least
         * [MIN_SUFFIX_DIGITS] digits. A sender with letters must be equal,
         * ignoring case.
         */
        fun senderMatches(expected: String, actual: String?): Boolean {
            if (actual.isNullOrBlank() || expected.isBlank()) return false
            val expectedDigits = digitsOf(expected)
            val actualDigits = digitsOf(actual)
            if (expectedDigits.isEmpty() || actualDigits.isEmpty()) {
                return expected.trim().equals(actual.trim(), ignoreCase = true)
            }
            val a = expectedDigits.trimStart('0')
            val b = actualDigits.trimStart('0')
            val shorter = if (a.length <= b.length) a else b
            val longer = if (a.length <= b.length) b else a
            return shorter.length >= MIN_SUFFIX_DIGITS && longer.endsWith(shorter)
        }

        private fun digitsOf(value: String): String {
            // "+", spaces and dashes are separators; any letter makes it an alphanumeric sender.
            if (value.any { it.isLetter() }) return ""
            return OtpDigits.normalize(value)
        }
    }
}

/**
 * A receiver registered only while the code screen is visible and RECEIVE_SMS
 * is granted. The manifest receiver of the app is enabled after linking, so
 * it cannot serve this: nothing is readable before the phone is linked.
 */
class OtpSmsReader(
    expectedSender: String,
    onCode: (String) -> Unit,
) : BroadcastReceiver() {

    private val matcher = OtpSmsMatcher(expectedSender, onCode)
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
        // BROADCAST_SMS: only the system can be the sender of this broadcast.
        context.registerReceiver(this, filter, Manifest.permission.BROADCAST_SMS, null)
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(this)
        } catch (e: IllegalArgumentException) {
            // already gone
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (matcher.isFinished) return
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable SMS broadcast")
            null
        }
        if (parts == null) return

        // A long message arrives in parts from one sender: join them before matching.
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (part in parts) {
            val from = part.originatingAddress ?: continue
            bySender.getOrPut(from) { StringBuilder() }.append(part.messageBody ?: "")
        }
        for ((from, text) in bySender) {
            if (matcher.onMessage(from, text.toString())) return
        }
    }

    private companion object {
        const val TAG = "OtpSmsReader"
    }
}
