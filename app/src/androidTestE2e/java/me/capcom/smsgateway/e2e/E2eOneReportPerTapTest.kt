package me.capcom.smsgateway.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Strict form of the property: one tap sends ONE state report `Sent` for the message, not two.
 * CI evidence (run 44431177515): the phone sends one SMS (one message id, one send time) but posts
 * the identical final state twice, ~10 ms apart (`PATCH /message` twice). That is an app defect in
 * the state-report path, not a double send, and is tracked in e2e/quarantine.txt (V2-L47). The
 * assertion is kept unchanged here; only this class is quarantined, E2eGatewayTest still proves
 * one message id and one send.
 */
@E2eFull
@RunWith(AndroidJUnit4::class)
class E2eOneReportPerTapTest {

    private val settleMs = 15_000L

    @Test
    fun oneTapOnSendTestMessageYieldsExactlyOneSentReport() {
        E2e.ensureLinkedAndHome()
        val postsBefore = E2e.stub().getJSONArray("testMessagePosts").length()

        E2e.need("buttonHomeTest").click()
        E2e.device.wait(Until.findObject(By.res("android:id/button1").enabled(true)), E2e.SHORT)!!.click()

        E2e.waitFor("exactly one test-message POST") {
            E2e.stub().getJSONArray("testMessagePosts").length() == postsBefore + 1
        }
        val id = E2e.stub().getJSONArray("testMessagePosts").getJSONObject(postsBefore).getString("messageId")

        fun sentReports(): Int {
            val reports = E2e.stub().getJSONArray("messageReports")
            var n = 0
            for (i in 0 until reports.length()) {
                val r = reports.getJSONObject(i)
                if (r.optString("id") == id && r.optString("state") == "Sent") n++
            }
            return n
        }

        E2e.waitFor("the phone to report the message as Sent") { sentReports() >= 1 }
        SystemClock.sleep(settleMs) // negative-assertion window: a second report would arrive in it
        assertEquals("one Sent report for one tap", 1, sentReports())
    }
}
