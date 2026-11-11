package me.capcom.smsgateway.modules.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD-21: the in-process runner (virtual time) and the lock-aware routing. */
@OptIn(ExperimentalCoroutinesApi::class)
class InProcessSchedulerTest {

    private class FakeTasks(private val scope: TestScope) : LockedTasks {
        val pullTimes = mutableListOf<Long>()
        val sendTimes = mutableListOf<Long>()
        val stateCalls = mutableListOf<String>()
        val drainTimes = mutableListOf<Long>()
        var pullFailures = 0
        var pullDurationMs = 0L
        var stateFailsAlways = false
        var nextWebhookDelay: Long? = null
        var concurrent = 0
        var maxConcurrent = 0

        override suspend fun pull() {
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                pullTimes += scope.currentTime
                if (pullDurationMs > 0) delay(pullDurationMs)
                if (pullFailures > 0) {
                    pullFailures--
                    throw RuntimeException("offline")
                }
            } finally {
                concurrent--
            }
        }

        override suspend fun sendPending() {
            sendTimes += scope.currentTime
        }

        override suspend fun sendState(messageId: String) {
            stateCalls += messageId
            if (stateFailsAlways) throw RuntimeException("offline")
        }

        override suspend fun drainWebhooks(): Long? {
            drainTimes += scope.currentTime
            val next = nextWebhookDelay
            nextWebhookDelay = null
            return next
        }
    }

    private fun TestScope.scheduler(tasks: LockedTasks) = InProcessScheduler(
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        tasks = { tasks },
        now = { currentTime },
    )

    @Test
    fun onePullAtATimeAndARequestDuringARunRunsOnceMore() = runTest {
        val tasks = FakeTasks(this).apply { pullDurationMs = 1000 }
        val s = scheduler(tasks)

        s.pullMessages()
        runCurrent()
        s.pullMessages()
        s.pullMessages()
        s.pullMessages()
        advanceTimeBy(5000)
        runCurrent()

        assertEquals(listOf(0L, 1000L), tasks.pullTimes)
        assertEquals(1, tasks.maxConcurrent)
        assertFalse(s.isBusy())
    }

    @Test
    fun aFailedPullIsRetriedWithExponentialBackoff() = runTest {
        val tasks = FakeTasks(this).apply { pullFailures = 3 }
        val s = scheduler(tasks)

        s.pullMessages()
        advanceTimeBy(60_000)
        runCurrent()

        // attempts at 0, +5 s, +10 s, +20 s; the fourth succeeds
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L), tasks.pullTimes)
        assertFalse(s.isBusy())
    }

    @Test
    fun backoffDoublesAndIsCapped() {
        val b = Backoff(5_000L, 300_000L)
        assertEquals(5_000L, b.delayMs(0))
        assertEquals(10_000L, b.delayMs(1))
        assertEquals(20_000L, b.delayMs(2))
        assertEquals(300_000L, b.delayMs(10))
        assertEquals(300_000L, b.delayMs(1000))
    }

    @Test
    fun sendRunsNowOrAtTheScheduledTimeAndNonForceKeepsTheEarlierOne() = runTest {
        val tasks = FakeTasks(this)
        val s = scheduler(tasks)

        s.sendMessages(true, 0L)
        runCurrent()
        assertEquals(listOf(0L), tasks.sendTimes)

        s.sendMessages(true, currentTime + 10_000)
        s.sendMessages(false, currentTime + 20_000) // later and not forced: ignored
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf(0L, 10_000L), tasks.sendTimes)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("the ignored later run must not fire", 2, tasks.sendTimes.size)
    }

    @Test
    fun forceReplacesAScheduledRun() = runTest {
        val tasks = FakeTasks(this)
        val s = scheduler(tasks)

        s.sendMessages(true, currentTime + 10_000)
        s.sendMessages(true, currentTime + 30_000)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(tasks.sendTimes.isEmpty())

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(30_000L), tasks.sendTimes)
    }

    @Test
    fun stateReportsGiveUpAfterTheMaxAttemptsAndAreDeduplicatedPerMessage() = runTest {
        val tasks = FakeTasks(this).apply { stateFailsAlways = true }
        val s = scheduler(tasks)

        s.sendState("m1")
        advanceTimeBy(24 * 3_600_000L)
        runCurrent()

        assertEquals(10, tasks.stateCalls.size)
        assertFalse(s.isBusy())

        // a second message is independent, and a repeated request while running does not multiply runs
        val ok = FakeTasks(this)
        val s2 = scheduler(ok)
        s2.sendState("a")
        s2.sendState("a")
        s2.sendState("b")
        runCurrent()
        assertEquals(setOf("a", "b"), ok.stateCalls.toSet())
        assertTrue(ok.stateCalls.size <= 3)
    }

    @Test
    fun webhookDrainRunsAgainAfterTheDelayItReturns() = runTest {
        val tasks = FakeTasks(this).apply { nextWebhookDelay = 30_000 }
        val s = scheduler(tasks)

        s.processWebhookQueue(true)
        runCurrent()
        assertEquals(listOf(0L), tasks.drainTimes)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(listOf(0L, 30_000L), tasks.drainTimes)
    }

    @Test
    fun handoverWaitsForTheRunningWorkThenStartsTheFullMode() = runTest {
        val tasks = FakeTasks(this).apply { pullDurationMs = 2_000 }
        val s = scheduler(tasks)
        val events = mutableListOf<String>()

        s.pullMessages()
        runCurrent()
        assertTrue(s.isBusy())

        // a separate scope, as in production (LockedMode)
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch {
            s.drainThenHandover { events += "handover@$currentTime" }
        }
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue("must not hand over while a pull runs", events.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, events.size)
        assertTrue(events[0].startsWith("handover@"))
        assertFalse(s.isBusy())
    }

    @Test
    fun handoverGivesUpWaitingAfterTheTimeout() = runTest {
        val tasks = FakeTasks(this).apply { pullDurationMs = 10_000_000 }
        val s = scheduler(tasks)
        var handedOver = false

        s.pullMessages()
        runCurrent()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch { s.drainThenHandover(timeoutMs = 5_000) { handedOver = true } }
        advanceTimeBy(5_500)
        runCurrent()

        assertTrue(handedOver)
    }

    @Test
    fun aStateReportStillInBackoffAtTheHandoverIsReissuedAfterIt() = runTest {
        // CARD-21 (rev-fork17-2): the drain gives up after the timeout and shuts the loops down;
        // a report that was still retrying must not be lost, WorkManager takes it over
        val tasks = FakeTasks(this).apply { stateFailsAlways = true }
        val s = scheduler(tasks)
        val order = mutableListOf<String>()

        s.sendState("m1") // fails, now waits in backoff
        runCurrent()
        assertEquals(listOf("m1"), s.runningStateIds())

        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch {
            s.drainThenHandover(
                timeoutMs = 1_000,
                resumeState = { order += "state:$it" },
            ) { order += "handover" }
        }
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(listOf("handover", "state:m1"), order)
        assertFalse(s.isBusy())
    }

    @Test
    fun aFinishedStateReportIsNotReissued() = runTest {
        val tasks = FakeTasks(this)
        val s = scheduler(tasks)
        val resumed = mutableListOf<String>()

        s.sendState("done")
        runCurrent()
        assertTrue(s.runningStateIds().isEmpty())

        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        caller.launch { s.drainThenHandover(resumeState = { resumed += it }) { } }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertTrue(resumed.isEmpty())
    }

    // region lock-aware routing: nothing reaches WorkManager while locked

    private class Recording(val name: String, val log: MutableList<String>) : WorkScheduler {
        override fun pullMessages() { log += "$name.pull" }
        override fun sendMessages(force: Boolean, scheduleAt: Long) { log += "$name.send" }
        override fun sendState(messageId: String) { log += "$name.state" }
        override fun processWebhookQueue(internetRequired: Boolean) { log += "$name.webhooks" }
        override fun updateWebhooks() { log += "$name.updateWebhooks" }
        override fun updateSettings() { log += "$name.updateSettings" }
    }

    private fun callEverything(s: WorkScheduler) {
        s.pullMessages()
        s.sendMessages(true, 0L)
        s.sendState("m")
        s.processWebhookQueue(false)
        s.updateWebhooks()
        s.updateSettings()
    }

    @Test
    fun lockedNeverCreatesOrCallsTheWorkManagerScheduler() {
        val log = mutableListOf<String>()
        var workManagerCreated = 0
        val s = ModeAwareScheduler(
            isUnlocked = { false },
            workManager = { workManagerCreated++; Recording("wm", log) },
            inProcess = { Recording("local", log) },
        )

        callEverything(s)

        assertEquals(0, workManagerCreated)
        assertEquals(6, log.size)
        assertTrue(log.all { it.startsWith("local.") })
    }

    @Test
    fun unlockedUsesOnlyWorkManager() {
        val log = mutableListOf<String>()
        var localCreated = 0
        val s = ModeAwareScheduler(
            isUnlocked = { true },
            workManager = { Recording("wm", log) },
            inProcess = { localCreated++; Recording("local", log) },
        )

        callEverything(s)

        assertEquals(0, localCreated)
        assertTrue(log.all { it.startsWith("wm.") })
    }

    @Test
    fun aProcessThatStartedLockedSwitchesToWorkManagerOnUnlock() {
        val log = mutableListOf<String>()
        var unlocked = false
        val s = ModeAwareScheduler(
            isUnlocked = { unlocked },
            workManager = { Recording("wm", log) },
            inProcess = { Recording("local", log) },
        )

        s.pullMessages()
        unlocked = true
        s.pullMessages()

        assertEquals(listOf("local.pull", "wm.pull"), log)
    }
    // endregion
}
