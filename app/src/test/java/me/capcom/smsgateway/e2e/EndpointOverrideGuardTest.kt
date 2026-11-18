package me.capcom.smsgateway.e2e

import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.modules.account.AccountApi
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.LinkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2 guard: the emulator-e2e endpoint override exists only in the `emulatorE2e` build type.
 * This suite runs for every other variant (debug, debugInsecure, insecure and, with
 * `./gradlew test`, release); the emulatorE2e variant has its unit tests disabled in
 * app/build.gradle, so a test seeing the override means the source-set wiring broke.
 */
class EndpointOverrideGuardTest {

    @Test
    fun theseUnitTestsNeverRunInTheE2eBuildType() {
        assertNotEquals("emulatorE2e", BuildConfig.BUILD_TYPE)
    }

    @Test
    fun noOverrideExistsOutsideTheE2eBuildType() {
        assertNull(E2eOverride.stubBase)
        assertFalse(E2eOverride.isStubServer("http://10.0.2.2:8787/api/mobile/v1"))
        assertFalse(E2eOverride.isStubServer(GatewaySettings.PUBLIC_URL))
    }

    @Test
    fun theProductionUrlsAreStillTheProductionUrls() {
        assertEquals("https://api.mnaseb.com/v1", AccountApi.DEFAULT_BASE_URL)
        assertEquals("https://smsgate.mnaseb.com/api/mobile/v1", GatewaySettings.PUBLIC_URL)
        assertEquals(AccountApi.DEFAULT_BASE_URL, Endpoints.accountBase)
        assertEquals(GatewaySettings.PUBLIC_URL, Endpoints.gatewayBase)
    }

    @Test
    fun aLinkPayloadNamingTheStubServerIsRefused() {
        val stub = "http%3A%2F%2F10.0.2.2%3A8787%2Fapi%2Fmobile%2Fv1"
        val result = LinkPayload.parse(
            "mnaseb-sim://link?v=1&s=$stub&c=ABCDEF", GatewaySettings.PUBLIC_URL, 1L
        )
        assertTrue(result is LinkPayload.Result.Failed)
        assertEquals(
            LinkPayload.Error.ForbiddenServer,
            (result as LinkPayload.Result.Failed).error
        )
    }
}
