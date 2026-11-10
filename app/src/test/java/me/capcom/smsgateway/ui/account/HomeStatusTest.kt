package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.helpers.KeepAliveGuideState
import me.capcom.smsgateway.modules.account.AppHome
import me.capcom.smsgateway.modules.account.HomeDevice
import me.capcom.smsgateway.modules.account.HomeLine
import me.capcom.smsgateway.modules.account.HomeRecipients
import me.capcom.smsgateway.modules.account.HomeTenant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HomeStatusTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private val healthy = LocalChecks(
        serviceRunning = true,
        smsPermissionGranted = true,
        simPresent = true,
        batteryExempt = true,
    )

    // region the three sentences

    @Test
    fun aHealthyPhoneSeenAMinuteAgoIsWorking() {
        val status = HomeStatus.phoneStatus(healthy, now - minute, now)
        assertEquals(PhoneStatus.Working(agoMinutes = 1, batteryNote = false), status)
    }

    @Test
    fun aPhoneSeenJustNowIsWorkingWithZeroMinutes() {
        val status = HomeStatus.phoneStatus(healthy, now - 10_000L, now) as PhoneStatus.Working
        assertEquals(0, status.agoMinutes)
    }

    @Test
    fun aClockBehindTheServerIsNotTheFuture() {
        val status = HomeStatus.phoneStatus(healthy, now + 5 * minute, now) as PhoneStatus.Working
        assertEquals(0, status.agoMinutes)
    }

    @Test
    fun aMissingBatteryExemptionIsANoteNotAStop() {
        val status = HomeStatus.phoneStatus(healthy.copy(batteryExempt = false), now, now)
        assertEquals(PhoneStatus.Working(0, batteryNote = true), status)
        // Unknown (Android older than 6) is not a note.
        val unknown = HomeStatus.phoneStatus(healthy.copy(batteryExempt = null), now, now)
        assertEquals(PhoneStatus.Working(0, batteryNote = false), unknown)
    }

    @Test
    fun aServiceThatIsNotRunningIsStoppedWhateverTheServerSaw() {
        val status = HomeStatus.phoneStatus(healthy.copy(serviceRunning = false), now, now)
        assertEquals(PhoneStatus.Stopped(PhoneStatus.Reason.SERVICE), status)
    }

    @Test
    fun aMissingSmsPermissionIsStoppedAndComesFirst() {
        val local = healthy.copy(smsPermissionGranted = false, serviceRunning = false)
        assertEquals(
            PhoneStatus.Stopped(PhoneStatus.Reason.PERMISSION),
            HomeStatus.phoneStatus(local, now, now)
        )
    }

    @Test
    fun noSimIsStopped() {
        val status = HomeStatus.phoneStatus(healthy.copy(simPresent = false), now, now)
        assertEquals(PhoneStatus.Stopped(PhoneStatus.Reason.SIM), status)
    }

    @Test
    fun aHealthyPhoneTheServerHasNotSeenForAWhileIsOffline() {
        assertEquals(
            PhoneStatus.Offline,
            HomeStatus.phoneStatus(healthy, now - 16 * minute, now)
        )
        assertEquals(PhoneStatus.Offline, HomeStatus.phoneStatus(healthy, null, now))
        // The edge: exactly fifteen minutes is still fresh.
        assertTrue(HomeStatus.phoneStatus(healthy, now - 15 * minute, now) is PhoneStatus.Working)
    }

    // endregion

    // region keep-alive card

    private fun guide(battery: Boolean?, boot: Boolean, oem: Boolean) =
        KeepAliveGuideState(battery, boot, oem)

    @Test
    fun theKeepAliveCardStaysWhileTheServerNeverSawThePhone() {
        val allSet = guide(true, true, true)
        assertTrue(HomeStatus.keepAliveCardVisible(serverPending = true, local = allSet))
        assertFalse(HomeStatus.keepAliveCardVisible(serverPending = false, local = allSet))
    }

    @Test
    fun theKeepAliveCardStaysWhileARowIsNotSet() {
        assertTrue(HomeStatus.keepAliveCardVisible(false, guide(true, true, false)))
        // An unknown battery state does not count as set.
        assertTrue(HomeStatus.keepAliveCardVisible(false, guide(null, true, true)))
    }

    @Test
    fun stepsLeftCountsTheRowsThatAreNotOn() {
        assertEquals(0, HomeStatus.keepAliveStepsLeft(guide(true, true, true)))
        assertEquals(2, HomeStatus.keepAliveStepsLeft(guide(false, true, false)))
        assertEquals(3, HomeStatus.keepAliveStepsLeft(guide(null, false, false)))
    }

    @Test
    fun theReportedChecklistFollowsTheRows() {
        val checklist = KeepAliveReport.checklist(guide(true, false, true))
        assertTrue(checklist.battery)
        assertFalse(checklist.boot)
        assertTrue(checklist.oem)
        assertFalse(KeepAliveReport.checklist(guide(null, true, true)).battery)
    }

    // endregion

    // region approval card, counters, numbers

    @Test
    fun theApprovalCardMirrorsTheApprovalStatus() {
        assertEquals(ApprovalCard.START, HomeStatus.approvalCard("SANDBOX", "NONE"))
        assertEquals(ApprovalCard.START, HomeStatus.approvalCard("SANDBOX", null))
        assertEquals(ApprovalCard.REVIEW, HomeStatus.approvalCard("SANDBOX", "SUBMITTED"))
        assertEquals(ApprovalCard.NEEDS_INFO, HomeStatus.approvalCard("SANDBOX", "NEEDS_INFO"))
        assertEquals(ApprovalCard.REJECTED, HomeStatus.approvalCard("SANDBOX", "REJECTED"))
        assertNull(HomeStatus.approvalCard("LIVE", "APPROVED"))
        assertNull(HomeStatus.approvalCard("SANDBOX", "APPROVED"))
        assertNull(HomeStatus.approvalCard("LIVE", "NONE"))
    }

    private fun home(
        mode: String = "SANDBOX",
        balanceMinor: Long? = 1_200_000L,
        devices: List<HomeDevice> = emptyList(),
    ) = AppHome(
        tenant = HomeTenant("t", "Al Shifa", mode, "NONE"),
        balanceMinor = balanceMinor,
        sentToday = 3,
        sandboxDailyMax = 30,
        devices = devices,
        testRecipients = HomeRecipients(2, 10),
        keepAlivePending = false,
    )

    @Test
    fun theCountersLineIsSandboxOrLive() {
        val sandbox = HomeStatus.counters(home())
        assertFalse(sandbox.live)
        assertEquals(3, sandbox.sentToday)
        assertEquals(30, sandbox.dailyMax)

        val live = HomeStatus.counters(home(mode = "LIVE"))
        assertTrue(live.live)
        // 1,200,000 minor units of 1/10000 is 120.00.
        assertEquals("120.00", live.balance)
    }

    @Test
    fun theBalanceIsFormattedFromMinorUnits() {
        assertEquals("0.00", HomeStatus.balanceText(null))
        assertEquals("0.05", HomeStatus.balanceText(500))
        assertEquals("12.35", HomeStatus.balanceText(123_450))
    }

    @Test
    fun theLastSeenIsTheNewestOfTheActivePhones() {
        val devices = listOf(
            HomeDevice("a", "p1", "ACTIVE", "2026-11-10T10:00:00.000Z", null),
            HomeDevice("b", "p2", "ACTIVE", "2026-11-10T10:05:00.000Z", null),
            HomeDevice("c", "p3", "RETIRED", "2026-11-10T11:00:00.000Z", null),
        )
        val seen = HomeStatus.lastSeenMs(home(devices = devices))
        assertEquals(me.capcom.smsgateway.modules.account.IsoTime.parseMillis("2026-11-10T10:05:00.000Z"), seen)
        assertNull(HomeStatus.lastSeenMs(home()))
        assertNull(HomeStatus.lastSeenMs(null))
    }

    @Test
    fun theNumbersListHasEveryLineInSlotOrderWithItsReadiness() {
        val devices = listOf(
            HomeDevice(
                "a", "p1", "ACTIVE", null,
                listOf(
                    HomeLine(2, "+967******352", "PAUSED"),
                    HomeLine(1, "+967******714", "ACTIVE"),
                )
            )
        )
        val lines = HomeStatus.numberLines(home(devices = devices))
        assertEquals(listOf("+967******714", "+967******352"), lines.map { it.masked })
        assertEquals(listOf(true, false), lines.map { it.ready })
        assertTrue(HomeStatus.numberLines(null).isEmpty())
    }

    // endregion
}
