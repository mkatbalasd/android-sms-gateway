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
    fun aHealthyPhoneSeenAMinuteAgoIsWorkingWithoutAnAgo() {
        // A normal gap between pings is not staleness: «يعمل» alone.
        val status = HomeStatus.phoneStatus(healthy, now - minute, now)
        assertEquals(PhoneStatus.Working(agoMinutes = null, batteryNote = false), status)
    }

    @Test
    fun aPhoneSeenJustNowIsWorkingWithoutAnAgo() {
        val status = HomeStatus.phoneStatus(healthy, now - 10_000L, now) as PhoneStatus.Working
        assertNull(status.agoMinutes)
    }

    @Test
    fun aClockBehindTheServerIsNotTheFuture() {
        val status = HomeStatus.phoneStatus(healthy, now + 5 * minute, now) as PhoneStatus.Working
        assertNull(status.agoMinutes)
    }

    @Test
    fun aMissingBatteryExemptionIsANoteNotAStop() {
        val status = HomeStatus.phoneStatus(healthy.copy(batteryExempt = false), now, now)
        assertEquals(PhoneStatus.Working(null, batteryNote = true), status)
        // Unknown (Android older than 6) is not a note.
        val unknown = HomeStatus.phoneStatus(healthy.copy(batteryExempt = null), now, now)
        assertEquals(PhoneStatus.Working(null, batteryNote = false), unknown)
    }

    // The staleness wording comes only after the ping interval plus a grace.

    @Test
    fun aGapWithinThePingIntervalAndGraceIsPlainWorking() {
        // Ping every 5 minutes, grace 5: ten minutes is still normal.
        val atEdge = HomeStatus.phoneStatus(healthy, now - 10 * minute, now, pingIntervalSeconds = 300)
        assertEquals(PhoneStatus.Working(null, false), atEdge)
    }

    @Test
    fun aGapPastThePingIntervalAndGraceNamesTheMinutes() {
        val late = HomeStatus.phoneStatus(healthy, now - 11 * minute, now, pingIntervalSeconds = 300)
        assertEquals(PhoneStatus.Working(11, false), late)
    }

    @Test
    fun aShortPingIntervalMakesAShortGapWorthMentioning() {
        // Ping every minute: six minutes is the edge, seven is late.
        assertEquals(
            PhoneStatus.Working(null, false),
            HomeStatus.phoneStatus(healthy, now - 6 * minute, now, pingIntervalSeconds = 60)
        )
        assertEquals(
            PhoneStatus.Working(7, false),
            HomeStatus.phoneStatus(healthy, now - 7 * minute, now, pingIntervalSeconds = 60)
        )
    }

    @Test
    fun withoutAPingIntervalTheDefaultIsThePullPeriod() {
        // The default phone has no ping interval: contact comes every 15 minutes (the pull
        // worker), so a 14 minute gap is normal and plain «يعمل» holds up to 20.
        assertEquals(
            androidx.work.PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            HomeStatus.DEFAULT_EXPECTED_MS
        )
        for (min in listOf(1L, 14L, 15L, 20L)) {
            assertEquals(
                PhoneStatus.Working(null, false),
                HomeStatus.phoneStatus(healthy, now - min * minute, now, pingIntervalSeconds = null)
            )
        }
        assertEquals(
            PhoneStatus.Working(25, false),
            HomeStatus.phoneStatus(healthy, now - 25 * minute, now)
        )
        // Zero or negative is "no interval", not "everything is late".
        assertEquals(HomeStatus.expectedGapMs(null), HomeStatus.expectedGapMs(0))
    }

    @Test
    fun aLongPingIntervalIsNotCalledOfflineBeforeTwoIntervals() {
        // Ping every 30 minutes: 40 minutes is late, not offline; 80 is offline.
        val fortyMin = HomeStatus.phoneStatus(healthy, now - 40 * minute, now, pingIntervalSeconds = 1800)
        assertEquals(PhoneStatus.Working(40, false), fortyMin)
        assertEquals(
            PhoneStatus.Offline,
            HomeStatus.phoneStatus(healthy, now - 80 * minute, now, pingIntervalSeconds = 1800)
        )
    }

    // The connection line is the gateway's; the app session never feeds it.

    @Test
    fun theConnectionStatusIgnoresTheAppSession() {
        // Same gateway contact, session revoked or not: the same status (the function has no
        // session input), and the session line is separate.
        val status = HomeStatus.phoneStatus(healthy, now - minute, now)
        assertEquals(PhoneStatus.Working(null, false), status)
        assertTrue(HomeStatus.sessionNeedsSignIn(linked = true, hasSession = false, lastErrorCode = "APP_SESSION_REVOKED"))
    }

    @Test
    fun aLinkedPhoneWithoutAnAppSessionOffersSignIn() {
        assertTrue(HomeStatus.sessionNeedsSignIn(linked = true, hasSession = false, lastErrorCode = null))
        // Every verify on a linked phone is a re-sign-in (tenant guard, no enrolment), so the
        // action is safe to offer while a stale token is still stored.
        for (code in listOf("APP_SESSION_INVALID", "APP_SESSION_REVOKED", "NO_SESSION")) {
            assertTrue(HomeStatus.sessionNeedsSignIn(linked = true, hasSession = true, lastErrorCode = code))
        }
    }

    @Test
    fun aHealthySessionOrAnUnlinkedPhoneOffersNoSignIn() {
        assertFalse(HomeStatus.sessionNeedsSignIn(linked = true, hasSession = true, lastErrorCode = null))
        // A network or server error is not a dead session.
        assertFalse(HomeStatus.sessionNeedsSignIn(linked = true, hasSession = true, lastErrorCode = "HTTP_503"))
        // An unlinked phone uses the welcome screens, not this action.
        assertFalse(HomeStatus.sessionNeedsSignIn(linked = false, hasSession = false, lastErrorCode = "APP_SESSION_INVALID"))
    }

    @Test
    fun aSignedInAgainLinkedPhoneNeedsTheReattachUntilItIsDone() {
        assertTrue(HomeStatus.sessionNeedsReattach(true, true, null, reattachPending = true))
        assertFalse(HomeStatus.sessionNeedsReattach(true, true, null, reattachPending = false))
        // Without a session the sign-in comes first.
        assertFalse(HomeStatus.sessionNeedsReattach(true, false, null, reattachPending = true))
        assertFalse(HomeStatus.sessionNeedsReattach(false, true, null, reattachPending = true))
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
            HomeStatus.phoneStatus(healthy, now - 31 * minute, now)
        )
        assertEquals(PhoneStatus.Offline, HomeStatus.phoneStatus(healthy, null, now))
        // The edge: exactly thirty minutes (twice the pull period) is still working.
        assertTrue(HomeStatus.phoneStatus(healthy, now - 30 * minute, now) is PhoneStatus.Working)
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
