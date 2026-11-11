package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.helpers.KeepAliveGuideState
import me.capcom.smsgateway.modules.account.AppHome
import me.capcom.smsgateway.modules.account.HomeDevice
import me.capcom.smsgateway.modules.account.IsoTime
import me.capcom.smsgateway.modules.account.PauseHold
import java.math.BigDecimal
import java.math.RoundingMode

/** What the phone itself can tell: is it able to send right now. */
data class LocalChecks(
    val serviceRunning: Boolean,
    val smsPermissionGranted: Boolean,
    val simPresent: Boolean,
    /** Battery exemption; null when the system cannot tell. Never stops the phone from being "working". */
    val batteryExempt: Boolean?,
)

/** The three sentences of the design (01-flows §1.1), with the numbers they need. */
sealed class PhoneStatus {
    /**
     * «يعمل»: the gateway has had contact within the expected interval ([agoMinutes] null),
     * or «يعمل — آخر اتصال قبل N دقيقة» once the gap is longer than a normal ping gap.
     */
    data class Working(val agoMinutes: Int?, val batteryNote: Boolean) : PhoneStatus()

    /** «متوقف — افتح التطبيق»: something on the phone itself is off. */
    data class Stopped(val reason: Reason) : PhoneStatus()

    /** «الهاتف لا يتصل»: the phone looks fine but the gateway has had no contact lately. */
    object Offline : PhoneStatus()

    /** «متوقف مؤقتًا»: the owner paused the phone (V2-L23); not an error and not offline. */
    object Paused : PhoneStatus()

    enum class Reason { SERVICE, PERMISSION, SIM }
}

enum class ApprovalCard { START, REVIEW, NEEDS_INFO, REJECTED }

object HomeStatus {

    /** The gateway's last contact older than this is "not connecting" (a floor; see [offlineAfterMs]). */
    const val OFFLINE_FLOOR_MS = 30 * 60_000L

    /** Slack on top of the expected gap (Doze and the work scheduler run late). */
    const val GRACE_MS = 5 * 60_000L

    /**
     * The real gap between contacts when no ping interval is set (the default): the pull
     * worker's period (WorkManager's 15 minute minimum), plus pushes and the live stream.
     */
    const val DEFAULT_EXPECTED_MS = 15 * 60_000L

    private fun baseGapMs(pingIntervalSeconds: Int?): Long =
        pingIntervalSeconds?.takeIf { it > 0 }?.let { it * 1000L } ?: DEFAULT_EXPECTED_MS

    /** A gap up to this is normal: the ping interval (or the pull period) plus a grace. */
    fun expectedGapMs(pingIntervalSeconds: Int?): Long = baseGapMs(pingIntervalSeconds) + GRACE_MS

    /** No contact for longer than this is «لا يتصل»: twice the expected gap, never under 30 minutes. */
    fun offlineAfterMs(pingIntervalSeconds: Int?): Long =
        maxOf(OFFLINE_FLOOR_MS, 2 * baseGapMs(pingIntervalSeconds))

    /**
     * The connection line comes from the gateway only ([lastContactAtMs]: its last good
     * answer from the server), never from the app session: a revoked sign-in does not make
     * a linked phone «لا يتصل».
     */
    fun phoneStatus(
        local: LocalChecks,
        lastContactAtMs: Long?,
        nowMs: Long,
        pingIntervalSeconds: Int? = null,
        paused: Boolean = false,
    ): PhoneStatus {
        // A paused phone has its services stopped on purpose (V2-L23): never Stopped or Offline.
        if (paused) return PhoneStatus.Paused
        when {
            !local.smsPermissionGranted -> return PhoneStatus.Stopped(PhoneStatus.Reason.PERMISSION)
            !local.serviceRunning -> return PhoneStatus.Stopped(PhoneStatus.Reason.SERVICE)
            !local.simPresent -> return PhoneStatus.Stopped(PhoneStatus.Reason.SIM)
        }
        if (lastContactAtMs == null) return PhoneStatus.Offline
        // A phone clock behind the contact time is read as "just now", not as the future.
        val age = (nowMs - lastContactAtMs).coerceAtLeast(0L)
        if (age > offlineAfterMs(pingIntervalSeconds)) return PhoneStatus.Offline
        val note = local.batteryExempt == false
        // A normal ping gap is not staleness: say only «يعمل» until the gap is longer.
        if (age <= expectedGapMs(pingIntervalSeconds)) return PhoneStatus.Working(null, note)
        return PhoneStatus.Working((age / 60_000L).toInt(), note)
    }

