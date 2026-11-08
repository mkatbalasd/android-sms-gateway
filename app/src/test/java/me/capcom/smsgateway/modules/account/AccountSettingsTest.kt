package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AccountSettingsTest {

    private val password = "S3cret-Passw0rd!"

    private val session = AppSession("access-token-1", "refresh-token-1", 900, "session-1")

    @Test
    fun tokensAndIdentityRoundTrip() {
        val storage = FakeKeyValueStorage()
        val settings = AccountSettings(storage)

        settings.saveSession(session, 1_000L)
        settings.saveIdentity(TenantInfo("t-1", "Demo Owner 1", "SANDBOX"), UserInfo("u-1", "OWNER"))

        // A second object over the same store sees the same values (a restart of the app).
        val again = AccountSettings(storage)
        assertEquals("access-token-1", again.access)
        assertEquals("refresh-token-1", again.refresh)
        assertTrue(again.hasSession)
        assertEquals("t-1", again.tenantId)
        assertEquals("Demo Owner 1", again.tenantName)
        assertEquals("SANDBOX", again.tenantMode)
    }

    @Test
    fun theAccessTokenExpiresByTheServersLifetime() {
        val settings = AccountSettings(FakeKeyValueStorage())
        settings.saveSession(session, 1_000_000L)

        assertFalse(settings.accessExpired(1_000_000L + 800_000L))
        // 30 s margin: 870 s after the save the token counts as expired.
        assertTrue(settings.accessExpired(1_000_000L + 871_000L))
    }

    @Test
    fun theInstallIdIsStableAndShapedForTheServer() {
        val storage = FakeKeyValueStorage()
        val first = AccountSettings(storage).installId
        val second = AccountSettings(storage).installId

        assertEquals(first, second)
        assertTrue(Regex("^[A-Za-z0-9._:-]{8,64}$").matches(first))
    }

    @Test
    fun clearingTheSessionKeepsTheInstallIdAndTheLinkFlag() {
        val settings = AccountSettings(FakeKeyValueStorage())
        val id = settings.installId
        settings.saveSession(session, 0L)
        settings.markLinked()

        settings.clearSession()

        assertFalse(settings.hasSession)
        assertNull(settings.access)
        assertEquals(id, settings.installId)
        assertTrue(settings.linked)
    }

    @Test
    fun aPendingCodeSurvivesARestart() {
        val storage = FakeKeyValueStorage()
        val pending = PendingVerification("signup", "id-1", "+967700000001", "+967******200", "e", "r")
        AccountSettings(storage).pending = pending

        assertEquals(pending, AccountSettings(storage).pending)
    }

    @Test
    fun anIncompleteSessionIsRefused() {
        val settings = AccountSettings(FakeKeyValueStorage())
        try {
            settings.saveSession(AppSession(null, "r", 900, "s"), 0L)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(settings.hasSession)
        }
    }

    private class FakeBackend : AccountBackend {
        val seen = mutableListOf<Any>()

        override suspend fun signup(request: SignupRequest): SignupStarted {
            seen.add(request)
            return SignupStarted("signup-1", OtpInfo("SMS", "+967700000001", "+967******200", "e", "r"))
        }

        override suspend fun signupVerify(request: SignupVerifyRequest): VerifyResponse {
            seen.add(request)
            return VerifyResponse(
                TenantInfo("t-1", "Demo Owner 1", "SANDBOX"),
                UserInfo("u-1", "OWNER"),
                AppSession("access-token-1", "refresh-token-1", 900, "session-1"),
                Enrolment("https://x", "7wr3bd", null, null, "KEY", "AB12CD34", null),
                "home",
            )
        }

        override suspend fun signupResend(request: SignupResendRequest) = SignupStarted(null, null)
        override suspend fun login(request: LoginRequest): LoginStarted {
            seen.add(request)
            return LoginStarted("login-1", OtpInfo("SMS", "+967700000001", "+967******200", "e", "r"))
        }

        override suspend fun loginVerify(request: LoginVerifyRequest): VerifyResponse =
            signupVerify(SignupVerifyRequest("x", "y"))

        override suspend fun loginResend(request: LoginResendRequest) = LoginStarted(null, null)
        override suspend fun refresh(request: RefreshRequest): AppSession =
            AppSession("a2", "r2", 900, "s")

        override suspend fun mintEnrolment(access: String): Enrolment? = null
        override suspend fun completeEnrolment(access: String, request: EnrolmentCompleteRequest) = Unit
    }

    @Test
    fun theSignupPasswordIsSentOnceAndNeverStored() = runBlocking {
        val storage = FakeKeyValueStorage()
        val backend = FakeBackend()
        val repository = AccountRepository(
            backend, AccountSettings(storage), DeviceProfile("Pixel 7", "Android 14", "0.3.0")
        )

        repository.startSignup("Demo Owner 1", "+967700000200", password, "ar")
        assertFalse("after sign-up start", storage.dump().contains(password))

        val outcome = repository.verify("123456")
        assertTrue(outcome.signedIn)
        assertNotNull(outcome.enrolment)

        // The tokens are stored (so the test is not vacuous) but the password never.
        assertTrue(storage.dump().contains("access-token-1"))
        assertFalse("after verify", storage.dump().contains(password))
        // And the request that carried it was the only holder.
        assertEquals(1, backend.seen.count { it is SignupRequest })
        assertEquals(password, (backend.seen.first { it is SignupRequest } as SignupRequest).password)
    }

    @Test
    fun theLoginPasswordIsNeverStored() = runBlocking {
        val storage = FakeKeyValueStorage()
        val repository = AccountRepository(
            FakeBackend(), AccountSettings(storage), DeviceProfile("Pixel 7", "Android 14", "0.3.0")
        )

        repository.startLogin("+967700000200", password, "ar")
        assertFalse(storage.dump().contains(password))
        repository.verify("654321")
        assertFalse(storage.dump().contains(password))
        // Only the identifiers of the waiting code, the session and the install id are in the store.
        assertTrue(storage.dump().contains("refresh-token-1"))
    }

    @Test
    fun theStoredDocumentHasNoPasswordField() {
        val fields = AccountSettings.Stored::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("password") || it.contains("secret") })
    }
}
