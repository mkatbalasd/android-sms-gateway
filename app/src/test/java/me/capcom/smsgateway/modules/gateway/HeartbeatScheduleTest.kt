package me.capcom.smsgateway.modules.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatScheduleTest {

    @Test
    fun defaultIsFiveMinutes() {
        assertEquals(300_000L, HeartbeatSchedule.intervalMs(null))
    }

    @Test
    fun flooredAtOneMinute() {
        assertEquals(60_000L, HeartbeatSchedule.intervalMs(5))
        assertEquals(60_000L, HeartbeatSchedule.intervalMs(60))
    }

    @Test
    fun anImportedIntervalAboveTheFloorIsUsed() {
        assertEquals(120_000L, HeartbeatSchedule.intervalMs(120))
        assertEquals(900_000L, HeartbeatSchedule.intervalMs(900))
    }

    private fun TestScope.heartbeat(configured: () -> Int?, beats: MutableList<Long>) = Heartbeat(
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        configuredSeconds = configured,
        beat = { beats += currentTime },
    )

    @Test
    fun beatsAtStartThenEveryFiveMinutes() = runTest {
        val beats = mutableListOf<Long>()
        val hb = heartbeat({ null }, beats)

        hb.start()
        runCurrent()
        advanceTimeBy(600_001)
        runCurrent()
        hb.stop()

        assertEquals(listOf(0L, 300_000L, 600_000L), beats)
    }

    @Test
    fun anImportedIntervalTakesEffectOnTheNextWait() = runTest {
        val beats = mutableListOf<Long>()
        var configured: Int? = null
        val hb = heartbeat({ configured }, beats)

        hb.start()
        runCurrent() // beat at 0, next wait is 300 s
        configured = 60
        advanceTimeBy(300_001)
        runCurrent() // beat at 300 s, next wait is 60 s
        advanceTimeBy(60_000)
        runCurrent()
        hb.stop()

        assertEquals(listOf(0L, 300_000L, 360_000L), beats)
    }

    @Test
    fun stopEndsTheBeatsAndAFailedBeatDoesNotKillTheLoop() = runTest {
        val beats = mutableListOf<Long>()
        var fail = true
        val hb = Heartbeat(
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            configuredSeconds = { null },
            beat = {
                beats += currentTime
                if (fail) throw RuntimeException("offline")
            },
        )

        hb.start()
        runCurrent()
        fail = false
        advanceTimeBy(300_001)
        runCurrent()
        assertEquals(listOf(0L, 300_000L), beats)

        hb.stop()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(2, beats.size)
    }
}
