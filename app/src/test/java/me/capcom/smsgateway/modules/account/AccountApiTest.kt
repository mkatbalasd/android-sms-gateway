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
import me.capcom.smsgateway.ui.account.UiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal class AccountApiTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String?>()
    }

    private fun api(
        recorder: Recorder,
        handler: (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ): AccountApi {
        val engine = MockEngine { request ->
            recorder.requests.add(request)
            recorder.bodies.add((request.body as? TextContent)?.text)
            val (status, body) = handler(request)
            respond(content = body, status = status, headers = json)
        }
        return AccountApi(engine = engine)
    }

    private val device = DeviceInfo("install-12345678", "Pixel 7", "Android 14", "0.3.0")

    private fun signupRequest() = SignupRequest(
        businessName = "Demo Owner 1",
        contact = SignupContact("MOBILE", "+967700000200"),
        password = "S3cret-Passw0rd!",
        locale = "ar",
        termsVersion = "draft-1",
        device = device,
    )

    @Test
    fun signupSendsNoAuthorizationAndTheDocumentedBody() = runBlocking {
        val recorder = Recorder()
        val api = api(recorder) { HttpStatusCode.Accepted to """
            {"signup_id":"11111111-1111-1111-1111-111111111111",
             "otp":{"channel":"SMS","sender":"+967700000001","sent_to_masked":"+967******200",
                    "expires_at":"2026-11-08T10:15:00.000Z","resend_after":"2026-11-08T10:01:00.000Z"}}
        """.trimIndent() }

        val started = api.signup(signupRequest())

        val request = recorder.requests.single()
        assertEquals("https://api.mnaseb.com/v1/signup", request.url.toString())
        assertEquals("POST", request.method.value)
        assertNull(request.headers[HttpHeaders.Authorization])
        val body = JsonParser.parseString(recorder.bodies.single()).asJsonObject
        assertEquals("Demo Owner 1", body.get("business_name").asString)
        assertEquals("MOBILE", body.getAsJsonObject("contact").get("type").asString)
        assertEquals("+967700000200", body.getAsJsonObject("contact").get("value").asString)
        assertEquals("ar", body.get("locale").asString)
        assertEquals("draft-1", body.get("terms_version").asString)
        assertEquals("install-12345678", body.getAsJsonObject("device").get("install_id").asString)
        assertEquals("11111111-1111-1111-1111-111111111111", started.signupId)
        assertEquals("+967700000001", started.otp?.sender)
        assertEquals("2026-11-08T10:01:00.000Z", started.otp?.resendAfter)
    }

    @Test
    fun loginAndVerifyAndRefreshCarryNoAuthorization() = runBlocking {
        val recorder = Recorder()
        val api = api(recorder) { request ->
            when (request.url.encodedPath) {
                "/v1/app/login" -> HttpStatusCode.Accepted to """{"login_id":"L1","otp":{"channel":"SMS"}}"""
                "/v1/app/login/verify" -> HttpStatusCode.Created to
                    """{"app_session":{"access":"a1","refresh":"r1","expires_in":900,"session_id":"s"},
                        "tenant":{"id":"t","name":"Demo","mode":"SANDBOX"},"user":{"id":"u"}}"""
                else -> HttpStatusCode.Created to
                    """{"app_session":{"access":"a2","refresh":"r2","expires_in":900,"session_id":"s"}}"""
            }
        }

        api.login(LoginRequest("+967700000200", "pw-not-logged", device, "ar"))
        val verified = api.loginVerify(LoginVerifyRequest("L1", "123456", device))
        val session = api.refresh(RefreshRequest("r1", "install-12345678"))

        assertTrue(recorder.requests.all { it.headers[HttpHeaders.Authorization] == null })
        assertEquals("a1", verified.appSession?.access)
        assertEquals("SANDBOX", verified.tenant?.mode)
        assertEquals("a2", session.access)
        val refreshBody = JsonParser.parseString(recorder.bodies.last()).asJsonObject
        assertEquals("r1", refreshBody.get("refresh").asString)
        assertEquals("install-12345678", refreshBody.get("install_id").asString)
    }

    @Test
    fun enrolmentRoutesCarryTheBearerToken() = runBlocking {
        val recorder = Recorder()
        val api = api(recorder) { request ->
            if (request.url.encodedPath.endsWith("/complete")) {
                HttpStatusCode.OK to """{"ok":true}"""
            } else {
                HttpStatusCode.OK to
                    """{"server_url":"https://smsgate.mnaseb.com/api/mobile/v1","login_code":"7wr3bd",
                        "code_expires_at":"2026-11-08T10:01:00.000Z","label":"Mnaseb SIM/Pixel/AB12CD34",
                        "signing_key":"KEYKEYKEYKEYKEYKEYKEYKEYKEYKEYKE","nonce":"AB12CD34","device_id":"d1"}"""
            }
        }

        val enrolment = api.mintEnrolment("access-1")
        api.completeEnrolment("access-1", EnrolmentCompleteRequest("gw-1", "AB12CD34"))

        assertEquals("https://api.mnaseb.com/v1/app/enrolment", recorder.requests[0].url.toString())
        assertEquals("Bearer access-1", recorder.requests[0].headers[HttpHeaders.Authorization])
        assertNull(recorder.bodies[0])
        assertEquals("https://api.mnaseb.com/v1/app/enrolment/complete", recorder.requests[1].url.toString())
        assertEquals("Bearer access-1", recorder.requests[1].headers[HttpHeaders.Authorization])
        val body = JsonParser.parseString(recorder.bodies[1]).asJsonObject
        assertEquals("gw-1", body.get("gateway_device_id").asString)
        assertEquals("AB12CD34", body.get("nonce").asString)
        assertNotNull(enrolment)
        assertEquals("7wr3bd", enrolment?.loginCode)
        assertEquals("AB12CD34", enrolment?.nonce)
    }

    @Test
    fun anEnrolmentInsideAnEnvelopeIsAlsoRead() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.OK to """{"enrolment":{"login_code":"abc","nonce":"AB12CD34"}}"""
        }
        assertEquals("abc", api.mintEnrolment("a")?.loginCode)
    }

    @Test
    fun anAnswerWithoutAnEnrolmentIsNull() = runBlocking {
        val api = api(Recorder()) { HttpStatusCode.OK to """{"enrolment":null}""" }
        assertNull(api.mintEnrolment("a"))
    }

    @Test
    fun theErrorEnvelopeBecomesAnAccountError() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.Conflict to
                """{"success":false,"error":{"code":"TENANT_NAME_TAKEN","message":"taken"}}"""
        }
        try {
            api.signup(signupRequest())
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("TENANT_NAME_TAKEN", e.error.code)
            assertEquals(409, e.error.httpStatus)
            assertEquals("taken", e.error.message)
            // The exception text carries the code and status only, never the request.
            assertFalse(e.message.orEmpty().contains("S3cret"))
        }
    }

    @Test
    fun rateLimitDetailsAreParsed() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.TooManyRequests to
                """{"success":false,"error":{"code":"LOGIN_RATE_LIMIT","message":"x",
                    "details":{"retry_after_seconds":125}}}"""
        }
        try {
            api.login(LoginRequest("x", "y", device, null))
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("LOGIN_RATE_LIMIT", e.error.code)
            assertEquals(125, e.error.retryAfterSeconds)
            assertEquals(3, UiError.from(e.error).number) // 125 s: three minutes
        }
    }

    @Test
    fun attemptsLeftAreParsedWhenTheServerGivesThem() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.BadRequest to
                """{"success":false,"error":{"code":"OTP_INVALID","message":"x","details":{"attempts_left":3}}}"""
        }
        try {
            api.signupVerify(SignupVerifyRequest("id", "123456"))
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals(3, e.error.attemptsLeft)
        }
    }

    @Test
    fun aValidationFailureWithAnArrayOfDetailsKeepsItsCode() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.BadRequest to
                """{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Validation failed",
                    "details":["password must be longer than 9 characters"],"path":"/v1/signup"}}"""
        }
        try {
            api.signup(signupRequest())
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("VALIDATION_ERROR", e.error.code)
            assertEquals(400, e.error.httpStatus)
            assertEquals(null, e.error.retryAfterSeconds)
        }
    }

    @Test
    fun theTenantThroughputGuardWithAStringDetailKeepsItsCode() = runBlocking {
        val api = api(Recorder()) {
            HttpStatusCode.TooManyRequests to
                """{"success":false,"error":{"code":"TOO_MANY_REQUESTS","message":"Throughput limit exceeded (10 TPS).",
                    "details":"Too Many Requests"}}"""
        }
        try {
            api.mintEnrolment("access-token")
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("TOO_MANY_REQUESTS", e.error.code)
            assertEquals(429, e.error.httpStatus)
        }
    }

    @Test
    fun aBodyThatIsNotAnEnvelopeGetsAnHttpCode() = runBlocking {
        val api = api(Recorder()) { HttpStatusCode.BadGateway to "<html>bad gateway</html>" }
        try {
            api.signup(signupRequest())
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("HTTP_502", e.error.code)
        }
    }

    @Test
    fun theRequestObjectsNeverPrintASecret() {
        assertFalse(signupRequest().toString().contains("S3cret"))
        assertFalse(LoginRequest("c", "S3cret", device, null).toString().contains("S3cret"))
        assertFalse(AppSession("acc-secret", "ref-secret", 900, "s").toString().contains("secret"))
        val enrolment = Enrolment("u", "7wr3bd", null, null, "signing-secret", "n", null)
        assertFalse(enrolment.toString().contains("7wr3bd"))
        assertFalse(enrolment.toString().contains("signing-secret"))
    }

    // region refresh once on 401 (AccountRepository over a real AccountApi on a MockEngine)

    private class Clock(var now: Long = 1_000_000L)

    private fun repository(
        recorder: Recorder,
        settings: AccountSettings,
        clock: Clock,
        handler: (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) = AccountRepository(
        api(recorder, handler), settings, DeviceProfile("Pixel 7", "Android 14", "0.3.0"),
        now = { clock.now },
    )

    private val enrolmentJson =
        """{"login_code":"7wr3bd","nonce":"AB12CD34","signing_key":"KEYKEYKEYKEYKEYKEYKEYKEYKEYKEYKE"}"""

    private fun settingsWithSession(expiresInSeconds: Long = 900): AccountSettings {
        val settings = AccountSettings(FakeKeyValueStorage())
        settings.saveSession(AppSession("old-access", "old-refresh", expiresInSeconds, "s"), 1_000_000L)
        return settings
    }

    @Test
    fun a401IsRefreshedOnceAndTheCallIsRepeated() = runBlocking {
        val recorder = Recorder()
        val settings = settingsWithSession()
        val repo = repository(recorder, settings, Clock()) { request ->
            val path = request.url.encodedPath
            val bearer = request.headers[HttpHeaders.Authorization]
            when {
                path.endsWith("/app/refresh") ->
                    HttpStatusCode.Created to
                        """{"app_session":{"access":"new-access","refresh":"new-refresh","expires_in":900}}"""

                bearer == "Bearer old-access" ->
                    HttpStatusCode.Unauthorized to
                        """{"success":false,"error":{"code":"APP_SESSION_INVALID","message":"x"}}"""

                else -> HttpStatusCode.OK to enrolmentJson
            }
        }

        val enrolment = repo.mintEnrolment()

        assertEquals("7wr3bd", enrolment?.loginCode)
        assertEquals(
            listOf("/v1/app/enrolment", "/v1/app/refresh", "/v1/app/enrolment"),
            recorder.requests.map { it.url.encodedPath }
        )
        assertEquals("Bearer new-access", recorder.requests[2].headers[HttpHeaders.Authorization])
        // The refresh call itself carries no bearer; it sends the refresh token in the body.
        assertNull(recorder.requests[1].headers[HttpHeaders.Authorization])
        assertEquals("new-access", settings.access)
        assertEquals("new-refresh", settings.refresh)
    }

    @Test
    fun aSecond401IsNotRefreshedAgain() = runBlocking {
        val recorder = Recorder()
        val settings = settingsWithSession()
        val repo = repository(recorder, settings, Clock()) { request ->
            if (request.url.encodedPath.endsWith("/app/refresh")) {
                HttpStatusCode.Created to
                    """{"app_session":{"access":"new-access","refresh":"new-refresh","expires_in":900}}"""
            } else {
                HttpStatusCode.Unauthorized to
                    """{"success":false,"error":{"code":"APP_SESSION_INVALID","message":"x"}}"""
            }
        }

        try {
            repo.mintEnrolment()
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("APP_SESSION_INVALID", e.error.code)
        }
        assertEquals(1, recorder.requests.count { it.url.encodedPath.endsWith("/app/refresh") })
        assertEquals(3, recorder.requests.size)
    }

    @Test
    fun aRefusedRefreshForgetsTheSession() = runBlocking {
        val recorder = Recorder()
        val settings = settingsWithSession()
        val repo = repository(recorder, settings, Clock()) {
            HttpStatusCode.Unauthorized to
                """{"success":false,"error":{"code":"APP_SESSION_INVALID","message":"x"}}"""
        }

        try {
            repo.mintEnrolment()
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("APP_SESSION_INVALID", e.error.code)
        }
        assertFalse(settings.hasSession)
        assertNull(settings.access)
    }

    @Test
    fun anAccountLockedAnswerIsNotRefreshed() = runBlocking {
        val recorder = Recorder()
        val repo = repository(recorder, settingsWithSession(), Clock()) {
            HttpStatusCode.Unauthorized to
                """{"success":false,"error":{"code":"ACCOUNT_LOCKED","message":"x"}}"""
        }

        try {
            repo.mintEnrolment()
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("ACCOUNT_LOCKED", e.error.code)
        }
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun anExpiredAccessTokenIsRefreshedBeforeTheCall() = runBlocking {
        val recorder = Recorder()
        val clock = Clock()
        val settings = settingsWithSession(expiresInSeconds = 900)
        clock.now += 901_000L
        val repo = repository(recorder, settings, clock) { request ->
            if (request.url.encodedPath.endsWith("/app/refresh")) {
                HttpStatusCode.Created to
                    """{"app_session":{"access":"fresh","refresh":"fresh-refresh","expires_in":900}}"""
            } else {
                HttpStatusCode.OK to enrolmentJson
            }
        }

        repo.mintEnrolment()

        assertEquals(
            listOf("/v1/app/refresh", "/v1/app/enrolment"),
            recorder.requests.map { it.url.encodedPath }
        )
        assertEquals("Bearer fresh", recorder.requests[1].headers[HttpHeaders.Authorization])
    }

    //endregion
}
