package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AppClient
import java.io.IOException

/** The end of a test message, as the dialog shows it. */
sealed class TestOutcome {
    /** «وصلت ✓ خلال N ث». */
    data class Arrived(val seconds: Int) : TestOutcome()

    /** The platform gave up on it: [status] is its final status. */
    data class Failed(val status: String) : TestOutcome()

    /** Still on its way after the wait; the Messages tab follows it. */
    object StillOnItsWay : TestOutcome()

    /** The platform refused the send: [code] is its error code. */
    data class Refused(val code: String) : TestOutcome()
}

object TestMessagePolling {

    const val POLL_INTERVAL_MS = 2_000L
    const val TIMEOUT_MS = 60_000L
    const val TEXT_MAX = 300
    const val CAP_CODE = "SANDBOX_DAILY_LIMIT"

    /** SENT: the phone's radio took it. DELIVERED: the carrier confirmed. Both are «وصلت». */
    private val ARRIVED = setOf("SENT", "DELIVERED")
    private val FAILED = setOf("FAILED", "UNDELIVERABLE", "EXPIRED", "REJECTED", "ACCEPTANCE_UNKNOWN")

    fun isArrived(status: String?) = status in ARRIVED
    fun isFailed(status: String?) = status in FAILED

    /** The default text: «رسالة تجريبية من مناسب — <tenant>», cut to the platform's limit. */
    fun defaultText(prefix: String, tenant: String?): String =
        (if (tenant.isNullOrBlank()) prefix else "$prefix — ${tenant.trim()}").take(TEXT_MAX)

    /** A text the platform accepts: 1 to [TEXT_MAX] characters. */
    fun textValid(text: String): Boolean = text.isNotBlank() && text.length <= TEXT_MAX

    /**
     * Sends one test message and follows it every [POLL_INTERVAL_MS] for up to [TIMEOUT_MS].
     * [idempotencyKey] makes a repeated tap the same message. Seconds are counted from the send.
     */
    suspend fun run(
        client: AppClient,
        idempotencyKey: String,
        recipientId: String,
        text: String,
        delayMs: suspend (Long) -> Unit,
        nowMs: () -> Long,
        onWaiting: () -> Unit = {},
    ): TestOutcome {
        val began = nowMs()
        val created = try {
            client.sendTestMessage(idempotencyKey, recipientId, text)
        } catch (e: AccountException) {
            return TestOutcome.Refused(e.error.code)
        } catch (e: IOException) {
            return TestOutcome.Refused(UiError.NETWORK)
        }
        val id = created.id ?: return TestOutcome.Refused("BAD_RESPONSE")
        return poll(client, id, began, created.status, delayMs, nowMs, onWaiting)
    }

    suspend fun poll(
        client: AppClient,
        id: String,
        began: Long,
        firstStatus: String?,
        delayMs: suspend (Long) -> Unit,
        nowMs: () -> Long,
        onWaiting: () -> Unit = {},
    ): TestOutcome {
        var status = firstStatus
        onWaiting()
        while (true) {
            outcomeOf(status, began, nowMs())?.let { return it }
            if (nowMs() - began >= TIMEOUT_MS) return TestOutcome.StillOnItsWay
            delayMs(POLL_INTERVAL_MS)
            status = try {
                client.message(id).status
            } catch (e: IOException) {
                status // a dropped request is retried on the next tick
            } catch (e: AccountException) {
                return TestOutcome.Refused(e.error.code)
            }
        }
    }

    private fun outcomeOf(status: String?, began: Long, now: Long): TestOutcome? = when {
        isArrived(status) -> TestOutcome.Arrived(((now - began) / 1000L).toInt().coerceAtLeast(0))
        isFailed(status) -> TestOutcome.Failed(status.orEmpty())
        else -> null
    }
}
