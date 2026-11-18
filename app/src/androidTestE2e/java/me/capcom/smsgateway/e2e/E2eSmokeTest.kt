package me.capcom.smsgateway.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Draft-push subset: installed, the sign-up screen opens, the stub answers. */
@E2eSmoke
@E2eFull
@RunWith(AndroidJUnit4::class)
class E2eSmokeTest {

    @Test
    fun theStubIsReachableFromTheEmulator() {
        assertTrue("stub not reachable at 10.0.2.2:8787", StubControl.reachable())
    }

    @Test
    fun theE2eBuildIsInstalledUnderItsOwnPackage() {
        assertEquals(E2e.PKG, E2e.context.packageName)
    }

    @Test
    fun theWelcomeScreenOffersCreateAndTheCreateFlowOpens() {
        E2e.startAccountScreen()
        E2e.need("buttonCreateAccount")
        E2e.need("buttonSignIn")
        E2e.need("buttonCreateAccount").click()
        // Either the permission screen (fresh install) or, when already granted, the form.
        E2e.waitFor("the permission screen or the create form", E2e.SHORT) {
            E2e.visible("buttonAllow") || E2e.visible("editBusinessName")
        }
    }
}
