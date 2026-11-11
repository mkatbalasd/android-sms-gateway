package me.capcom.smsgateway.modules.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD-21 (I-3): the account store is never opened, created or deleted while locked. */
class AccountStorageGuardTest {

    @Test
    fun lockedThrowsAndRunsNothing() {
        var opened = false
        var unlockedChecks = 0

        val thrown = try {
            requireUnlocked({ unlockedChecks++; false }) { opened = true }
            null
        } catch (e: LockedException) {
            e
        }

        assertTrue("must throw LockedException", thrown != null)
        assertFalse("must not open, create or delete anything", opened)
        assertEquals(1, unlockedChecks)
    }

    @Test
    fun unlockedRunsTheBlockOnceAndReturnsItsValue() {
        var runs = 0
        val value = requireUnlocked({ true }) { runs++; "store" }

        assertEquals("store", value)
        assertEquals(1, runs)
    }

    @Test
    fun aLockedAttemptDoesNotPoisonALaterUnlockedOne() {
        var unlocked = false
        var runs = 0

        try {
            requireUnlocked({ unlocked }) { runs++ }
        } catch (_: LockedException) {
        }
        assertEquals(0, runs)

        unlocked = true
        requireUnlocked({ unlocked }) { runs++ }
        assertEquals(1, runs)
    }
}
