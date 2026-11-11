package me.capcom.smsgateway.helpers

import me.capcom.smsgateway.helpers.DeviceStorageMigration.Result
import me.capcom.smsgateway.helpers.MigrationFollowUp.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CARD-21 (review rev-fork17-1): a failed copy is never silent, and a copy that was deferred
 * because the process started locked is run by a fresh process at the unlock.
 */
class MigrationFollowUpTest {
    @Test
    fun aFailedCopyIsReported() {
        assertEquals(Action.REPORT_FAILURE, MigrationFollowUp.of(Result.FAILED))
    }

    @Test
    fun aDeferredCopyRestartsAtTheUnlock() {
        assertEquals(Action.RESTART_AT_UNLOCK, MigrationFollowUp.of(Result.SKIPPED_LOCKED))
    }

    @Test
    fun aFinishedOrUnneededCopyNeedsNothing() {
        assertEquals(Action.NONE, MigrationFollowUp.of(Result.DONE))
        assertEquals(Action.NONE, MigrationFollowUp.of(Result.ALREADY_DONE))
        assertEquals(Action.NONE, MigrationFollowUp.of(null))
    }
}
