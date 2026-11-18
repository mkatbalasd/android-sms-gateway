package me.capcom.smsgateway.ui.account

import android.content.Context
import java.security.MessageDigest

/** Persistent set of consumed proof attempts: attempt key to the time it was consumed. */
interface AttemptLog {
    fun entries(): Map<String, Long>

    /**
     * Must be durable when it returns: the SMS is sent right after. Returns false when the
     * record could not be made durable (rev-fork15-1 L4): the caller must then NOT send.
     */
    fun add(key: String, atMs: Long): Boolean
    fun remove(key: String)
}

/**
 * One platform token, one SMS (CARD-18 T2). The attempt is identified by a hash of the
 * slot and the token the platform just issued, recorded durably BEFORE the radio is asked,
 * and consumed once: a repeated trigger, a restored screen or a restarted process that
 * reaches the send again with the same token finds the record and sends nothing. A new
 * token (a new explicit tap that fetched it from the platform) is a new attempt.
 * Only a hash is stored, never the token text.
 */
class ProofAttemptGuard(
    private val log: AttemptLog,
    private val nowMs: () -> Long,
) {

    enum class Begin { GO, CONSUMED, NOT_RECORDED }

    /** True once for this attempt: the caller may send. False when it was already consumed or not recorded. */
    fun tryBegin(key: String): Boolean = begin(key) == Begin.GO

    /**
     * [Begin.GO] once for this attempt. [Begin.CONSUMED] when it was already used.
     * [Begin.NOT_RECORDED] when the durable record failed: nothing may be sent (a restart
     * could then send the same text again), and nothing was sent, so a next tap may retry.
     */
    @Synchronized
    fun begin(key: String): Begin {
        val now = nowMs()
        log.entries().forEach { (k, at) -> if (now - at > RETAIN_MS) log.remove(k) }
        if (log.entries().containsKey(key)) return Begin.CONSUMED
        val recorded = try {
            log.add(key, now)
        } catch (e: Exception) {
            false
        }
        if (!recorded) {
            runCatching { log.remove(key) } // a half-written entry must not block the retry
            return Begin.NOT_RECORDED
        }
        return Begin.GO
    }

    /**
     * Gives the attempt back after a DEFINITE failure (the radio said it did not send).
     * Never after an unknown outcome: the system may still deliver that SMS.
     */
    @Synchronized
    fun release(key: String) = log.remove(key)

    companion object {
        private const val RETAIN_MS = 24L * 60 * 60 * 1000

        fun key(slot: Int, to: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$slot|$to|$text".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Process-local log, for tests and as a default. */
class MemoryAttemptLog : AttemptLog {
    private val map = HashMap<String, Long>()
    override fun entries(): Map<String, Long> = HashMap(map)
    override fun add(key: String, atMs: Long): Boolean {
        map[key] = atMs
        return true
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

/** Survives process death. Keys are hashes; values are times. `commit()` is synchronous on purpose. */
class PrefsAttemptLog(context: Context) : AttemptLog {
    private val prefs = context.applicationContext.getSharedPreferences("mnaseb-proof-attempts", Context.MODE_PRIVATE)

    override fun entries(): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    override fun add(key: String, atMs: Long): Boolean = prefs.edit().putLong(key, atMs).commit()

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }
}
