package me.capcom.smsgateway.ui.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.account.AccountError
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.LineVerifyStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NumbersStateTest {

    private class Sender(val result: Boolean = true) : LineSmsSender {
        val calls = mutableListOf<Triple<Int, String, String>>()
        override suspend fun send(slot: Int, to: String, text: String): Boolean {
            calls.add(Triple(slot, to, text))
            return result
        }
    }

    private class Clock {
        var now = 0L
    }

    private fun verifier(client: FakeAppClient, sender: LineSmsSender, clock: Clock = Clock()) =
        NumbersVerifier(
            client = client,
            sender = sender,
            delayMs = { clock.now += it },
            nowMs = { clock.now },
            canSendSms = { true },
        )

    private val mo = LineVerifyStart("MO", "+967700000001", "MNASEB 7K2Q-SECRET", 600)
    private val row = NumberRow(slot = 1, carrier = "Yemen Mobile", readableNumber = null)

    // region slots to rows

    @Test
    fun slotsBecomeRowsInSlotOrderAndAVerifiedSlotStartsVerifiedAndUnticked() {
        val sims = listOf(
            SimSlot(2, "Sabafon", null),
            SimSlot(1, "Yemen Mobile", "+967700000714"),
        )
        val rows = NumbersState.rows(sims, listOf(line(slot = 1)))

        assertEquals(listOf(1, 2), rows.map { it.slot })
        assertTrue(rows[0].verified)
        assertFalse(rows[0].ticked)
        assertEquals("+967******200", rows[0].verifiedMasked)
        assertEquals(RowPhase.IDLE, rows[1].phase)
        assertTrue(rows[1].ticked)
        // A SIM whose number Android hides asks for it; one with a readable number does not.
        assertTrue(rows[1].asksForNumber)
        assertFalse(NumbersState.rows(sims, emptyList())[0].asksForNumber)
    }

    @Test
    fun aPausedLineIsNotAProof() {
        val rows = NumbersState.rows(
            listOf(SimSlot(1, null, null)),
            listOf(line(slot = 1, status = "PAUSED", verifiedAt = null))
        )
        assertFalse(rows.single().verified)
    }

    @Test
    fun theButtonsFollowTheRows() {
        val idle = listOf(row)
        assertTrue(NumbersState.canVerify(idle))
        assertFalse(NumbersState.canContinue(idle))

        val running = listOf(row.copy(phase = RowPhase.WAITING))
        assertFalse(NumbersState.canVerify(running))

        val done = listOf(row.copy(phase = RowPhase.VERIFIED, ticked = false))
        assertFalse(NumbersState.canVerify(done))
        assertTrue(NumbersState.canContinue(done))

        assertFalse(NumbersState.canVerify(listOf(row.copy(ticked = false))))
    }

    // endregion

    @Test
    fun moSendsOneSmsFromTheSlotToThePlatformLineAndEndsVerified() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { call -> if (call < 2) emptyList() else listOf(line(slot = 1)) }
        val sender = Sender()
        val phases = mutableListOf<RowPhase>()

        val result = verifier(client, sender).verify(row) { phases.add(it) }

        assertEquals(RowPhase.VERIFIED, result.phase)
        assertEquals("MO", result.method)
        assertFalse(result.weak)
        assertEquals("+967******200", result.verifiedMasked)
        // Nothing typed: the platform discovers the number.
        assertEquals(listOf(1 to null), client.verifyCalls)
        assertEquals(listOf(Triple(1, "+967700000001", "MNASEB 7K2Q-SECRET")), sender.calls)
        assertEquals(
            listOf(RowPhase.STARTING, RowPhase.SENDING, RowPhase.WAITING),
            phases
        )
        assertEquals(3, client.linesCalls)
    }

    @Test
    fun aTypedNumberIsSentAsMsisdn() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { listOf(line(slot = 1)) }

        verifier(client, Sender()).verify(row.copy(typed = " +967700000200 "))

        assertEquals(listOf(1 to "+967700000200"), client.verifyCalls)
    }

    @Test
    fun loopbackSendsToTheReturnedNumberAndIsMarkedWeak() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { LineVerifyStart("LOOPBACK", "+967700000200", "code 123456", 600) }
        client.linesAnswer = { listOf(line(slot = 1, method = "LOOPBACK")) }
        val sender = Sender()

        val result = verifier(client, sender).verify(row.copy(typed = "+967700000200"))

        assertEquals(RowPhase.VERIFIED, result.phase)
        assertTrue(result.weak)
        assertEquals(listOf(Triple(1, "+967700000200", "code 123456")), sender.calls)
    }

    @Test
    fun noPlatformLineAsksForTheNumberAndSendsNothing() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { throw AccountException(AccountError("NO_PLATFORM_LINE", 409)) }
        val sender = Sender()

        val result = verifier(client, sender).verify(row)

        assertEquals(RowPhase.NEEDS_NUMBER, result.phase)
        assertTrue(result.asksForNumber)
        assertTrue(sender.calls.isEmpty())
        assertEquals(0, client.linesCalls)
    }

    @Test
    fun aSendFailureStopsWithoutPolling() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        val sender = Sender(result = false)

        val result = verifier(client, sender).verify(row)

        assertEquals(RowPhase.SEND_FAILED, result.phase)
        assertEquals(1, sender.calls.size)
        assertEquals(0, client.linesCalls)
    }

    @Test
    fun aSenderThatThrowsIsASendFailure() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        val sender = object : LineSmsSender {
            override suspend fun send(slot: Int, to: String, text: String): Boolean =
                throw SecurityException("no permission")
        }

        assertEquals(RowPhase.SEND_FAILED, verifier(client, sender).verify(row).phase)
    }

    @Test
    fun noProofWithinNinetySecondsIsATimeoutPolledEveryThreeSeconds() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { emptyList() }
        val clock = Clock()

        val result = verifier(client, Sender(), clock).verify(row)

        assertEquals(RowPhase.TIMED_OUT, result.phase)
        assertEquals(90_000L, clock.now)
        assertEquals(30, client.linesCalls)
    }

    @Test
    fun anOpenProofWithNothingToResendSaysWhenToRetryAndSendsNothing() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { throw AccountException(AccountError("LINE_PROOF_OPEN", 409)) }
        val sender = Sender()

        val result = verifier(client, sender).verify(row)

        assertEquals(RowPhase.OPEN_PROOF, result.phase)
        assertEquals(10, result.retryMinutes)
        assertTrue(sender.calls.isEmpty())
        assertEquals(0, client.linesCalls)
    }

    @Test
    fun aFailedSendIsResentOnTheNextTapWithoutAskingThePlatformAgain() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        // The radio refuses first; then the keeper tops up and taps again.
        var radioOk = false
        val calls = mutableListOf<Triple<Int, String, String>>()
        val sender = object : LineSmsSender {
            override suspend fun send(slot: Int, to: String, text: String): Boolean {
                calls.add(Triple(slot, to, text))
                return radioOk
            }
        }
        client.linesAnswer = { listOf(line(slot = 1)) }
        val v = verifier(client, sender)

        val first = v.verify(row)
        assertEquals(RowPhase.SEND_FAILED, first.phase)

        // A second start would be 409 LINE_PROOF_OPEN: it must not even be asked.
        client.verifyAnswer = { throw AccountException(AccountError("LINE_PROOF_OPEN", 409)) }
        radioOk = true
        val second = v.verify(first)

        assertEquals(RowPhase.VERIFIED, second.phase)
        assertEquals(1, client.verifyCalls.size)
        assertEquals(2, calls.size)
        assertEquals(calls[0], calls[1])
    }

    @Test
    fun anExpiredKeptTextIsDroppedAndAFreshStartIsAsked() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        val clock = Clock()
        var radioOk = false
        val sender = object : LineSmsSender {
            override suspend fun send(slot: Int, to: String, text: String) = radioOk
        }
        val v = verifier(client, sender, clock)
        val first = v.verify(row)
        assertEquals(RowPhase.SEND_FAILED, first.phase)

        clock.now += 601_000L
        radioOk = true
        client.linesAnswer = { listOf(line(slot = 1)) }
        v.verify(first)

        assertEquals(2, client.verifyCalls.size)
    }

    @Test
    fun aDestinationThatIsNotANumberIsRefusedBeforeAnySms() = runBlocking {
        val sender = Sender()
        for (bad in listOf("", "evil", "+1 800", "http://x.example", "12", "+1234567890123456")) {
            val client = FakeAppClient()
            client.verifyAnswer = { LineVerifyStart("MO", bad, "tok", 600) }
            val result = verifier(client, sender).verify(row)
            assertEquals("for '$bad'", RowPhase.FAILED, result.phase)
        }
        assertTrue(sender.calls.isEmpty())
        assertTrue(NumbersVerifier.plausibleDestination("+967700000001"))
        assertTrue(NumbersVerifier.plausibleDestination("714"))
    }

    @Test
    fun aRefusalOtherThanTheKnownOnesIsAFailureWithItsCode() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { throw AccountException(AccountError("NUMBER_HELD", 409)) }

        val result = verifier(client, Sender()).verify(row)

        assertEquals(RowPhase.FAILED, result.phase)
        assertEquals("NUMBER_HELD", result.errorCode)
    }

    @Test
    fun aDroppedPollIsRetriedAndAnOldProofIsNotTakenForTheNewOne() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { call ->
            when (call) {
                0 -> throw java.io.IOException("offline")
                // The proof of an earlier tap, same time stamp as the row already had.
                1 -> listOf(line(slot = 1, verifiedAt = "2026-11-09T10:00:00.000Z"))
                else -> listOf(line(slot = 1, verifiedAt = "2026-11-10T10:00:00.000Z"))
            }
        }

        val result = verifier(client, Sender()).verify(row.copy(verifiedAt = "2026-11-09T10:00:00.000Z"))

        assertEquals(RowPhase.VERIFIED, result.phase)
        assertEquals("2026-11-10T10:00:00.000Z", result.verifiedAt)
        assertEquals(3, client.linesCalls)
    }

    @Test
    fun theTokenTextIsNotInTheRowOrItsPrintout() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { listOf(line(slot = 1)) }

        val result = verifier(client, Sender()).verify(row)

        assertFalse(result.toString().contains("SECRET"))
        assertFalse(mo.toString().contains("SECRET"))
        assertNull(result.errorCode)
    }
}
