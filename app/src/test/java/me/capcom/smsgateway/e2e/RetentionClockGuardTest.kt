package me.capcom.smsgateway.e2e

import me.capcom.smsgateway.modules.retention.RetentionClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-24 T2 guard: the retention clock can be shifted only in the `emulatorE2e` build type.
 * These tests run for every other variant (the e2e variant has its unit tests disabled), so a
 * non-zero shift here means the source-set wiring broke.
 */
class RetentionClockGuardTest {
    @Test
    fun noRetentionShiftExistsOutsideTheE2eBuildType() {
        assertEquals(0L, E2eOverride.retentionShiftMs)
    }

    @Test
    fun theRetentionClockIsTheRealClock() {
        val before = System.currentTimeMillis()
        val now = RetentionClock.now()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
    }
}
