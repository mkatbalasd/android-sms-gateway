package me.capcom.smsgateway.modules.account

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.ui.account.HomeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A linked phone whose app session the platform ended (a password reset) signs in again:
 * the link stays, only the owning account may come back, no enrolment is asked for.
 */
internal class LinkedSignInAgainTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val paths = mutableListOf<String>()

    private fun repo(
        settings: AccountSettings,
        handler: (String) -> Pair<HttpStatusCode, String>,
    ): AccountRepository {
        val engine = MockEngine { request ->
            paths.add(request.url.encodedPath)
            val (status, body) = handler(request.url.encodedPath)
            respond(content = body, status = status, headers = json)
        }
        return AccountRepository(
            AccountApi(engine = engine), settings, DeviceProfile("Pixel 7", "Android 14", "0.3.0"),
            now = { 1_000_000L },
        )
    }

    /** A phone linked to tenant "t" whose session was revoked: tokens gone, link kept. */
    private fun revokedLinkedSettings(): AccountSettings {
        val settings = AccountSettings(FakeKeyValueStorage())
        settings.saveSession(AppSession("a", "r", 900, "s"), 1_000_000L)
        settings.saveIdentity(TenantInfo("t", "Demo", "SANDBOX"), UserInfo("u", "OWNER"))
        settings.markLinked()
        settings.clearSession()
        settings.pending = PendingVerification(
            PendingVerification.KIND_LOGIN, "L1", null, null, null, null
        )
        return settings
    }

    private fun verifyAnswer(tenantId: String) =
        HttpStatusCode.Created to
            """{"app_session":{"access":"new-a","refresh":"new-r","expires_in":900,"session_id":"s2"},
                "tenant":{"id":"$tenantId","name":"Demo","mode":"SANDBOX"},"user":{"id":"u"}}"""

    @Test
    fun aRevokedRefreshKeepsTheLinkAndOffersSignIn() = runBlocking {
        val settings = AccountSettings(FakeKeyValueStorage())
        settings.saveSession(AppSession("old", "old-r", 900, "s"), 0L)
        settings.saveIdentity(TenantInfo("t", "Demo", "SANDBOX"), null)
        settings.markLinked()
        val install = settings.installId
        val repo = repo(settings) {
            HttpStatusCode.Unauthorized to
                """{"success":false,"error":{"code":"APP_SESSION_REVOKED","message":"x"}}"""
        }

        try {
            repo.withSession { error("not reached") }
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals(401, e.error.httpStatus)
        }

        // The tokens are gone; the link, the tenant and the install id are not.
        assertFalse(settings.hasSession)
        assertTrue(settings.linked)
        assertEquals("t", settings.tenantId)
        assertEquals(install, settings.installId)
        assertTrue(HomeStatus.sessionNeedsSignIn(settings.linked, settings.hasSession, null))
    }

    @Test
    fun theOwningAccountSignsInAgainAndTheLinkStays() = runBlocking {
        val settings = revokedLinkedSettings()
        val repo = repo(settings) { verifyAnswer("t") }

        val outcome = repo.verify("123456", expectTenantId = settings.tenantId)

        assertTrue(outcome.signedIn)
        assertEquals("new-a", settings.access)
        assertEquals("new-r", settings.refresh)
        assertTrue(settings.linked)
        assertEquals("t", settings.tenantId)
        assertNull(settings.pending)
        // Only the verify call: no enrolment, no gateway registration.
        assertEquals(listOf("/v1/app/login/verify"), paths)
    }

    @Test
    fun anotherAccountIsRefusedAndNothingIsStored() = runBlocking {
        val settings = revokedLinkedSettings()
        val repo = repo(settings) { verifyAnswer("other-tenant") }

        try {
            repo.verify("123456", expectTenantId = settings.tenantId)
            fail("expected a refusal")
        } catch (e: AccountException) {
            assertEquals(AccountRepository.ACCOUNT_MISMATCH, e.error.code)
        }

        assertFalse(settings.hasSession)
        assertNull(settings.access)
        assertTrue(settings.linked)
        // The linked phone keeps its own account identity.
        assertEquals("t", settings.tenantId)
        assertEquals("Demo", settings.tenantName)
        assertNull(settings.pending)
    }

    @Test
    fun aVerifyWithoutAnExpectedTenantBehavesAsBefore() = runBlocking {
        val settings = AccountSettings(FakeKeyValueStorage())
        settings.pending = PendingVerification(
            PendingVerification.KIND_LOGIN, "L1", null, null, null, null
        )
        val repo = repo(settings) { verifyAnswer("any") }

        assertTrue(repo.verify("123456").signedIn)
        assertEquals("any", settings.tenantId)
    }
}
