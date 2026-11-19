package me.capcom.smsgateway.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.modules.retention.ActiveLineStore
import me.capcom.smsgateway.modules.retention.RetentionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * CARD-24 follow-up (owner decision 2026-11-19): a CUSTOMER phone FAILS CLOSED. With no line list
 * (never received, and the platform cannot give one) an SMS that arrives is neither stored nor
 * forwarded. The same phone then gets its list and a second SMS is reported: that positive control
 * proves the first one reached the app and was dropped by the filter, not lost on the way.
 * Counts only: no body, sender or number is read into an assertion message.
 */
@E2eFull
@RunWith(AndroidJUnit4::class)
class E2eFailClosedTest {

    /** Idle window in which a wrongly kept SMS would have been stored and forwarded. */
    private val settleMs = 20_000L

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun incomingRows(): Int =
        koin<AppDatabase>().openHelper.readableDatabase.query("SELECT COUNT(*) FROM incoming_messages").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun dropLogRows(): Int =
        koin<AppDatabase>().openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM logs_entries WHERE message LIKE '%dropped, the SIM slot holds no active line%'")
            .use {
                it.moveToFirst()
                it.getInt(0)
            }

    private fun uploadRows(): Int =
        koin<AppDatabase>().openHelper.readableDatabase.query("SELECT COUNT(*) FROM gateway__inbox_upload").use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun aCustomerPhoneWithNoLineListNeitherStoresNorForwardsAnSms() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }
        val lines = koin<ActiveLineStore>()

        try {
            // the list cannot be fetched, and the phone has never received one
            StubControl.setLinesUnavailable(true)
            lines.clear()
            assertNull("no line list was ever received", lines.slots())
            assertFalse("a customer phone is not the platform phone", lines.isPlatformPhone())

            val inboxBefore = E2e.stub().getJSONArray("inbox").length()
            val storedBefore = incomingRows()
            val uploadsBefore = uploadRows()

            val dropsBefore = dropLogRows()
            StubControl.requestInboundSms("5551234", "e2e personal sim ${SystemClock.elapsedRealtime()}")
            // the SMS must have reached the app and been dropped by the filter (one more drop
            // row, a count only), not lost on the way
            E2e.waitFor("the filter to drop the SMS") { dropLogRows() > dropsBefore }
            SystemClock.sleep(settleMs) // the idle window of a negative assertion

            assertEquals("nothing was forwarded", inboxBefore, E2e.stub().getJSONArray("inbox").length())
            assertEquals("nothing was stored", storedBefore, incomingRows())
            assertEquals("nothing was queued for upload", uploadsBefore, uploadRows())
            assertNull("the filter did not invent a list", lines.slots())

            // positive control: the list arrives, the next SMS is kept and reported once
            StubControl.seedLine(1)
            StubControl.setLinesUnavailable(false)
            runBlocking { koin<RetentionService>().refreshActiveLines() }
            E2e.waitFor("the line list to be received") { lines.slots()?.contains(1) == true }

            StubControl.requestInboundSms("5551234", "e2e with a list ${SystemClock.elapsedRealtime()}")
            try {
                E2e.waitFor("the SMS to be reported once the list is known") {
                    E2e.stub().getJSONArray("inbox").length() > inboxBefore
                }
            } catch (e: AssertionError) {
                E2e.dumpEvidence()
                throw e
            }
            assertEquals(
                "exactly the second SMS was reported (the first stayed dropped)",
                inboxBefore + 1,
                E2e.stub().getJSONArray("inbox").length()
            )
        } finally {
            // leave the stub and the phone as the other tests expect them
            StubControl.setLinesUnavailable(false)
            StubControl.seedLine(1)
            runBlocking { koin<RetentionService>().refreshActiveLines() }
        }
    }
}
