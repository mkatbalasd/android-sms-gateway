package me.capcom.smsgateway.modules.orchestrator

import me.capcom.smsgateway.modules.orchestrator.LockedPipelineGate.FullStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-21 (review rev-fork17-1): the locked pipeline starts once per process, from any
 * entry that runs while the user is locked, and the full start never runs next to it:
 * the unlock handover (drain, then full start) is the only door.
 */
class LockedPipelineGateTest {

    // region start decision: who starts the locked pipeline
    @Test
    fun startsWhileLockedWhenEnabledAndLinked() {
        assertTrue(
            LockedPipelineGate.shouldStartLocked(
                unlocked = false, enabled = true, linked = true, migrated = true, sdkInt = 30,
            )
        )
    }

    @Test
    fun neverStartsOnceUnlocked() {
        assertFalse(
            LockedPipelineGate.shouldStartLocked(
                unlocked = true, enabled = true, linked = true, migrated = true, sdkInt = 30,
            )
        )
    }

    @Test
    fun neverStartsWhenDisabledOrNotLinked() {
        assertFalse(LockedPipelineGate.shouldStartLocked(false, enabled = false, linked = true, migrated = true, sdkInt = 30))
        assertFalse(LockedPipelineGate.shouldStartLocked(false, enabled = true, linked = false, migrated = true, sdkInt = 30))
    }

    @Test
    fun neverStartsOnDeviceProtectedStorageThatWasNotMigrated() {
        assertFalse(
            LockedPipelineGate.shouldStartLocked(
                unlocked = false, enabled = true, linked = true, migrated = false, sdkInt = 30,
            )
        )
    }

    @Test
    fun neverStartsBelowDirectBootApi() {
        assertFalse(
            LockedPipelineGate.shouldStartLocked(
                unlocked = false, enabled = true, linked = true, migrated = true, sdkInt = 23,
            )
        )
    }
    // endregion

    // region idempotence and handover
    @Test
    fun startIsIdempotent() {
        val gate = LockedPipelineGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun aFailedStartCanBeRetried() {
        val gate = LockedPipelineGate()
        assertTrue(gate.tryStart())
        gate.finish()
        assertTrue(gate.tryStart())
    }

    @Test
    fun handoverRunsOnce() {
        val gate = LockedPipelineGate()
        gate.tryStart()
        assertTrue(gate.beginHandover())
        assertFalse(gate.beginHandover())
    }

    @Test
    fun handoverNeedsTheLockedPipeline() {
        assertFalse(LockedPipelineGate().beginHandover())
    }

    @Test
    fun fullStartRunsAtOnceWhenNoLockedPipelineExists() {
        assertEquals(FullStart.RUN_NOW, LockedPipelineGate().requestFullStart())
    }

    @Test
    fun fullStartWhileLockedPipelineRunsIsLeftToTheHandover() {
        val gate = LockedPipelineGate()
        gate.tryStart()
        // BOOT_COMPLETED arrives after the unlock; the handover must start it, after the drain
        assertEquals(FullStart.START_HANDOVER, gate.requestFullStart())
        // and the handover is now claimed: a second request just waits
        assertEquals(FullStart.WAIT_FOR_HANDOVER, gate.requestFullStart())
        assertFalse(gate.beginHandover())
    }

    @Test
    fun fullStartWaitsWhileTheHandoverDrains() {
        val gate = LockedPipelineGate()
        gate.tryStart()
        assertTrue(gate.beginHandover())
        assertEquals(FullStart.WAIT_FOR_HANDOVER, gate.requestFullStart())
    }

    @Test
    fun afterTheHandoverFinishesTheFullStartRunsDirectly() {
        val gate = LockedPipelineGate()
        gate.tryStart()
        gate.beginHandover()
        gate.finish()
        assertEquals(FullStart.RUN_NOW, gate.requestFullStart())
    }
    // endregion
}
