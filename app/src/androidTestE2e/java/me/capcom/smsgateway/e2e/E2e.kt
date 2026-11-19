package me.capcom.smsgateway.e2e

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import me.capcom.smsgateway.ui.account.AccountActivity
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.logs.LogsService
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import java.util.regex.Pattern

/**
 * UI Automator helpers for the emulator e2e suite. Nothing here prints an EditText value, a
 * code, a token or a phone number: assertions use presence, emptiness and counts only.
 * Every wait is an explicit deadline on a real condition (a view, or the stub's recorded
 * state); there is no sleep that stands in for an assertion.
 */
object E2e {
    const val PKG = "com.mnaseb.sim.e2e"
    const val TEST_PASSWORD = "E2e-Test-Pass-1"
    const val TEST_OTP = "123456"
    const val SHORT = 10_000L
    const val LONG = 90_000L

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    fun res(id: String) = By.res(PKG, id)

    fun find(id: String, timeoutMs: Long = SHORT): UiObject2? =
        device.wait(Until.findObject(res(id)), timeoutMs)

    fun need(id: String, timeoutMs: Long = SHORT): UiObject2 {
        val o = find(id, timeoutMs)
        assertNotNull("view $id not shown within ${timeoutMs}ms", o)
        return o!!
    }

    fun visible(id: String): Boolean = device.hasObject(res(id))

    /** Polls [cond] until it holds or [timeoutMs] passes; the failure message names [what]. */
    fun waitFor(what: String, timeoutMs: Long = LONG, cond: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (try { cond() } catch (e: Exception) { false }) return
            device.waitForIdle(500)
            SystemClock.sleep(500) // polling interval of a deadline-bounded wait, not an assertion
        }
        fail("timed out after ${timeoutMs}ms waiting for: $what")
    }

    fun stub(): JSONObject = StubControl.state()

    fun startAccountScreen() {
        device.pressHome()
        val intent = Intent(context, AccountActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), SHORT)
    }

    fun startLauncher() {
        device.pressHome()
        val intent = context.packageManager.getLaunchIntentForPackage(PKG)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(PKG).depth(0)), SHORT)
    }

    private val allowButton: Pattern = Pattern.compile(
        "com\\.android\\.(permissioncontroller|packageinstaller):id/" +
            "permission_allow(_foreground_only|_one_time)?_button"
    )

    /**
     * Presses "Allow" on every system permission dialog until [done] shows. The dialogs differ
     * by API level (grouped SMS dialog, phone, notifications on 33+), so this loops.
     */
    fun grantDialogsUntil(done: String, timeoutMs: Long = LONG) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (visible(done)) return
            val allow = device.wait(Until.findObject(By.res(allowButton)), 2_000)
            allow?.click()
            device.waitForIdle(500)
        }
        fail("permission dialogs did not lead to $done within ${timeoutMs}ms")
    }

    /** Opens the welcome screen, taps create, allows the permissions, reaches the create form. */
    fun startSignUpAndGrant() {
        startAccountScreen()
        need("buttonCreateAccount").click()
        // PermissionsFragment shows only while something is missing; a granted phone skips it.
        if (find("buttonAllow", 4_000) != null) {
            need("buttonAllow").click()
        }
        grantDialogsUntil("editBusinessName")
    }

    fun fillCreateForm(nationalDigits: String) {
        need("editBusinessName").text = "E2E Business"
        selectDialCodeIfPlaceholder()
        need("editIntlNational").text = nationalDigits
        need("editPassword").text = TEST_PASSWORD
        need("checkTerms").click()
    }

    /** The SIM country preselects the dial code; without one, pick any +1 entry. */
    private fun selectDialCodeIfPlaceholder() {
        val spinner = need("spinnerIntlCountry")
        if (spinner.hasObject(By.textContains("+"))) return
        spinner.click()
        val entry = device.wait(Until.findObject(By.textContains("+1)")), SHORT)
        assertNotNull("no +1 country entry in the dial code list", entry)
        entry!!.click()
    }

    /** Sign-up through to a linked phone; asserts the stub saw the enrolment complete. */
    fun signUpAndLink() {
        startSignUpAndGrant()
        fillCreateForm("2125550123")
        need("buttonCreateSubmit").click()
        need("editCode", LONG).text = TEST_OTP
        need("buttonCodeConfirm").click()
        waitFor("the stub to record the enrolment as complete") {
            !stub().isNull("enrolmentComplete")
        }
    }

    fun isLinkedOnStub(): Boolean = try {
        StubControl.reachable() && !stub().isNull("enrolmentComplete")
    } catch (e: Exception) {
        false
    }

    /** Idempotent: links through the UI once, then lands on the account home screen. */
    fun ensureLinkedAndHome() {
        if (!isLinkedOnStub()) signUpAndLink()
        startLauncher()
        need("buttonHomePause", LONG)
    }

    /** Device-local logcat timestamp ("MM-dd HH:mm:ss.SSS"), the format `logcat` prints by default. */
    fun logcatNow(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    /**
     * Ground truth OUTSIDE the app's own state tables: how many SMS the telephony stack (the
     * phone process, not this app) took for submission since [since] (a [logcatNow] value).
     * The platform's SmsNumberUtils logs one `enter filterDestAddr` line per outgoing SMS with the
     * destination as a hash; the result maps each destination hash to its submission count.
     * Other senders on the emulator (the dialer's voicemail probe) show up as their own hash.
     * Needs the shell user to read logcat, which UiDevice.executeShellCommand is.
     */
    /** The hash the telephony log prints for a destination: URL-safe base64 of SHA-1, no padding. */
    fun telephonyDestHash(number: String): String = android.util.Base64.encodeToString(
        java.security.MessageDigest.getInstance("SHA-1").digest(number.toByteArray()),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )

    fun radioSubmissionsSince(since: String): Map<String, Int> {
        val out = device.executeShellCommand("logcat -b all -d -s SmsNumberUtils")
        val counts = HashMap<String, Int>()
        val dest = Regex("enter filterDestAddr\\. destAddr=\"\\[([^\\]]*)\\]\"")
        for (line in out.lineSequence()) {
            if (line.length < 18 || line.substring(0, 18) < since) continue
            dest.find(line)?.let { counts.merge(it.groupValues[1], 1, Int::plus) }
        }
        return counts
    }

    /**
     * Failure evidence for a CI run: the app's own WARN/ERROR log rows (module and message, digit
     * runs masked) and the tail of the stub's request log, written to logcat under E2E-APPLOG and
     * E2E-STUB. Never prints a message body, a number, a code or a token.
     */
    fun dumpEvidence() {
        val digits = Regex("[0-9\u0660-\u0669]+")
        try {
            val logs = GlobalContext.get().get<LogsService>()
            runBlocking { logs.select() }
                .filter { it.priority.ordinal >= me.capcom.smsgateway.modules.logs.db.LogEntry.Priority.WARN.ordinal }
                .takeLast(25)
                .forEach { Log.e("E2E-APPLOG", digits.replace("${it.priority} ${it.module} ${it.message}", "#")) }
        } catch (e: Throwable) {
            Log.e("E2E-APPLOG", "could not read app logs: ${e.javaClass.simpleName}")
        }
        try {
            Log.e("E2E-STUB", digits.replace(stub().getJSONArray("requestsTail").toString(), "#"))
        } catch (e: Throwable) {
            Log.e("E2E-STUB", "could not read stub state: ${e.javaClass.simpleName}")
        }
    }
}
