package me.capcom.smsgateway.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.capcom.smsgateway.ui.account.AccountPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Sign-up through the real screens against the stub: the permission screen, the international
 * phone field, the code, the self-enrolment and the link. CARD-18 items 1-2 in simulation.
 */
@E2eFull
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class E2eSignUpTest {

    @Test
    fun t01_permissionScreenShowsTheRealStateAndTheDialogsGrantIt() {
        E2e.startAccountScreen()
        E2e.need("buttonCreateAccount").click()
        if (E2e.find("buttonAllow", 5_000) != null) {
            // The screen is only shown while something is really missing.
            assertFalse(
                "permission screen shown although nothing is missing",
                AccountPermissions.missing(E2e.context).isEmpty()
            )
            E2e.need("buttonAllow").click()
        }
        E2e.grantDialogsUntil("editBusinessName")
        assertTrue(
            "after the dialogs these are still missing: ${AccountPermissions.missing(E2e.context)}",
            AccountPermissions.missing(E2e.context).isEmpty()
        )
    }

    @Test
    fun t02_theNationalNumberIsNeverPrefilledFromTheSim() {
        E2e.startSignUpAndGrant()
        // Whatever country the SIM preselects, the number field must not carry a number. An
        // empty EditText reports its hint ("Mobile number") as its text to UI Automator, so the
        // assertion is that no digit of any script is present, which is what a SIM prefill would be.
        val national = E2e.need("editIntlNational").text.orEmpty()
        assertFalse("the national number field was prefilled", national.any { it.isDigit() })
        val name = E2e.need("editBusinessName").text.orEmpty()
        assertFalse("the business name field was prefilled with digits", name.any { it.isDigit() })
    }

    @Test
    fun t03_signUpVerifiesTheCodeAndLinksThePhone() {
        E2e.signUpAndLink()
        val s = E2e.stub()
        assertEquals("one sign-up", 1, s.getJSONArray("signups").length())
        val signup = s.getJSONArray("signups").getJSONObject(0)
        assertEquals("MOBILE", signup.getJSONObject("contact").getString("type"))
        assertTrue(
            "the typed digits reached the platform in E.164",
            signup.getJSONObject("contact").getString("value").startsWith("+1212")
        )
        val device = s.getJSONObject("device")
        assertTrue("registered with the enrolment nonce as the name suffix", device.getBoolean("nameEndsWithNonce"))
        assertTrue("a signed E2E public key was registered", device.getBoolean("hasPublicKey"))
        assertFalse("enrolment completed", s.isNull("enrolmentComplete"))
    }

    @Test
    fun t04_theLinkedPhoneShowsTheAccountHomeAndHoldsAnEventStream() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the phone's SSE stream to be open on the stub") {
            E2e.stub().getInt("sseOpen") >= 1
        }
        E2e.waitFor("a heartbeat (authenticated pull) on the stub") {
            E2e.stub().getJSONArray("heartbeats").length() >= 1
        }
    }
}
