package me.capcom.smsgateway.receivers

import me.capcom.smsgateway.receivers.BootPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class BootPolicyTest {

    private val lockedBoot = BootPolicy.LOCKED_BOOT_COMPLETED
    private val bootCompleted = "android.intent.action.BOOT_COMPLETED"
    private val replaced = "android.intent.action.MY_PACKAGE_REPLACED"
    private val quick = "android.intent.action.QUICKBOOT_POWERON"
    private val reboot = "android.intent.action.REBOOT"
    private val shutdown = "android.intent.action.ACTION_SHUTDOWN"

    private fun decide(action: String?, unlocked: Boolean, autostart: Boolean = true, sdk: Int = 33) =
        BootPolicy.decide(action, unlocked, autostart, sdk)

    @Test
    fun lockedBootWhileLockedStartsLockedMode() {
        assertEquals(Action.START_LOCKED, decide(lockedBoot, unlocked = false))
    }

    @Test
    fun lockedBootOnAnUnlockedDeviceStartsEverything() {
        assertEquals(Action.START_FULL, decide(lockedBoot, unlocked = true))
    }

    @Test
    fun lockedBootBelowApi24IsIgnored() {
        assertEquals(Action.IGNORE, decide(lockedBoot, unlocked = false, sdk = 23))
        assertEquals(Action.IGNORE, decide(lockedBoot, unlocked = true, sdk = 21))
    }

    @Test
    fun theAppPrivateRelaunchActionStartsEverythingOnceUnlocked() {
        // the deferred storage copy restarts the process with this action, never with the
        // protected BOOT_COMPLETED (a non-system sender cannot send that)
        assertEquals(Action.START_FULL, decide(BootPolicy.RELAUNCH, unlocked = true))
        assertEquals(Action.IGNORE, decide(BootPolicy.RELAUNCH, unlocked = true, autostart = false))
        assertEquals(false, BootPolicy.RELAUNCH.startsWith("android."))
    }

    @Test
    fun bootCompletedAfterUnlockStartsEverythingAsBefore() {
        for (a in listOf(bootCompleted, replaced, quick, reboot, shutdown)) {
            assertEquals(a, Action.START_FULL, decide(a, unlocked = true))
        }
    }

    @Test
    fun bootCompletedBelowApi24StartsEverything() {
        assertEquals(Action.START_FULL, decide(bootCompleted, unlocked = true, sdk = 21))
    }

    @Test
    fun startActionsWhileLockedUseLockedMode() {
        assertEquals(Action.START_LOCKED, decide(bootCompleted, unlocked = false))
        assertEquals(Action.START_LOCKED, decide(replaced, unlocked = false))
        assertEquals(Action.START_LOCKED, decide(quick, unlocked = false))
    }

    @Test
    fun shutdownAndRebootNeverStartAnythingWhileLocked() {
        assertEquals(Action.IGNORE, decide(shutdown, unlocked = false))
        assertEquals(Action.IGNORE, decide(reboot, unlocked = false))
    }

    @Test
    fun nothingStartsWhenAutostartIsOff() {
        for (unlocked in listOf(true, false)) {
            for (a in listOf(lockedBoot, bootCompleted, replaced)) {
                assertEquals(Action.IGNORE, decide(a, unlocked, autostart = false))
            }
        }
    }

    @Test
    fun unknownOrMissingActionsAreIgnored() {
        assertEquals(Action.IGNORE, decide(null, unlocked = true))
        assertEquals(Action.IGNORE, decide("android.intent.action.USER_PRESENT", unlocked = true))
        assertEquals(Action.IGNORE, decide("android.intent.action.USER_PRESENT", unlocked = false))
    }
}
