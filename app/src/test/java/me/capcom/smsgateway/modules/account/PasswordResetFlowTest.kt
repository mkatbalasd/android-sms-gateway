package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** V2-L33: the forgotten-password state machine against a fake backend. */
internal class PasswordResetFlowTest {

    private class FakeBackend : PasswordResetBackend {
        val starts = mutableListOf<ResetStartRequest>()
        val verifies = mutableListOf<ResetVerifyRequest>()
        var startError: Throwable? = null
        var verifyError: Throwable? = null

        override suspend fun resetStart(request: ResetStartRequest) {
            starts.add(request)
            startError?.let { throw it }
        }

        override suspend fun resetVerify(request: ResetVerifyRequest) {
            verifies.add(request)
            verifyError?.let { throw it }
        }
    }

    private val backend = FakeBackend()
    private var clock = 1_000_000L
    private var cleared = 0
    private val flow = PasswordResetFlow(backend, { cleared++ }, { clock })

    private val mobile = "+967700000200"
    private val password = "a-long-new-password"

    private fun error(code: String, status: Int, retry: Int? = null, left: Int? = null) =
        AccountException(AccountError(code, status, retryAfterSeconds = retry, attemptsLeft = left))

    private fun started() = runBlocking {
        assertNull(flow.requestCode(mobile, "ar"))
    }

    @Test
    fun startsOnThePhoneStep() {
        assertEquals(ResetState.EnterPhone, flow.state.value)
    }

    @Test
    fun anImsiShapedNumberIsRefusedAndNothingIsSent() = runBlocking {
        for (bad in listOf("+421030000000000", "421030000000000", "", "abc")) {
            assertEquals(ResetProblem.PHONE_INVALID, flow.requestCode(bad, "ar")?.problem)
        }
        assertTrue(backend.starts.isEmpty())
        assertEquals(ResetState.EnterPhone, flow.state.value)
    }

    @Test
    fun anAcceptedStartAlwaysShowsTheCodeScreenWithTheCountedTimes() {
        started()
        val state = flow.state.value as ResetState.EnterCode
        assertEquals(clock + 60_000L, state.resendAtMs)
        assertEquals(clock + 600_000L, state.expiresAtMs)
        assertFalse(state.dead)
        val sent = backend.starts.single()
        assertEquals(mobile, sent.contact)
        assertEquals("ar", sent.locale)
    }

    @Test
    fun theLocaleIsArabicUnlessEnglish() = runBlocking {
        flow.requestCode(mobile, "fr")
        assertEquals("ar", backend.starts.last().locale)
        flow.reset()
        flow.requestCode(mobile, "en")
        assertEquals("en", backend.starts.last().locale)
    }

    @Test
    fun everyStartFailureOtherThanALimitIsOneNeutralCopy() = runBlocking {
        val errors = listOf(
            error("VALIDATION_ERROR", 400), error("OTP_INVALID", 400), error("HTTP_404", 404),
            error("ACCOUNT_NOT_FOUND", 404), error("INVALID_CREDENTIALS", 401), error("HTTP_500", 500),
        )
        for (e in errors) {
            backend.startError = e
            val failure = flow.requestCode(mobile, "ar")
            assertEquals(ResetFailure(ResetProblem.GENERIC), failure)
            assertEquals(ResetState.EnterPhone, flow.state.value)
        }
    }

    @Test
    fun aRateLimitShowsOnlyTheWaitAndStaysOnThePhoneStep() = runBlocking {
        backend.startError = error("RESET_RATE_LIMIT", 429, retry = 1800)
        val failure = flow.requestCode(mobile, "ar")
        assertEquals(ResetFailure(ResetProblem.RATE_LIMIT, 1800), failure)
        assertEquals(ResetState.EnterPhone, flow.state.value)
    }

    @Test
    fun unavailableAndNetworkAreTheirOwnProblems() = runBlocking {
        backend.startError = error("HTTP_503", 503)
        assertEquals(ResetProblem.UNAVAILABLE, flow.requestCode(mobile, "ar")?.problem)
        backend.startError = IOException("timeout")
        assertEquals(ResetProblem.NETWORK, flow.requestCode(mobile, "ar")?.problem)
        backend.startError = IllegalStateException("boom")
        assertEquals(ResetProblem.GENERIC, flow.requestCode(mobile, "ar")?.problem)
        assertEquals(ResetState.EnterPhone, flow.state.value)
    }

    @Test
    fun resendIsRefusedBefore60SecondsAndAllowedAfter() = runBlocking {
        started()
        assertEquals(ResetProblem.RATE_LIMIT, flow.resend()?.problem)
        assertEquals(1, backend.starts.size)

        clock += 60_000L
        assertNull(flow.resend())
        assertEquals(2, backend.starts.size)
        val state = flow.state.value as ResetState.EnterCode
        assertEquals(clock + 600_000L, state.expiresAtMs)
        assertEquals(clock + 60_000L, state.resendAtMs)
    }

    @Test
    fun aRateLimitOnResendPushesTheNextResendOutAndKeepsTheCodeScreen() = runBlocking {
        started()
        clock += 60_000L
        backend.startError = error("RESET_RATE_LIMIT", 429, retry = 1200)
        val failure = flow.resend()
        assertEquals(ResetFailure(ResetProblem.RATE_LIMIT, 1200), failure)
        val state = flow.state.value as ResetState.EnterCode
        assertEquals(clock + 1_200_000L, state.resendAtMs)
    }

