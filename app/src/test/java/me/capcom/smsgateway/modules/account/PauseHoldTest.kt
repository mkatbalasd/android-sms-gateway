package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.ui.account.FakeAppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** CARD V2-L23: pause, resume, the drop rule and the autostart gate. */
internal class PauseHoldTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private class FakeStore : PauseStore {
        override var pausedAtMs: Long? = null
        override var resumedAtMs: Long? = null
        override var holdMaxMin: Int? = null
    }

    /** One timeline for everything the controller touches, so the order is what is asserted. */
    private val events = mutableListOf<String>()

    private val services = object : ServiceSwitch {
        override fun pause() { events.add("pause") }
        override fun start() { events.add("start") }
        override fun keepReporting() { events.add("keepReporting") }
    }

    private val reporter = object : StateReporter {
        override fun report(messageId: String) { events.add("report:$messageId") }
    }

    private class FakePending(
        var stamps: List<PendingStamp>,
        private val events: MutableList<String>,
    ) : DelegatingPendingStore() {
        val failed = mutableListOf<Pair<String, String>>()
        override suspend fun pending() = stamps
        override suspend fun fail(id: String, reason: String) {
            events.add("fail:$id")
            failed.add(id to reason)
        }
    }

    private fun sweepOver(pending: FakePending, store: PauseStore, at: Long = now) =
        StaleSweep(pending, store, reporter) { at }

    private fun controller(
        client: FakeAppClient,
        store: FakeStore,
        pending: FakePending = FakePending(emptyList(), events),
        at: Long = now,
    ) = PauseController(client, store, services, sweepOver(pending, store, at)) { at }

    // region pause

    @Test
    fun pauseTellsThePlatformBeforeItStopsTheServices() = runBlocking {
        val client = FakeAppClient().apply { pauseAnswer = { events.add("api:pause") } }
        val store = FakeStore().apply { pausedAtMs = now - 500 * minute; resumedAtMs = now - 400 * minute }

        controller(client, store).pause()

        assertEquals(listOf("api:pause", "pause"), events)
        assertEquals(now, store.pausedAtMs)
        assertNull("a new pause has no end yet", store.resumedAtMs)
        assertTrue(store.isPaused)
    }

    @Test
    fun aPauseThatCannotReachThePlatformKeepsThePhoneRunning() = runBlocking {
        val client = FakeAppClient().apply { pauseAnswer = { throw IOException("no network") } }
        val store = FakeStore()

        try {
            controller(client, store).pause()
            fail("expected the network error")
        } catch (e: IOException) {
            // the screen shows the error
        }

        assertTrue("the services must not be stopped", events.isEmpty())
        assertNull(store.pausedAtMs)
        assertFalse(store.isPaused)
    }

    @Test
    fun aPauseTheServerRefusesKeepsThePhoneRunning() = runBlocking {
        val client = FakeAppClient().apply {
            pauseAnswer = { throw AccountException(AccountError("HTTP_503", 503)) }
        }
        val store = FakeStore()

        try {
            controller(client, store).pause()
            fail("expected the server error")
        } catch (e: AccountException) {
            assertEquals(503, e.error.httpStatus)
        }

        assertFalse(events.contains("pause"))
        assertFalse(store.isPaused)
    }

    // endregion

    // region resume

    @Test
    fun resumeSweepsTheQueueThenTellsThePlatformThenStartsTheServices() = runBlocking {
        val client = FakeAppClient().apply { resumeAnswer = { events.add("api:resume") } }
        val pausedAt = now - 90 * minute
        val store = FakeStore().apply { pausedAtMs = pausedAt }
        val pending = FakePending(
            listOf(
                PendingStamp("old", pausedAt + 10 * minute),
                PendingStamp("recent", now - 30 * minute),
            ),
            events,
        )

        controller(client, store, pending).resume()

        assertEquals(listOf("keepReporting", "fail:old", "report:old", "api:resume", "start"), events)
        assertEquals(listOf("old" to "TTL expired"), pending.failed)
        assertEquals(now, store.resumedAtMs)
        assertFalse(store.isPaused)
        // The last interval is kept for the drop rule on every later pull.
        assertEquals(pausedAt, store.pausedAtMs)
    }

    @Test
    fun aResumeThatCannotReachThePlatformStartsNothingAndStaysPaused() = runBlocking {
        val client = FakeAppClient().apply { resumeAnswer = { throw IOException("no network") } }
        val store = FakeStore().apply { pausedAtMs = now - minute }

        try {
            controller(client, store).resume()
            fail("expected the network error")
        } catch (e: IOException) {
            // retry is allowed: the platform call is idempotent
        }

        assertFalse("the services must not start before the platform knows", events.contains("start"))
        assertNull(store.resumedAtMs)
        assertTrue(store.isPaused)
    }

    // endregion

    // region the hold rule

    @Test
    fun theHoldIsMeasuredFromTheLaterOfCreationAndPause() {
        val pausedAt = now - 100 * minute
        // Created long before the pause: held from the pause, 100 minutes.
        assertTrue(PauseHold.heldTooLong(now - 5 * 60 * minute, pausedAt, now, 60))
        // The same message when the pause lasted only 40 minutes is fine.
        assertFalse(PauseHold.heldTooLong(now - 5 * 60 * minute, now - 40 * minute, now, 60))
        // Created during the pause, 30 minutes ago: held 30 minutes.
        assertFalse(PauseHold.heldTooLong(now - 30 * minute, pausedAt, now, 60))
        // Created during the pause, 61 minutes ago.
        assertTrue(PauseHold.heldTooLong(now - 61 * minute, pausedAt, now, 60))
        // Exactly the limit is not over the limit.
        assertFalse(PauseHold.heldTooLong(now - 60 * minute, pausedAt, now, 60))
        assertTrue(PauseHold.heldTooLong(now - 60 * minute - 1, pausedAt, now, 60))
    }

    @Test
    fun theLimitIsThePlatformsAndFallsBackToSixty() {
        assertEquals(60, PAUSE_HOLD_MAX_MIN)
        assertEquals(60, PauseHold.holdMaxMin(null))
        assertEquals(60, PauseHold.holdMaxMin(0))
        assertEquals(30, PauseHold.holdMaxMin(30))
        assertTrue(PauseHold.heldTooLong(now - 31 * minute, now - 100 * minute, now, 30))
        assertFalse(PauseHold.heldTooLong(now - 31 * minute, now - 100 * minute, now, 60))
    }

    @Test
    fun theDropOnPullAppliesOnlyToMessagesCreatedBeforeTheResume() {
        val pausedAt = now - 200 * minute
        val resumedAt = now - 10 * minute
        fun drop(createdAt: Long, paused: Long? = pausedAt, resumed: Long? = resumedAt) =
            PauseHold.dropOnPull(createdAt, paused, resumed, now, 60)

        // Created during the pause and held 120 minutes by now: dropped.
        assertTrue(drop(now - 120 * minute))
        // Created before the pause: held from the pause start, 200 minutes: dropped.
        assertTrue(drop(now - 24 * 60 * minute))
        // Created during the pause but only 30 minutes ago: kept.
        assertFalse(drop(now - 30 * minute, resumed = now - 1 * minute))
        // Created after the resume: an ordinary message, never dropped.
        assertFalse(drop(resumedAt + 1))
        assertFalse(drop(resumedAt))
        // No pause ever, or still paused: the rule has nothing to say.
        assertFalse(drop(now - 500 * minute, paused = null))
        assertFalse(drop(now - 500 * minute, resumed = null))
    }

    @Test
    fun theSweepFailsEveryMessageHeldTooLongAndSparesTheRest() = runBlocking {
        val pausedAt = now - 100 * minute
        val store = FakeStore().apply { pausedAtMs = pausedAt }
        val pending = FakePending(
            listOf(
                PendingStamp("before-pause", now - 5 * 60 * minute),
                PendingStamp("during-30", now - 30 * minute),
                PendingStamp("during-61", now - 61 * minute),
                PendingStamp("fresh", now),
            ),
            events,
        )

        val dropped = sweepOver(pending, store).sweep()

        assertEquals(2, dropped)
        assertEquals(listOf("before-pause", "during-61"), pending.failed.map { it.first })
        assertTrue(pending.failed.all { it.second == "TTL expired" })
    }

    @Test
    fun theSweepUsesThePlatformsLimit() = runBlocking {
        val store = FakeStore().apply { pausedAtMs = now - 100 * minute; holdMaxMin = 20 }
        val pending = FakePending(
            listOf(PendingStamp("a", now - 25 * minute), PendingStamp("b", now - 10 * minute)),
            events,
        )

        sweepOver(pending, store).sweep()

        assertEquals(listOf("a"), pending.failed.map { it.first })
    }

    @Test
    fun aSweepWithoutAPauseDropsNothing() = runBlocking {
        val store = FakeStore()
        val pending = FakePending(listOf(PendingStamp("old", now - 5 * 60 * minute)), events)

        assertEquals(0, sweepOver(pending, store).sweep())
        assertTrue(pending.failed.isEmpty())
    }

    @Test
    fun oneMessageThatCannotBeFailedDoesNotStopTheRest() = runBlocking {
        val store = FakeStore().apply { pausedAtMs = now - 200 * minute }
        val failing = object : DelegatingPendingStore() {
            val failed = mutableListOf<String>()
            override suspend fun pending() = listOf(
                PendingStamp("x", now - 90 * minute),
                PendingStamp("y", now - 90 * minute),
            )

            override suspend fun fail(id: String, reason: String) {
                if (id == "x") throw IllegalStateException("gone")
                failed.add(id)
            }
        }

        val dropped = StaleSweep(failing, store, reporter) { now }.sweep()

        assertEquals(1, dropped)
        assertEquals(listOf("y"), failing.failed)
    }

    // endregion

    // region restore from the server

    @Test
    fun aPauseTheServerKnowsIsTakenOverAndTheServicesStop() {
        val store = FakeStore()
        val client = FakeAppClient()
        val serverPausedAt = now - 20 * minute

        val restored = controller(client, store).restore(
            serverPaused = true, serverPausedAtMs = serverPausedAt, requestedAtMs = now
        )

        assertTrue(restored)
        assertTrue(store.isPaused)
        assertEquals(serverPausedAt, store.pausedAtMs)
        assertEquals(listOf("pause"), events)
    }

    @Test
    fun aServerPauseWithoutATimeUsesTheClock() {
        val store = FakeStore()

        controller(FakeAppClient(), store).restore(true, null, now)

        assertEquals(now, store.pausedAtMs)
    }

    @Test
    fun anAnswerAskedForBeforeTheResumeIsStale() {
        val store = FakeStore().apply { pausedAtMs = now - 60 * minute; resumedAtMs = now }

        val restored = controller(FakeAppClient(), store).restore(
            serverPaused = true, serverPausedAtMs = now - 60 * minute, requestedAtMs = now - 1_000
        )

        assertFalse(restored)
        assertFalse(store.isPaused)
        assertTrue(events.isEmpty())
    }

    @Test
    fun restoreDoesNothingWhenAlreadyPausedOrTheServerIsNotPaused() {
        val paused = FakeStore().apply { pausedAtMs = now - minute }
        assertFalse(controller(FakeAppClient(), paused).restore(true, null, now))

        val running = FakeStore()
        assertFalse(controller(FakeAppClient(), running).restore(false, null, now))
        assertFalse(controller(FakeAppClient(), running).restore(null, null, now))
        assertTrue(events.isEmpty())
    }

    @Test
    fun aNewerPauseAfterAnOldResumeIsRestored() {
        // Resumed an hour ago, paused again elsewhere: the answer is newer than the resume.
        val store = FakeStore().apply { pausedAtMs = now - 300 * minute; resumedAtMs = now - 60 * minute }

        assertTrue(controller(FakeAppClient(), store).restore(true, now - 5 * minute, now))
        assertTrue(store.isPaused)
        assertNull(store.resumedAtMs)
    }

    // endregion

    // region reporting while paused

    @Test
    fun aFailureRecordedWhilePausedReachesTheReporter() = runBlocking {
        // Still paused: the sweep runs before the platform is told about the resume.
        val store = FakeStore().apply { pausedAtMs = now - 100 * minute }
        assertTrue(store.isPaused)
        val pending = FakePending(
            listOf(PendingStamp("held", now - 80 * minute), PendingStamp("fresh", now - 5 * minute)),
            events,
        )

        sweepOver(pending, store).sweep()

        assertEquals("the failure is reported, the fresh one is not", listOf("fail:held", "report:held"), events)
        assertTrue(store.isPaused)
    }

    @Test
    fun theReportingPathIsMadeSureBeforeTheSweepAndNeverStoppedByAPause() = runBlocking {
        val client = FakeAppClient()
        val store = FakeStore().apply { pausedAtMs = now - 100 * minute }
        val pending = FakePending(listOf(PendingStamp("held", now - 80 * minute)), events)

        controller(client, store, pending).resume()

        assertTrue(events.indexOf("keepReporting") < events.indexOf("fail:held"))
        assertTrue(events.indexOf("report:held") < events.indexOf("start"))
        // The switch has no "stop everything": a pause is only pause().
        assertEquals(setOf("pause", "start", "keepReporting"), ServiceSwitch::class.java.methods.map { it.name }.toSet())
    }

    @Test
    fun aMessageThatCannotBeFailedIsNotReported() = runBlocking {
        val store = FakeStore().apply { pausedAtMs = now - 200 * minute }
        val failing = object : DelegatingPendingStore() {
            override suspend fun pending() = listOf(PendingStamp("x", now - 90 * minute))
            override suspend fun fail(id: String, reason: String) = throw IllegalStateException("gone")
        }

        StaleSweep(failing, store, reporter) { now }.sweep()

        assertTrue(events.isEmpty())
    }

    // endregion

    // region the paused flag

    @Test
    fun pausedIsTheLocalFlagOrTheServersWord() {
        assertTrue(PauseHold.isPaused(true, null))
        assertTrue(PauseHold.isPaused(false, true))
        assertFalse(PauseHold.isPaused(false, false))
        assertFalse(PauseHold.isPaused(false, null))
    }

    @Test
    fun theIntervalSurvivesARestartAndTheAccountResetForgetsIt() {
        val storage = FakeKeyValueStorage()
        val settings = AccountSettings(storage)
        settings.holdMaxMin = 45
        settings.pausedAtMs = now

        val again = AccountSettings(storage)
        assertEquals(now, again.pausedAtMs)
        assertEquals(45, again.holdMaxMin)
        assertTrue(again.isPaused)

        again.resumedAtMs = now + minute
        val third = AccountSettings(storage)
        assertFalse(third.isPaused)
        assertEquals(now, third.pausedAtMs)

        third.reset()
        val fresh = AccountSettings(storage)
        assertNull(fresh.pausedAtMs)
        assertNull(fresh.resumedAtMs)
        assertFalse(fresh.isPaused)
    }

    // endregion
}
