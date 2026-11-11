package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.DeviceStorageMigration.Result
import me.capcom.smsgateway.helpers.MigrationFollowUp.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CARD-21 (review rev-fork17-1/2): a failed copy is never silent, and a copy that was deferred
 * because the process started locked is run by a fresh process at the unlock, also when the
 * unlock already happened before the receiver could be registered.
 */
class MigrationFollowUpTest {
    @Test
    fun aFailedCopyIsReported() {
        assertEquals(Action.REPORT_FAILURE, MigrationFollowUp.of(Result.FAILED, unlockedNow = true))
        assertEquals(Action.REPORT_FAILURE, MigrationFollowUp.of(Result.FAILED, unlockedNow = false))
    }

    @Test
    fun aDeferredCopyWhileStillLockedRestartsAtTheUnlock() {
        assertEquals(
            Action.RESTART_AT_UNLOCK,
            MigrationFollowUp.of(Result.SKIPPED_LOCKED, unlockedNow = false)
        )
    }

    @Test
    fun aDeferredCopyWhoseUnlockAlreadyPassedRestartsAtOnce() {
        // the race: unlocked between attachBaseContext (skipped) and the receiver registration;
        // the receiver would never fire and the process would run unlocked on empty storage
        assertEquals(
            Action.RESTART_NOW,
            MigrationFollowUp.of(Result.SKIPPED_LOCKED, unlockedNow = true)
        )
    }

    @Test
    fun aFinishedOrUnneededCopyNeedsNothing() {
        assertEquals(Action.NONE, MigrationFollowUp.of(Result.DONE, unlockedNow = true))
        assertEquals(Action.NONE, MigrationFollowUp.of(Result.ALREADY_DONE, unlockedNow = true))
        assertEquals(Action.NONE, MigrationFollowUp.of(null, unlockedNow = true))
    }
}
