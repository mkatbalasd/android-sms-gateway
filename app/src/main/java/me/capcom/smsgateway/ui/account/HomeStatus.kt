package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.helpers.KeepAliveGuideState
import me.capcom.smsgateway.modules.account.AppHome
import me.capcom.smsgateway.modules.account.HomeDevice
import me.capcom.smsgateway.modules.account.IsoTime
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
    /** «يعمل — آخر اتصال قبل N دقيقة»; [agoMinutes] 0 means just now. */
    data class Working(val agoMinutes: Int, val batteryNote: Boolean) : PhoneStatus()

    /** «متوقف — افتح التطبيق»: something on the phone itself is off. */
    data class Stopped(val reason: Reason) : PhoneStatus()

    /** «الهاتف لا يتصل»: the phone looks fine but the server has not seen it lately. */
    object Offline : PhoneStatus()

    enum class Reason { SERVICE, PERMISSION, SIM }
}

enum class ApprovalCard { START, REVIEW, NEEDS_INFO, REJECTED }

object HomeStatus {

    /** The server's liveness poller is the truth: older than this is "not connecting". */
    const val FRESH_MS = 15 * 60_000L

    fun phoneStatus(local: LocalChecks, lastSeenAtMs: Long?, nowMs: Long): PhoneStatus {
        when {
            !local.smsPermissionGranted -> return PhoneStatus.Stopped(PhoneStatus.Reason.PERMISSION)
            !local.serviceRunning -> return PhoneStatus.Stopped(PhoneStatus.Reason.SERVICE)
            !local.simPresent -> return PhoneStatus.Stopped(PhoneStatus.Reason.SIM)
        }
        if (lastSeenAtMs == null) return PhoneStatus.Offline
        val age = nowMs - lastSeenAtMs
        // A phone clock a little behind the server is read as "just now", not as the future.
        if (age > FRESH_MS) return PhoneStatus.Offline
        return PhoneStatus.Working(
            agoMinutes = (age.coerceAtLeast(0L) / 60_000L).toInt(),
            batteryNote = local.batteryExempt == false,
        )
    }

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
