package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class EnrolmentFlowTest {

    private val enrolment = Enrolment(
        serverUrl = "https://smsgate.mnaseb.com/api/mobile/v1",
        loginCode = "7wr3bd",
        codeExpiresAt = "2026-11-08T10:01:00.000Z",
        label = "Mnaseb SIM/Pixel/AB12CD34",
        signingKey = "K".repeat(43),
        nonce = "AB12CD34",
        deviceId = "dev-1",
    )

    private class Steps(
        private val onMint: () -> EnrolmentSteps.Mint = { error("mint not expected") },
        private val onRegister: (Enrolment) -> EnrolmentSteps.Register =
            { EnrolmentSteps.Register.Ok("gw-1") },
        private val onComplete: (String, String) -> EnrolmentSteps.Complete =
            { _, _ -> EnrolmentSteps.Complete.Ok },
    ) : EnrolmentSteps {
        var mints = 0
        var registers = 0
        var completes = 0
        var rollbacks = 0
        val registered = mutableListOf<Enrolment>()
        val completed = mutableListOf<Pair<String, String>>()

        override suspend fun mint(): EnrolmentSteps.Mint {
            mints++
            return onMint()
        }

        override suspend fun register(enrolment: Enrolment): EnrolmentSteps.Register {
            registers++
            registered.add(enrolment)
            return onRegister(enrolment)
        }

        override suspend fun complete(gatewayDeviceId: String, nonce: String): EnrolmentSteps.Complete {
            completes++
            completed.add(gatewayDeviceId to nonce)
            return onComplete(gatewayDeviceId, nonce)
        }

        override suspend fun rollback() {
            rollbacks++
        }
    }

    private fun run(steps: Steps, initial: Enrolment?): Pair<EnrolmentState, List<Long>> {
        val pauses = mutableListOf<Long>()
        val flow = EnrolmentFlow(steps) { pauses.add(it) }
        val result = runBlocking { flow.run(initial) }
        assertEquals(result, flow.state.value)
        return result to pauses
    }

    @Test
    fun successWithTheEnrolmentFromVerifyLinks() {
        val steps = Steps()
        val (state, pauses) = run(steps, enrolment)

        assertEquals(EnrolmentState.Linked, state)
        assertEquals(0, steps.mints)
        assertEquals(1, steps.registers)
        assertEquals(listOf("gw-1" to "AB12CD34"), steps.completed)
        assertEquals(0, steps.rollbacks)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun nullEnrolmentPollsMintEveryFiveSecondsThenLinks() {
        var calls = 0
        val steps = Steps(onMint = {
            calls++
            if (calls <= 3) EnrolmentSteps.Mint.NotReady else EnrolmentSteps.Mint.Ok(enrolment)
        })
        val (state, pauses) = run(steps, null)

        assertEquals(EnrolmentState.Linked, state)
        assertEquals(4, steps.mints)
        assertEquals(listOf(5_000L, 5_000L, 5_000L), pauses)
    }

    @Test
    fun mintThatNeverGetsReadyFailsAfterSixtySeconds() {
        val steps = Steps(onMint = { EnrolmentSteps.Mint.NotReady })
        val (state, pauses) = run(steps, null)

        assertEquals(EnrolmentState.Failed(EnrolmentFlow.Reasons.NOT_READY), state)
        assertEquals(13, steps.mints)
        assertEquals(60_000L, pauses.sum())
        assertEquals(0, steps.registers)
    }

    @Test
    fun aMintErrorStopsAtOnce() {
        val steps = Steps(onMint = { EnrolmentSteps.Mint.Error("ACCOUNT_LOCKED") })
        val (state, pauses) = run(steps, null)

        assertEquals(EnrolmentState.Failed("ACCOUNT_LOCKED"), state)
        assertEquals(1, steps.mints)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun registerRefusedOnceMintsAgainOnceAndLinks() {
        val second = enrolment.copy(loginCode = "second", nonce = "ZZ99YY88")
        var attempt = 0
        val steps = Steps(
            onMint = { EnrolmentSteps.Mint.Ok(second) },
            onRegister = {
                attempt++
                if (attempt == 1) EnrolmentSteps.Register.CodeRefused else EnrolmentSteps.Register.Ok("gw-2")
            },
        )
        val (state, _) = run(steps, enrolment)

        assertEquals(EnrolmentState.Linked, state)
        assertEquals(1, steps.mints)
        assertEquals(2, steps.registers)
        assertEquals(listOf(enrolment, second), steps.registered)
        // The nonce of the second enrolment is the one that is completed.
        assertEquals(listOf("gw-2" to "ZZ99YY88"), steps.completed)
    }

    @Test
    fun registerRefusedTwiceFailsAfterExactlyOneReMint() {
        val steps = Steps(
            onMint = { EnrolmentSteps.Mint.Ok(enrolment) },
            onRegister = { EnrolmentSteps.Register.CodeRefused },
        )
        val (state, _) = run(steps, enrolment)

        assertEquals(EnrolmentState.Failed(EnrolmentFlow.Reasons.CODE_REFUSED), state)
        assertEquals(1, steps.mints)
        assertEquals(2, steps.registers)
        assertEquals(0, steps.completes)
    }

    @Test
    fun aRegisterErrorIsReportedWithoutRetry() {
        val steps = Steps(onRegister = { EnrolmentSteps.Register.Error("ENROLMENT_INVALID") })
        val (state, _) = run(steps, enrolment)

        assertEquals(EnrolmentState.Failed("ENROLMENT_INVALID"), state)
        assertEquals(1, steps.registers)
        assertEquals(0, steps.mints)
    }

    @Test
    fun notListedIsRetriedThenLinks() {
        var calls = 0
        val steps = Steps(onComplete = { _, _ ->
            calls++
            if (calls <= 3) EnrolmentSteps.Complete.NotListed else EnrolmentSteps.Complete.Ok
        })
        val (state, pauses) = run(steps, enrolment)

        assertEquals(EnrolmentState.Linked, state)
        assertEquals(4, steps.completes)
        assertEquals(listOf(3_000L, 3_000L, 3_000L), pauses)
        assertEquals(0, steps.rollbacks)
    }

    @Test
    fun notListedForSixtySecondsFailsAndUndoesTheRegistration() {
        val steps = Steps(onComplete = { _, _ -> EnrolmentSteps.Complete.NotListed })
        val (state, pauses) = run(steps, enrolment)

        assertEquals(EnrolmentState.Failed(EnrolmentFlow.Reasons.NOT_LISTED), state)
        assertEquals(21, steps.completes)
        assertEquals(60_000L, pauses.sum())
        assertEquals(1, steps.rollbacks)
    }

    @Test
    fun anotherConflictFailsWithItsReasonAndDoesNotRetry() {
        val steps = Steps(onComplete = { _, _ -> EnrolmentSteps.Complete.Terminal("ENROLMENT_NONCE_MISMATCH") })
        val (state, pauses) = run(steps, enrolment)

        assertEquals(EnrolmentState.Failed("ENROLMENT_NONCE_MISMATCH"), state)
        assertEquals(1, steps.completes)
        assertTrue(pauses.isEmpty())
        assertEquals(1, steps.rollbacks)
    }

    @Test
    fun aTransientErrorIsRetriedWithinTheWindow() {
        var calls = 0
        val steps = Steps(onComplete = { _, _ ->
            calls++
            if (calls == 1) EnrolmentSteps.Complete.Transient("NETWORK") else EnrolmentSteps.Complete.Ok
        })
        val (state, _) = run(steps, enrolment)

        assertEquals(EnrolmentState.Linked, state)
        assertEquals(2, steps.completes)
    }

    @Test
    fun aNetworkThatNeverComesBackFailsWithNetworkAndUndoesTheRegistration() {
        val steps = Steps(onComplete = { _, _ -> EnrolmentSteps.Complete.Transient("NETWORK") })
        val (state, _) = run(steps, enrolment)

        assertEquals(EnrolmentState.Failed("NETWORK"), state)
        assertEquals(1, steps.rollbacks)
    }
}