    /** Paused when the phone remembers the pause or the platform's home says so. */
    fun isPaused(localPaused: Boolean, home: AppHome?): Boolean =
        PauseHold.isPaused(localPaused, home?.paused)

    /** Server answers that mean the app session is over (revoked, expired, never there). */
    val SESSION_ENDED_CODES = setOf("APP_SESSION_INVALID", "APP_SESSION_REVOKED", "NO_SESSION")

    /**
     * A linked phone whose app session is gone shows a sign-in action. Its gateway link,
     * device id and credentials are untouched; only the app session is asked for again.
     */
    fun sessionNeedsReattach(
        linked: Boolean,
        hasSession: Boolean,
        lastErrorCode: String?,
        reattachPending: Boolean,
    ): Boolean = linked && hasSession && reattachPending &&
        !sessionNeedsSignIn(linked, hasSession, lastErrorCode)

    fun sessionNeedsSignIn(linked: Boolean, hasSession: Boolean, lastErrorCode: String?): Boolean =
        linked && (!hasSession || lastErrorCode in SESSION_ENDED_CODES)

    /** The phone this screen speaks for: the active one the server saw last. */
    fun lastSeenMs(home: AppHome?): Long? =
        home?.devices.orEmpty()
            .filter { it.status == "ACTIVE" }
            .mapNotNull { IsoTime.parseMillis(it.lastSeenAt) }
            .maxOrNull()

    /**
     * The orange keep-alive card: while the server still has never seen the phone
     * ([serverPending]) or a row of the guide is not set on the phone.
     */
    fun keepAliveCardVisible(serverPending: Boolean, local: KeepAliveGuideState): Boolean =
        serverPending || !local.allSet

    /** How many rows of the guide are still open: «خطوتان متبقيتان». */
    fun keepAliveStepsLeft(local: KeepAliveGuideState): Int =
        listOf(local.battery, local.boot, local.oem).count { it != KeepAliveGuideState.Row.ON }

    /** The approval card; none when the tenant is already live. */
    fun approvalCard(mode: String?, approvalStatus: String?): ApprovalCard? {
        if (mode.equals("LIVE", ignoreCase = true) || approvalStatus == "APPROVED") return null
        return when (approvalStatus) {
            "SUBMITTED" -> ApprovalCard.REVIEW
            "NEEDS_INFO" -> ApprovalCard.NEEDS_INFO
            "REJECTED" -> ApprovalCard.REJECTED
            else -> ApprovalCard.START
        }
    }

    fun isLive(home: AppHome?): Boolean = home?.tenant?.mode.equals("LIVE", ignoreCase = true)

    /** Balance in the ledger's minor units (1/10000 of the currency unit) as «120.00». */
    fun balanceText(balanceMinor: Long?): String =
        BigDecimal.valueOf(balanceMinor ?: 0L).movePointLeft(4).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** The counters line is sandbox or live; this says which and the numbers to put in. */
    data class Counters(val live: Boolean, val sentToday: Int, val dailyMax: Int?, val balance: String)

    fun counters(home: AppHome): Counters = Counters(
        live = isLive(home),
        sentToday = home.sentToday ?: 0,
        dailyMax = home.sandboxDailyMax,
        balance = balanceText(home.balanceMinor),
    )

    /** The number rows of the home screen: every line of every phone, in slot order. */
    data class NumberLine(val masked: String, val ready: Boolean, val status: String?)

    fun numberLines(home: AppHome?): List<NumberLine> =
        home?.devices.orEmpty().flatMap { device: HomeDevice ->
            device.lines.orEmpty().sortedBy { it.slot ?: Int.MAX_VALUE }.map {
                NumberLine(it.msisdnMasked.orEmpty(), it.status == "ACTIVE", it.status)
            }
        }
}
