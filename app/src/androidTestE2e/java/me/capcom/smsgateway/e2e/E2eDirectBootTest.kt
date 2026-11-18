package me.capcom.smsgateway.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two instrumented halves of the Direct Boot job (CARD-21). The host script
 * (e2e/direct-boot.sh) runs [prepareLinkedPhone] before the PIN is set and the reboot, and
 * [afterUnlock] once the user is unlocked. Everything between (A2, A3, A4) is asserted by the
 * script from outside the app, because no instrumentation can run while credential-encrypted
 * storage is locked.
 */
@E2eDirectBoot
@RunWith(AndroidJUnit4::class)
class E2eDirectBootTest {

    @Test
    fun prepareLinkedPhone() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the phone to hold a live stream before the reboot") {
            E2e.stub().getInt("sseOpen") >= 1
        }
    }

    @Test
    fun afterUnlock() {
        // A8: the app is still signed in, WorkManager has its jobs, and nothing was sent twice.
        assertTrue("stub reachable", StubControl.reachable())
        E2e.waitFor("WorkManager/JobScheduler jobs of the app after the unlock") {
            E2e.device.executeShellCommand("dumpsys jobscheduler").contains(E2e.PKG)
        }
        val s = E2e.stub()
        assertFalse("the link survived the reboot", s.isNull("enrolmentComplete"))
        val reports = s.getJSONArray("messageReports")
        val sent = HashMap<String, Int>()
        for (i in 0 until reports.length()) {
            val r = reports.getJSONObject(i)
            if (r.optString("state") == "Sent") sent.merge(r.getString("id"), 1, Int::plus)
        }
        assertTrue("at least one message was sent while locked", sent.isNotEmpty())
        sent.forEach { (id, n) -> assertEquals("message $id reported Sent more than once", 1, n) }
    }
}
