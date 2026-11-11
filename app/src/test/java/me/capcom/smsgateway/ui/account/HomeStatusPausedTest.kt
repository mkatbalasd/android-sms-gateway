package me.capcom.smsgateway.ui.account

import com.google.gson.Gson
import me.capcom.smsgateway.modules.account.AppHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD V2-L23: «متوقف مؤقتًا» is its own status, distinct from Stopped and Offline. */
internal class HomeStatusPausedTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private val healthy = LocalChecks(
        serviceRunning = true,
        smsPermissionGranted = true,
        simPresent = true,
        batteryExempt = true,
    )

    private fun home(json: String): AppHome = Gson().fromJson(json, AppHome::class.java)

    @Test
    fun aPausedPhoneIsPausedWhateverTheOtherChecksSay() {
        // The services are stopped on purpose, so "service not running" must not read as Stopped.
        val stopped = healthy.copy(serviceRunning = false)
        assertEquals(PhoneStatus.Paused, HomeStatus.phoneStatus(stopped, now - minute, now, paused = true))
        // No contact for hours (the phone is not pulling) must not read as Offline.
        assertEquals(PhoneStatus.Paused, HomeStatus.phoneStatus(stopped, now - 3 * 60 * minute, now, paused = true))
        assertEquals(PhoneStatus.Paused, HomeStatus.phoneStatus(stopped, null, now, paused = true))
        assertEquals(
            PhoneStatus.Paused,
            HomeStatus.phoneStatus(healthy.copy(smsPermissionGranted = false), null, now, paused = true)
        )
    }

    @Test
    fun pausedIsDistinctFromStoppedAndOffline() {
        val stopped = HomeStatus.phoneStatus(healthy.copy(serviceRunning = false), now, now)
        val offline = HomeStatus.phoneStatus(healthy, now - 3 * 60 * minute, now)
        assertNotEquals(PhoneStatus.Paused, stopped)
        assertNotEquals(PhoneStatus.Paused, offline)
    }

    @Test
    fun withoutThePausedFlagTheOldMappingIsUnchanged() {
        val running = HomeStatus.phoneStatus(healthy, now - minute, now)
        assertTrue(running is PhoneStatus.Working)
        assertEquals(running, HomeStatus.phoneStatus(healthy, now - minute, now, paused = false))
        assertEquals(
            PhoneStatus.Stopped(PhoneStatus.Reason.SERVICE),
            HomeStatus.phoneStatus(healthy.copy(serviceRunning = false), now, now, paused = false)
        )
    }

    @Test
    fun theLocalFlagAndTheHomePausedFieldBothDriveTheStatus() {
        val pausedHome = home("""{"paused":true,"paused_at":"2026-11-11T10:00:00.000Z","pause_hold_max_min":45}""")
        val runningHome = home("""{"paused":false,"paused_at":null}""")
        val oldPlatform = home("""{"keep_alive_pending":false}""")

        assertTrue(HomeStatus.isPaused(false, pausedHome))
        assertTrue(HomeStatus.isPaused(true, runningHome))
        assertTrue(HomeStatus.isPaused(true, null))
        assertFalse(HomeStatus.isPaused(false, runningHome))
        // An older platform has no such field: it never pauses the screen by itself.
        assertFalse(HomeStatus.isPaused(false, oldPlatform))
        assertFalse(HomeStatus.isPaused(false, null))
    }

    @Test
    fun theTopLevelPauseFieldsOfAppHomeAreRead() {
        val h = home("""{"paused":true,"paused_at":"2026-11-11T10:00:00.000Z","pause_hold_max_min":45}""")
        assertEquals(true, h.paused)
        assertEquals("2026-11-11T10:00:00.000Z", h.pausedAt)
        assertEquals(45, h.pauseHoldMaxMin)
        // Absent from an older platform: null, and the phone falls back to 60.
        assertNull(home("""{"keep_alive_pending":true}""").pauseHoldMaxMin)
    }
}
