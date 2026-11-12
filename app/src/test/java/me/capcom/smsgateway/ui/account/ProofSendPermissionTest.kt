package me.capcom.smsgateway.ui.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.account.LineVerifyStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-22: the proof (START_SIM_LINE_MO) must not start unless SEND_SMS is granted at that
 * moment. A denied permission means no server call and no SMS; granted means one start, one SMS.
 */
internal class ProofSendPermissionTest {

    private class Sender : LineSmsSender {
        val calls = mutableListOf<Triple<Int, String, String>>()
        override suspend fun send(slot: Int, to: String, text: String): Boolean {
            calls.add(Triple(slot, to, text))
            return true
        }
    }

    private class Clock {
        var now = 0L
    }

    private val mo = LineVerifyStart("MO", "+967700000001", "MNASEB 7K2Q-SECRET", 600)
    private val row = NumberRow(slot = 1, carrier = "Yemen Mobile", readableNumber = null)

    private fun verifier(
        client: FakeAppClient,
        sender: LineSmsSender,
        clock: Clock = Clock(),
        canSendSms: () -> Boolean,
    ) = NumbersVerifier(
        client = client,
        sender = sender,
        delayMs = { clock.now += it },
        nowMs = { clock.now },
        canSendSms = canSendSms,
    )

    @Test
    fun aDeniedSendSmsMakesNoServerCallAndNoSend() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        val sender = Sender()

        val result = verifier(client, sender) { false }.verify(row)

        assertEquals(RowPhase.NEEDS_PERMISSION, result.phase)
        assertEquals(0, client.verifyCalls.size)
        assertEquals(0, sender.calls.size)
        assertEquals(0, client.linesCalls)
    }

    @Test
    fun aGrantedSendSmsStartsTheProofOnceAndSendsOnce() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { listOf(line(slot = 1)) }
        val sender = Sender()

        val result = verifier(client, sender) { true }.verify(row)

        assertEquals(RowPhase.VERIFIED, result.phase)
        assertEquals(1, client.verifyCalls.size)
        assertEquals(1, sender.calls.size)
    }

    @Test
    fun theDeniedTapIsNotAnAttemptAndTheNextGrantedTapStartsNormally() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        client.linesAnswer = { listOf(line(slot = 1)) }
        val sender = Sender()
        var granted = false
        val v = verifier(client, sender) { granted }

        val denied = v.verify(row)
        assertEquals(RowPhase.NEEDS_PERMISSION, denied.phase)

        granted = true
        val allowed = v.verify(denied)

        assertEquals(RowPhase.VERIFIED, allowed.phase)
        assertEquals(1, client.verifyCalls.size)
        assertEquals(1, sender.calls.size)
    }

    @Test
    fun aPermissionRevokedBetweenTheStartAndTheSendStopsTheSendAndKeepsTheTokenForOneResend() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = {
            LineVerifyStart("MO", "+967700000001", "MNASEB 7K2Q-SECRET", 600)
        }
        client.linesAnswer = { listOf(line(slot = 1)) }
        val sender = Sender()
        // Granted for the first check (before the server is asked), revoked for the second
        // (immediately before the radio), granted again on the next tap.
        val answers = ArrayDeque(listOf(true, false, true, true))
        val v = verifier(client, sender) { answers.removeFirst() }

        val first = v.verify(row)
        assertEquals(RowPhase.NEEDS_PERMISSION, first.phase)
        assertEquals(1, client.verifyCalls.size)
        assertEquals(0, sender.calls.size)

        // The platform would answer LINE_PROOF_OPEN to a second start: it must not be asked.
        val second = v.verify(first)
        assertEquals(RowPhase.VERIFIED, second.phase)
        assertEquals(1, client.verifyCalls.size)
        assertEquals(1, sender.calls.size)
    }

    @Test
    fun aDeniedPermissionKeepsARowThatAsksForANumberAndTheTypedNumber() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { mo }
        val sender = Sender()
        val asking = row.copy(phase = RowPhase.NEEDS_NUMBER, typed = "+967700000999", readableNumber = "+967700000714")

        // The verifier leaves it as it is.
        val result = verifier(client, sender) { false }.verify(asking)
        assertEquals(asking, result)
        assertEquals(0, client.verifyCalls.size)
        assertEquals(0, sender.calls.size)

        // And so does the ViewModel's marking: only an idle ticked row says NEEDS_PERMISSION.
        val idle = NumberRow(slot = 2, carrier = null, readableNumber = null)
        val marked = NumbersState.markPermissionNeeded(listOf(asking, idle))
        assertEquals(asking, marked[0])
        assertEquals(RowPhase.NEEDS_PERMISSION, marked[1].phase)
        assertTrue(marked[0].asksForNumber)
    }

    // V2-L25 (rev-fork21-2 L9): a denied prompt must change something visible on the screen,
    // also when every ticked row is NEEDS_NUMBER (its row keeps its phase).
    @Test
    fun aDeniedPromptShowsTheMessageOnTheScreenWhateverThePhaseOfTheRows() {
        val source = me.capcom.smsgateway.modules.gateway.PauseGateSources.read("ui/account/NumbersFragment.kt")
        val start = source.indexOf("viewModel.permissionDenied()")
        assertTrue(start >= 0)
        val after = source.substring(start, minOf(source.length, start + 300))
        assertTrue(after.contains("numbers_status_needs_permission"))
    }

    // V2-L25 (rev-fork21-2 L10): after a revoke the kept text belongs to the number that was
    // typed at the start; an edited number must not be ignored (and must not get that text).
    @Test
    fun anEditedNumberAfterARevokeDoesNotSendTheKeptTextToTheOriginalNumber() = runBlocking {
        val client = FakeAppClient()
        client.verifyAnswer = { LineVerifyStart("LOOPBACK", "+967700000999", "MNASEB 7K2Q-SECRET", 600) }
        val sender = Sender()
        val answers = ArrayDeque(listOf(true, false, true, true))
        val v = verifier(client, sender) { answers.removeFirst() }
        val asking = row.copy(phase = RowPhase.NEEDS_NUMBER, typed = "+967700000999")

        val first = v.verify(asking)
        assertEquals(0, sender.calls.size)

        // the keeper edits the number and taps again
        client.verifyAnswer = { throw me.capcom.smsgateway.modules.account.AccountException(
            me.capcom.smsgateway.modules.account.AccountError("LINE_PROOF_OPEN", 409)) }
        val second = v.verify(first.copy(typed = "+967700000888"))

        assertEquals("nothing is sent to the original number", 0, sender.calls.size)
        assertEquals(RowPhase.OPEN_PROOF, second.phase)
        assertEquals(2, client.verifyCalls.size)
    }
}
