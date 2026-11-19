package me.capcom.smsgateway.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The linked phone against the stub gateway: an inbound SMS (fired through the emulator console
 * by e2e/sms-watcher.sh), pause and resume (V2-L23), and one tap of "send test message" ->
 * exactly one SMS reported.
 */
@E2eFull
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class E2eGatewayTest {

    /** Idle window in which a duplicate send would have shown up; a negative assertion needs one. */
    private val settleMs = 15_000L

    private fun reportsFor(id: String, state: String): Int {
        val reports: JSONArray = E2e.stub().getJSONArray("messageReports")
        var n = 0
        for (i in 0 until reports.length()) {
            val r = reports.getJSONObject(i)
            if (r.optString("id") == id && r.optString("state") == state) n++
        }
        return n
    }

    @Test
    fun t01_anInboundSmsFromTheEmulatorConsoleIsReportedToTheStubInbox() {
        E2e.ensureLinkedAndHome()
        // the E2E passphrase comes from GET /settings; without it the app cannot encrypt the upload
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }
        val before = E2e.stub().getJSONArray("inbox").length()
        val text = "e2e in ${SystemClock.elapsedRealtime()}"

        StubControl.requestInboundSms("5551234", text)

        try {
            E2e.waitFor("the inbound SMS to be reported on /inbox") {
                E2e.stub().getJSONArray("inbox").length() > before
            }
        } catch (e: AssertionError) {
            E2e.dumpEvidence()
            throw e
        }
        val inbox = E2e.stub().getJSONArray("inbox")
        assertEquals("exactly one report for one SMS", before + 1, inbox.length())
        val item = inbox.getJSONObject(before)
        assertEquals("SMS", item.getString("type"))
        // With E2E encryption on, the content is ciphertext; in the clear it must be the text.
        if (!item.optBoolean("isEncrypted", true)) assertEquals(text, item.getString("content"))
        else assertTrue("content is passphrase-encrypted", item.getString("content").startsWith("\$aes-256-cbc/pbkdf2-sha1/v2\$"))
    }

    @Test
    fun t02_pauseStopsTheStreamAndResumeBringsItBack() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the SSE stream to be open") { E2e.stub().getInt("sseOpen") >= 1 }
        val pauses = E2e.stub().getInt("pauseCalls")
        val resumes = E2e.stub().getInt("resumeCalls")

        E2e.need("buttonHomePause").click()
        E2e.device.wait(
            androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android:id/button1")),
            E2e.SHORT
        )!!.click()
        E2e.waitFor("the platform to record the pause") {
            E2e.stub().getInt("pauseCalls") == pauses + 1 && E2e.stub().getBoolean("paused")
        }
        E2e.waitFor("a paused phone to hold no live stream") { E2e.stub().getInt("sseOpen") == 0 }

        E2e.need("buttonHomePause").click() // the same button now says resume
        E2e.waitFor("the platform to record the resume") {
            E2e.stub().getInt("resumeCalls") == resumes + 1 && !E2e.stub().getBoolean("paused")
        }
        E2e.waitFor("the stream to come back after resume") { E2e.stub().getInt("sseOpen") >= 1 }
    }

    @Test
    fun t03_oneTapOnSendTestMessageSendsExactlyOneSms() {
        E2e.ensureLinkedAndHome()
        val postsBefore = E2e.stub().getJSONArray("testMessagePosts").length()

        E2e.need("buttonHomeTest").click()
        // Dialog positive button ("Send"); one click only.
        E2e.device.wait(
            androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android:id/button1").enabled(true)),
            E2e.SHORT
        )!!.click()

        E2e.waitFor("exactly one test-message POST") {
            E2e.stub().getJSONArray("testMessagePosts").length() == postsBefore + 1
        }
        val id = E2e.stub().getJSONArray("testMessagePosts").getJSONObject(postsBefore).getString("messageId")

        E2e.waitFor("the phone to report the message as Sent") { reportsFor(id, "Sent") >= 1 }
        SystemClock.sleep(settleMs)
        assertEquals("one Sent report for one tap", 1, reportsFor(id, "Sent"))
        assertEquals(
            "still one test-message POST after the settle window",
            postsBefore + 1, E2e.stub().getJSONArray("testMessagePosts").length()
        )
        val handedOut = E2e.stub().getJSONArray("queue")
        var occurrences = 0
        for (i in 0 until handedOut.length()) if (handedOut.getJSONObject(i).getString("id") == id) occurrences++
        assertTrue("the queued message exists once", occurrences == 1)
    }
}
