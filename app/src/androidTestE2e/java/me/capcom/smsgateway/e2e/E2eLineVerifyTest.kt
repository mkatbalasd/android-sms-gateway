package me.capcom.smsgateway.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.capcom.smsgateway.ui.account.NumbersActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "verify my SIM number" screen: tick the emulator's SIM, tap verify, and the app asks the
 * platform (stub) for the MO proof and then reads the line back. The MO SMS itself goes to the
 * emulator modem; the stub cannot receive it, so it activates the line on the verify call.
 * Own class so it can be quarantined without losing the rest of the flow.
 */
@E2eFull
@RunWith(AndroidJUnit4::class)
class E2eLineVerifyTest {

    @Test
    fun tickingTheSimAndTappingVerifyStartsTheMoProofOnTheStub() {
        E2e.ensureLinkedAndHome()
        E2e.context.startActivity(
            Intent(E2e.context, NumbersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val tick = E2e.need("checkSlot", E2e.LONG)
        if (!tick.isChecked) tick.click()
        E2e.need("buttonNumbersVerify").click()
        // SEND_SMS is already granted by the sign-up test; if not, the system dialog is answered.
        E2e.device.wait(
            androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.res(
                    java.util.regex.Pattern.compile(".*:id/permission_allow_button")
                )
            ),
            3_000
        )?.click()
        E2e.waitFor("the platform to receive POST /app/lines/verify") {
            E2e.stub().getJSONArray("lines").length() == 1
        }
        assertEquals("one POST /app/lines/verify reached the stub for one tap (the stub activates the line itself; no MO SMS is matched)", 1, E2e.stub().getJSONArray("lines").length())
    }
}
