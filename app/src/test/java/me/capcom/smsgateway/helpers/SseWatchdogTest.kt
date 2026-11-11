package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.SseWatchdog.Action
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SseWatchdogTest {
    private var now = 1_000_000L

    private fun watchdog() = SseWatchdog(
        clock = { now },
        reconnectAfterMs = 180_000L,
        pullAfterMs = 60_000L,
    )

    @Test
    fun recentActivityDoesNothing() {
        val w = watchdog()
        now += 30_000L
        assertEquals(Action.NONE, w.check())
    }

    @Test
    fun exactlyAtThePullThresholdDoesNothing() {
        val w = watchdog()
        now += 60_000L
        assertEquals(Action.NONE, w.check())
    }

    @Test
    fun quietPastThePullThresholdPulls() {
        val w = watchdog()
        now += 61_000L
        assertEquals(Action.PULL, w.check())
        // Still quiet on the next tick: keep pulling, do not reconnect yet.
        now += 60_000L
        assertEquals(Action.PULL, w.check())
    }

    @Test
    fun silentPastTheWatchdogThresholdReconnects() {
        val w = watchdog()
        now += 181_000L
        assertEquals(Action.RECONNECT, w.check())
    }

    @Test
    fun exactlyAtTheWatchdogThresholdOnlyPulls() {
        val w = watchdog()
        now += 180_000L
        assertEquals(Action.PULL, w.check())
    }

    @Test
    fun reconnectResetsTheClock() {
        val w = watchdog()
        now += 200_000L
        assertEquals(Action.RECONNECT, w.check())
        // The next tick right after the reconnect sees a fresh window.
        now += 1_000L
        assertEquals(Action.NONE, w.check())
        // And a second reconnect needs a full new silence.
        now += 181_000L
        assertEquals(Action.RECONNECT, w.check())
    }

    @Test
    fun activityRestartsTheSilence() {
        val w = watchdog()
        now += 170_000L
        w.onActivity()
        now += 30_000L
        assertEquals(Action.NONE, w.check())
        assertEquals(30_000L, w.silenceMs())
    }

    @Test
    fun aClockThatMovesBackwardsNeverFires() {
        val w = watchdog()
        now -= 500_000L
        assertEquals(Action.NONE, w.check())
    }

    @Test
    fun defaultsMatchTheCard() {
        assertEquals(3 * 60_000L, SseWatchdog.WATCHDOG_SILENCE_MS)
        assertEquals(60_000L, SseWatchdog.PULL_SILENCE_MS)
        assertEquals(60_000L, SseWatchdog.TICK_MS)
    }
}
