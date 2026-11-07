package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.KeepAliveGuideState.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class KeepAliveGuideStateTest {

    @Test
    fun allSetWhenEverythingIsOn() {
        val s = KeepAliveGuideState(batteryExempt = true, autostart = true, oemConfirmed = true)
        assertTrue(s.allSet)
        assertEquals(Row.ON, s.battery)
        assertEquals(Row.ON, s.boot)
        assertEquals(Row.ON, s.oem)
    }

    @Test
    fun anyOffRowShowsOffAndIsNotAllSet() {
        val noBattery = KeepAliveGuideState(false, true, true)
        assertFalse(noBattery.allSet)
        assertEquals(Row.OFF, noBattery.battery)

        val noBoot = KeepAliveGuideState(true, false, true)
        assertFalse(noBoot.allSet)
        assertEquals(Row.OFF, noBoot.boot)

        val noOem = KeepAliveGuideState(true, true, false)
        assertFalse(noOem.allSet)
        assertEquals(Row.OFF, noOem.oem)
    }

    @Test
    fun unknownBatteryIsNotCountedAsSet() {
        val s = KeepAliveGuideState(null, true, true)
        assertEquals(Row.UNKNOWN, s.battery)
        assertFalse(s.allSet)
    }

    @Test
    fun offeredAgainOnNextStartUntilAllSet() {
        val partial = KeepAliveGuideState(true, true, false)
        val done = KeepAliveGuideState(true, true, true)
        assertTrue(KeepAliveGuideState.shouldOfferOnStart(shownBefore = true, state = partial))
        assertFalse(KeepAliveGuideState.shouldOfferOnStart(shownBefore = true, state = done))
    }

    @Test
    fun notOfferedBeforeTheFirstLinkShowedIt() {
        val none = KeepAliveGuideState(false, false, false)
        assertFalse(KeepAliveGuideState.shouldOfferOnStart(shownBefore = false, state = none))
    }
}
