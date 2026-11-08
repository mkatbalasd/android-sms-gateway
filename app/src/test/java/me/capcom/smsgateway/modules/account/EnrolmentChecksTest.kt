package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.gateway.GatewaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class EnrolmentChecksTest {

    private val fixed = GatewaySettings.PUBLIC_URL

    private val good = Enrolment(
        serverUrl = fixed,
        loginCode = "7wr3bd",
        codeExpiresAt = null,
        label = null,
        signingKey = "abcdEFGH0123456789abcdEFGH0123456789abcdEFG=",
        nonce = "AB12CD34",
        deviceId = null,
    )

    @Test
    fun aGoodEnrolmentPasses() {
        assertNull(EnrolmentChecks.problem(good, fixed))
    }

    @Test
    fun aMissingServerAddressIsAllowedBecauseTheServerIsFixed() {
        assertNull(EnrolmentChecks.problem(good.copy(serverUrl = null), fixed))
    }

    @Test
    fun anotherHostIsRefusedBeforeTheCodeIsSent() {
        assertEquals(
            EnrolmentChecks.SERVER_MISMATCH,
            EnrolmentChecks.problem(good.copy(serverUrl = "https://evil.example.com/api/mobile/v1"), fixed)
        )
        assertEquals(
            EnrolmentChecks.SERVER_MISMATCH,
            EnrolmentChecks.problem(good.copy(serverUrl = "http://smsgate.mnaseb.com/api/mobile/v1"), fixed)
        )
        assertEquals(
            EnrolmentChecks.SERVER_MISMATCH,
            EnrolmentChecks.problem(good.copy(serverUrl = "https://smsgate.mnaseb.com.evil.io/x"), fixed)
        )
        assertEquals(
            EnrolmentChecks.SERVER_MISMATCH,
            EnrolmentChecks.problem(good.copy(serverUrl = "not a url"), fixed)
        )
    }

    @Test
    fun theSameHostWithADifferentPathIsAccepted() {
        assertNull(EnrolmentChecks.problem(good.copy(serverUrl = "https://SMSGATE.mnaseb.com/api/mobile/v1/"), fixed))
    }

    @Test
    fun missingOrMalformedPartsAreInvalid() {
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(loginCode = null), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(loginCode = " "), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(nonce = null), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(nonce = "AB/CD34"), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(signingKey = null), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(signingKey = "short"), fixed))
        assertEquals(EnrolmentChecks.INVALID, EnrolmentChecks.problem(good.copy(signingKey = "a#b;c d".repeat(5)), fixed))
    }
}
