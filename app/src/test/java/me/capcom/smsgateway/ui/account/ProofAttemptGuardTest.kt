package me.capcom.smsgateway.ui.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.account.AccountError
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.LineVerifyStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD-18 T2: one tap, one proof SMS. Never an automatic or repeated send. */
internal class ProofAttemptGuardTest {

    private class Sender(private val outcome: () -> Boolean = { true }) : LineSmsSender {
        var calls = 0
        override suspend fun send(slot: Int, to: String, text: String): Boolean {
            calls++
            return outcome()
        }
    }

    private class Clock {
        var now = 0L
    }

    private val row = NumberRow(slot = 1, carrier = "Yemen Mobile", readableNumber = null)
    private val tokenA = LineVerifyStart("MO", "+967700000001", "MNASEB AAAA-1111", 600)
    private val tokenB = LineVerifyStart("MO", "+967700000001", "MNASEB BBBB-2222", 600)

    private fun verifier(client: FakeAppClient, sender: LineSmsSender, log: AttemptLog, clock: Clock) =
        NumbersVerifier(
            client = client,
            sender = sender,
            delayMs = { clock.now += it },
            nowMs = { clock.now },
            guard = ProofAttemptGuard(log) { clock.now },
        )

    @Test
    fun theGuardAdmitsAnAttemptOnceAndAGivenBackOneAgain() {
        val clock = Clock()
        val guard = ProofAttemptGuard(MemoryAttemptLog()) { clock.now }
        val key = ProofAttemptGuard.key(1, "+967700000001", "MNASEB AAAA-1111")

        assertTrue(guard.tryBegin(key))
        assertFalse(guard.tryBegin(key))
        guard.release(key)
        assertTrue(guard.tryBegin(key))
    }

    @Test
    fun theKeyIsAHashAndNeverTheTokenText() {
        val key = ProofAttemptGuard.key(1, "+967700000001", "MNASEB AAAA-1111")
        assertEquals(64, key.length)
        assertFalse(key.contains("AAAA"))
        assertFalse(
            ProofAttemptGuard.key(1, "+967700000001", "MNASEB AAAA-1111") ==
                ProofAttemptGuard.key(2, "+967700000001", "MNASEB AAAA-1111")
        )
    }

    @Test
    fun aRepeatedTriggerWithTheSameTokenSendsNothingEvenAfterAProcessRestore() = runBlocking {
        val clock = Clock()
        val log = MemoryAttemptLog() // stands for the preferences file that survives a restart
        val sender = Sender()
        val client = FakeAppClient()
        client.verifyAnswer = { tokenA }
        client.linesAnswer = { listOf(line(slot = 1)) }

        assertEquals(RowPhase.VERIFIED, verifier(client, sender, log, clock).verify(row).phase)
        assertEquals(1, sender.calls)

        // A new process: a new verifier, the same persisted log, the platform hands out the same token.
        val again = verifier(client, sender, log, clock).verify(row)

        assertEquals(1, sender.calls)
        assertEquals(RowPhase.OPEN_PROOF, again.phase)
    }

    @Test
    fun aRefusedTokenSendsNothingAndTheNextExplicitTapFetchesANewTokenAndSendsOnce() = runBlocking {
        val clock = Clock()
        val log = MemoryAttemptLog()
        val sender = Sender()
        val client = FakeAppClient()
        client.linesAnswer = { listOf(line(slot = 1)) }

        client.verifyAnswer = { throw AccountException(AccountError("LINE_PROOF_OPEN", 409)) }
        val refused = verifier(client, sender, log, clock).verify(row)
        assertEquals(RowPhase.OPEN_PROOF, refused.phase)
        assertEquals(0, sender.calls)

        client.verifyAnswer = { tokenB }
        val next = verifier(client, sender, log, clock).verify(refused)
        assertEquals(RowPhase.VERIFIED, next.phase)
        assertEquals(1, sender.calls)
        assertEquals(2, client.verifyCalls.size)
    }

    @Test
    fun anUnknownOutcomeIsNeverResentAndTheNextTapOnlyAsksThePlatform() = runBlocking {
        val clock = Clock()
        val log = MemoryAttemptLog()
        var unknown = true
        val sender = object : LineSmsSender {
            var calls = 0
            override suspend fun send(slot: Int, to: String, text: String): Boolean {
                calls++
                if (unknown) throw SendOutcomeUnknown()
                return true
            }
        }
        val client = FakeAppClient()
        client.verifyAnswer = { tokenA }
        client.linesAnswer = { emptyList() }
        val v = verifier(client, sender, log, clock)

        val first = v.verify(row)
        // The system may still send it: the row waits and times out, it does not say "failed to send".
        assertEquals(RowPhase.TIMED_OUT, first.phase)

        // The platform keeps the token open, so a new tap is refused and nothing is resent.
        unknown = false
        client.verifyAnswer = { throw AccountException(AccountError("LINE_PROOF_OPEN", 409)) }
        val second = v.verify(first)

        assertEquals(RowPhase.OPEN_PROOF, second.phase)
        assertEquals(1, sender.calls)
    }

    @Test
    fun aDefiniteFailureAfterARestartCannotResendTheOldTextBecauseItIsOnlyInMemory() = runBlocking {
        val clock = Clock()
        val log = MemoryAttemptLog()
        val client = FakeAppClient()
        client.verifyAnswer = { tokenA }
        val failing = Sender { false }

        val first = verifier(client, failing, log, clock).verify(row)
        assertEquals(RowPhase.SEND_FAILED, first.phase)

        // Restart: the kept text is gone; the platform still holds the token open and refuses a new start.
        client.verifyAnswer = { throw AccountException(AccountError("LINE_PROOF_OPEN", 409)) }
        val ok = Sender()
        val second = verifier(client, ok, log, clock).verify(first)

        assertEquals(RowPhase.OPEN_PROOF, second.phase)
        assertEquals(0, ok.calls)
    }

    @Test
    fun aSecondTriggerOnTheSameSlotWhileOneRunsDoesNothing() = runBlocking {
        val clock = Clock()
        val client = FakeAppClient()
        client.verifyAnswer = { tokenA }
        client.linesAnswer = { listOf(line(slot = 1)) }
        lateinit var v: NumbersVerifier
        var inner: NumberRow? = null
        val sender = object : LineSmsSender {
            var calls = 0
            override suspend fun send(slot: Int, to: String, text: String): Boolean {
                calls++
                inner = v.verify(row) // a re-entrant trigger in the middle of the send
                return true
            }
        }
        v = verifier(client, sender, MemoryAttemptLog(), clock)

        v.verify(row)

        assertEquals(1, sender.calls)
        assertEquals(row, inner)
        assertEquals(1, client.verifyCalls.size)
    }
}