    @Test
    fun submitChecksTheFormOnTheDeviceFirst() = runBlocking {
        started()
        assertEquals(ResetProblem.CODE_LENGTH, flow.submit("12345", password, password)?.problem)
        assertEquals(ResetProblem.PASSWORD_SHORT, flow.submit("123456", "short", "short")?.problem)
        assertEquals(ResetProblem.PASSWORD_MISMATCH, flow.submit("123456", password, password + "x")?.problem)
        assertTrue(backend.verifies.isEmpty())
    }

    @Test
    fun arabicIndicDigitsInTheCodeAreAccepted() = runBlocking {
        started()
        assertNull(flow.submit("١٢٣ ٤٥٦", password, password))
        assertEquals("123456", backend.verifies.single().code)
    }

    @Test
    fun aSuccessSendsTheBodyClearsTheLocalSessionAndEndsTheFlow() = runBlocking {
        started()
        assertNull(flow.submit("123456", password, password))

        val sent = backend.verifies.single()
        assertEquals(mobile, sent.contact)
        assertEquals("123456", sent.code)
        assertEquals(password, sent.newPassword)
        assertEquals(1, cleared)
        assertEquals(ResetState.Done, flow.state.value)
        // The number was forgotten with the flow: a repeat has nothing to send.
        assertEquals(ResetProblem.GENERIC, flow.submit("123456", password, password)?.problem)
        assertEquals(1, backend.verifies.size)
    }

    @Test
    fun aFailureToClearTheLocalSessionDoesNotHideTheSuccess() = runBlocking {
        val failing = PasswordResetFlow(backend, { throw IllegalStateException("disk") }, { clock })
        failing.requestCode(mobile, "ar")
        assertNull(failing.submit("123456", password, password))
        assertEquals(ResetState.Done, failing.state.value)
    }

    @Test
    fun everyWrongCodeIsTheSameAnswerWhateverTheServerSays() = runBlocking {
        started()
        for (e in listOf(
            error("OTP_INVALID", 400), error("OTP_INVALID", 400, left = 2),
            error("OTP_EXPIRED", 400), error("HTTP_400", 400),
        )) {
            backend.verifyError = e
            assertEquals(ResetFailure(ResetProblem.CODE_REJECTED), flow.submit("123456", password, password))
        }
        assertEquals(0, cleared)
    }

    @Test
    fun fiveWrongTriesKillTheCodeAndOfferANewOneAtOnce() = runBlocking {
        started()
        backend.verifyError = error("OTP_INVALID", 400)
        repeat(5) { assertEquals(ResetProblem.CODE_REJECTED, flow.submit("123456", password, password)?.problem) }
        val state = flow.state.value as ResetState.EnterCode
        assertTrue(state.dead)
        assertEquals(clock, state.resendAtMs)
        assertEquals(5, backend.verifies.size)
        // The sixth try never leaves the phone.
        assertEquals(ResetProblem.CODE_EXPIRED, flow.submit("123456", password, password)?.problem)
        assertEquals(5, backend.verifies.size)
        // A new code revives the screen.
        backend.startError = null
        assertNull(flow.resend())
        assertFalse((flow.state.value as ResetState.EnterCode).dead)
    }

    @Test
    fun theTenMinutesAreCountedOnThePhone() = runBlocking {
        started()
        clock += 600_000L
        assertEquals(ResetProblem.CODE_EXPIRED, flow.submit("123456", password, password)?.problem)
        assertTrue(backend.verifies.isEmpty())
        assertNull(flow.resend())
    }

    @Test
    fun aServerRefusalOfThePasswordIsNotACodeProblem() = runBlocking {
        started()
        backend.verifyError = error("VALIDATION_ERROR", 400)
        assertEquals(ResetProblem.PASSWORD_REJECTED, flow.submit("123456", password, password)?.problem)
    }

    @Test
    fun verifyLimitsAndOutagesStayOnTheCodeScreen() = runBlocking {
        started()
        backend.verifyError = error("RESET_RATE_LIMIT", 429, retry = 600)
        assertEquals(ResetFailure(ResetProblem.RATE_LIMIT, 600), flow.submit("123456", password, password))
        backend.verifyError = error("HTTP_503", 503)
        assertEquals(ResetProblem.UNAVAILABLE, flow.submit("123456", password, password)?.problem)
        backend.verifyError = IOException("down")
        assertEquals(ResetProblem.NETWORK, flow.submit("123456", password, password)?.problem)
        assertTrue(flow.state.value is ResetState.EnterCode)
        assertEquals(0, cleared)
    }

    @Test
    fun resetAndChangeNumberForgetTheNumber() = runBlocking {
        started()
        flow.changeNumber()
        assertEquals(ResetState.EnterPhone, flow.state.value)
        assertEquals(ResetProblem.GENERIC, flow.submit("123456", password, password)?.problem)
        assertTrue(backend.verifies.isEmpty())
    }

    @Test
    fun submitOnThePhoneStepDoesNothing() = runBlocking {
        assertNotNull(flow.submit("123456", password, password))
        assertTrue(backend.verifies.isEmpty())
    }
}
