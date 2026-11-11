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

/** CARD V2-L23: pause, resume, the 60-minute drop rule and the autostart gate. */
internal class PauseHoldTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private class FakeStore : PauseStore {
        override var pausedAtMs: Long? = null
        override var sweepAfterResume: Boolean = false
    }

    /** One timeline for everything the controller touches, so the order is what is asserted. */
    private val events = mutableListOf<String>()

    private val services = object : ServiceSwitch {
        override fun start() { events.add("start") }
        override fun stop() { events.add("stop") }
    }

    private class FakePending(
        var stamps: List<PendingStamp>,
        private val events: MutableList<String>,
    ) : PendingStore {
        val failed = mutableListOf<Pair<String, String>>()
        override suspend fun pending() = stamps
        override suspend fun fail(id: String, reason: String) {
            events.add("fail:$id")
            failed.add(id to reason)
        }
    }

    private fun sweepOver(pending: FakePending, store: PauseStore) =
        StaleSweep(pending, store) { now }

    private fun controller(
        client: FakeAppClient,
        store: FakeStore,
        pending: FakePending = FakePending(emptyList(), events),
    ) = PauseController(client, store, services, sweepOver(pending, store)) { now }

    // region pause

    @Test
    fun pauseTellsThePlatformBeforeItStopsTheServices() = runBlocking {
        val client = FakeAppClient().apply { pauseAnswer = { events.add("api:pause") } }
        val store = FakeStore()

        controller(client, store).pause()

        assertEquals(listOf("api:pause", "stop"), events)
        assertEquals(now, store.pausedAtMs)
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

        assertFalse(events.contains("stop"))
        assertNull(store.pausedAtMs)
    }

    // endregion

    // region resume and the drop rule

    @Test
    fun resumeDropsOldMessagesBeforeTheServicesStartAndClearsTheFlag() = runBlocking {
        val client = FakeAppClient().apply { resumeAnswer = { events.add("api:resume") } }
        val store = FakeStore().apply { pausedAtMs = now - 10 * minute }
        val pending = FakePending(
            listOf(
                PendingStamp("old", now - 61 * minute),
                PendingStamp("recent", now - 59 * minute),
            ),
            events,
        )

        controller(client, store, pending).resume()

        // The old one is failed first, so nothing old can be sent by the freshly started services.
        assertEquals(listOf("fail:old", "start", "api:resume"), events)
        assertEquals(listOf("old" to "TTL expired"), pending.failed)
        assertNull(store.pausedAtMs)
        assertTrue("the first pull after the resume sweeps again", store.sweepAfterResume)
    }

    @Test
    fun aResumeThatCannotReachThePlatformKeepsThePausedFlagForARetry() = runBlocking {
        val client = FakeAppClient().apply { resumeAnswer = { throw IOException("no network") } }
        val store = FakeStore().apply { pausedAtMs = now - minute }

        try {
            controller(client, store).resume()
            fail("expected the network error")
        } catch (e: IOException) {
            // retry is allowed: the platform call is idempotent
        }

        assertEquals(now - minute, store.pausedAtMs)
    }

    @Test
    fun onlyMessagesOlderThanSixtyMinutesAreStale() {
        assertEquals(60, PAUSE_HOLD_MAX_MIN)
        assertTrue(PauseHold.isStale(now - 60 * minute - 1, now))
        assertFalse("exactly 60 minutes is not older than 60 minutes", PauseHold.isStale(now - 60 * minute, now))
        assertFalse(PauseHold.isStale(now - 5 * minute, now))
        // A message "from the future" (clock skew) is never stale.
        assertFalse(PauseHold.isStale(now + 5 * minute, now))
    }

    @Test
    fun theSweepFailsEveryOldOneAndSparesTheRecentOnes() = runBlocking {
        val store = FakeStore()
        val pending = FakePending(
            listOf(
                PendingStamp("a", now - 3 * 60 * minute),
                PendingStamp("b", now - 30 * minute),
                PendingStamp("c", now - 61 * minute),
                PendingStamp("d", now),
            ),
            events,
        )

        val dropped = sweepOver(pending, store).sweep()

        assertEquals(2, dropped)
        assertEquals(listOf("a", "c"), pending.failed.map { it.first })
        assertTrue(pending.failed.all { it.second == "TTL expired" })
    }

    @Test
    fun oneMessageThatCannotBeFailedDoesNotStopTheRest() = runBlocking {
        val store = FakeStore()
        val failing = object : PendingStore {
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

        val dropped = StaleSweep(failing, store) { now }.sweep()

        assertEquals(1, dropped)
        assertEquals(listOf("y"), failing.failed)
    }

    @Test
    fun theFirstPullAfterAResumeSweepsOnceAndThenStops() = runBlocking {
        val store = FakeStore().apply { sweepAfterResume = true }
        val pending = FakePending(listOf(PendingStamp("late", now - 2 * 60 * minute)), events)
        val sweep = sweepOver(pending, store)

        sweep.onPullDone()
        assertEquals(listOf("late"), pending.failed.map { it.first })
        assertFalse(store.sweepAfterResume)

        // A later pull never drops anything: the rule belongs to the resume only.
        pending.failed.clear()
        sweep.onPullDone()
        assertTrue(pending.failed.isEmpty())
    }

    @Test
    fun aPullWithoutAResumeSweepsNothing() = runBlocking {
        val store = FakeStore()
        val pending = FakePending(listOf(PendingStamp("old", now - 5 * 60 * minute)), events)

        sweepOver(pending, store).onPullDone()

        assertTrue(pending.failed.isEmpty())
    }

    // endregion

    // region autostart and the paused flag

    @Test
    fun autostartIsSuppressedWhilePaused() {
        assertTrue(PauseHold.shouldAutostart(autostartEnabled = true, pausedAtMs = null))
        assertFalse(PauseHold.shouldAutostart(autostartEnabled = true, pausedAtMs = now))
        assertFalse(PauseHold.shouldAutostart(autostartEnabled = false, pausedAtMs = null))
        assertFalse(PauseHold.shouldAutostart(autostartEnabled = false, pausedAtMs = now))
    }

    @Test
    fun pausedIsTheLocalFlagOrTheServersWord() {
        assertTrue(PauseHold.isPaused(now, null))
        assertTrue(PauseHold.isPaused(null, true))
        assertFalse(PauseHold.isPaused(null, false))
        assertFalse(PauseHold.isPaused(null, null))
    }

    @Test
    fun thePausedFlagSurvivesARestartAndTheAccountResetForgetsIt() {
        val storage = FakeKeyValueStorage()
        val settings = AccountSettings(storage)
        settings.pausedAtMs = now
        settings.sweepAfterResume = true

        val again = AccountSettings(storage)
        assertEquals(now, again.pausedAtMs)
        assertTrue(again.sweepAfterResume)

        again.pausedAtMs = null
        again.sweepAfterResume = false
        assertNull(AccountSettings(storage).pausedAtMs)
        assertFalse(AccountSettings(storage).sweepAfterResume)

        again.pausedAtMs = now
        again.reset()
        assertNull(AccountSettings(storage).pausedAtMs)
    }

    // endregion
}
