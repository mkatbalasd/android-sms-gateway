package me.capcom.smsgateway.modules.account

import com.google.gson.JsonParser
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** V2-L33: the wire shapes of the two public reset calls, on the app host. */
internal class PasswordResetApiTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String?>()

    private fun api(status: HttpStatusCode, body: String): AccountApi {
        val engine = MockEngine { request ->
            requests.add(request)
            bodies.add((request.body as? TextContent)?.text)
            respond(content = body, status = status, headers = json)
        }
        return AccountApi(engine = engine)
    }

    private fun failureOf(block: suspend () -> Unit): AccountError {
        try {
            runBlocking { block() }
        } catch (e: AccountException) {
            return e.error
        }
        fail("expected an AccountException")
        throw IllegalStateException()
    }

    @Test
    fun startPostsTheNumberAndLocaleToTheAppHostWithNoCredential() = runBlocking {
        val api = api(HttpStatusCode.Accepted, """{"accepted":true}""")

        api.resetStart(ResetStartRequest("+967700000200", "ar"))

        val request = requests.single()
        assertEquals("https://app.mnaseb.com/api/auth/reset/start", request.url.toString())
        assertEquals("POST", request.method.value)
        assertNull(request.headers[HttpHeaders.Authorization])
        val body = JsonParser.parseString(bodies.single()).asJsonObject
        assertEquals(setOf("contact", "locale"), body.keySet())
        assertEquals("+967700000200", body.get("contact").asString)
        assertEquals("ar", body.get("locale").asString)
    }

    @Test
    fun startIsNeutralWhateverTheAcceptedBodyHolds() = runBlocking {
        // Any 2xx is "accepted": the body is not read, so an odd body cannot become a signal.
        api(HttpStatusCode.Accepted, "{}").resetStart(ResetStartRequest("+967700000200", "en"))
        api(HttpStatusCode.Accepted, "").resetStart(ResetStartRequest("+967700000201", "en"))
        assertEquals(2, requests.size)
    }

    @Test
    fun startRateLimitCarriesOnlyTheWait() {
        val api = api(
            HttpStatusCode.TooManyRequests,
            """{"success":false,"error":{"code":"RESET_RATE_LIMIT","message":"x","details":{"retry_after_seconds":1800}}}"""
        )
        val error = failureOf { api.resetStart(ResetStartRequest("+967700000200", "ar")) }
        assertEquals("RESET_RATE_LIMIT", error.code)
        assertEquals(429, error.httpStatus)
        assertEquals(1800, error.retryAfterSeconds)
    }

    @Test
    fun startUnavailableIsA503() {
        val api = api(HttpStatusCode.ServiceUnavailable, "<html>down</html>")
        val error = failureOf { api.resetStart(ResetStartRequest("+967700000200", "ar")) }
        assertEquals(503, error.httpStatus)
        assertEquals("HTTP_503", error.code)
    }

    @Test
    fun verifyPostsTheDocumentedBody() = runBlocking {
        val api = api(HttpStatusCode.OK, """{"success":true}""")

        api.resetVerify(ResetVerifyRequest("+967700000200", "123456", "a-long-new-password"))

        val request = requests.single()
        assertEquals("https://app.mnaseb.com/api/auth/reset/verify", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
        val body = JsonParser.parseString(bodies.single()).asJsonObject
        assertEquals(setOf("contact", "code", "new_password"), body.keySet())
        assertEquals("123456", body.get("code").asString)
        assertEquals("a-long-new-password", body.get("new_password").asString)
    }

    @Test
    fun verifyOtpInvalidIsOneAnswer() {
        val api = api(
            HttpStatusCode.BadRequest,
            """{"success":false,"error":{"code":"OTP_INVALID","message":"x"}}"""
        )
        val error = failureOf { api.resetVerify(ResetVerifyRequest("+967700000200", "000000", "a-long-new-password")) }
        assertEquals("OTP_INVALID", error.code)
        assertNull(error.attemptsLeft)
    }

    @Test
    fun verifyAnswerWithoutSuccessIsNotASuccess() {
        val api = api(HttpStatusCode.OK, "{}")
        val error = failureOf { api.resetVerify(ResetVerifyRequest("+967700000200", "123456", "a-long-new-password")) }
        assertEquals("BAD_RESPONSE", error.code)
    }

    @Test
    fun theOtherCallsKeepTheirBaseUrl() = runBlocking {
        val api = api(HttpStatusCode.Accepted, """{"login_id":"L1","otp":{"channel":"SMS"}}""")
        api.login(LoginRequest("+967700000200", "pw", DeviceInfo("i", "m", "o", "v"), "ar"))
        assertEquals("https://api.mnaseb.com/v1/app/login", requests.single().url.toString())
    }

    @Test
    fun requestsNeverPrintTheirSecrets() {
        val start = ResetStartRequest("+967700000200", "ar").toString()
        val verify = ResetVerifyRequest("+967700000200", "123456", "a-long-new-password").toString()
        for (text in listOf(start, verify)) {
            assertFalse(text.contains("967700000200"))
            assertFalse(text.contains("123456"))
            assertFalse(text.contains("a-long-new-password"))
            assertTrue(text.contains("redacted"))
        }
    }
}
