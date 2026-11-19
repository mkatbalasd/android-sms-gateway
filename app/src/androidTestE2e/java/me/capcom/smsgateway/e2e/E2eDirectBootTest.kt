package me.capcom.smsgateway.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.data.dao.MessagesDao
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.helpers.RollbackCopies
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.modules.messages.exceptions.ConflictException
import me.capcom.smsgateway.modules.retention.ActiveLineStore
import me.capcom.smsgateway.modules.retention.RetentionPolicy
import me.capcom.smsgateway.modules.retention.RetentionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.koin.core.context.GlobalContext
import java.util.Date
import me.capcom.smsgateway.modules.messages.data.Message as SendMessage
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
        // the E2E passphrase (from GET /settings) must be stored before the reboot: it is what
        // lets a text received after unlock be encrypted and uploaded
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }

        // CARD-24: only an SMS on a slot holding an active line is kept and forwarded, so the
        // phone must know the slots before the reboot (the host script seeded slot 1 on the stub)
        val lines = koin<ActiveLineStore>()
        runBlocking { koin<RetentionService>().refreshActiveLines() }
        E2e.waitFor("the active line slots to be known") { lines.slots()?.contains(1) == true }
    }

    /**
     * CARD-24, after the unlock and before the time shift: the text of the sent message is
     * already gone (deleted at the final state), the recipient number is still inside its
     * window, and the bare id is there. The host script greps the storage as root next, so the
     * recipient number must be visible to it here (the positive control of the later check).
     */
    @Test
    fun retentionBeforeShift() {
        val id = firstSentId()
        val row = koin<MessagesDao>().get(id)
        assertNotNull("the message row (the repeat guard) exists", row)
        assertEquals("the text was deleted at the final state", "", row!!.message.textContent?.text)
        assertTrue("the recipient is still inside its window", row.recipients.isNotEmpty())
        assertEquals("the inbound SMS reached only a known line", false, activeLineSlots().isNullOrEmpty())

        // a fresh install has no CARD-21 rollback copies, so plant the ones an upgraded phone
        // has (credential-encrypted storage, the old file names); the next step must remove them
        rollbackCopies().forEach {
            it.parentFile!!.mkdirs()
            it.writeText("e2e-rollback-copy")
            assertTrue("planted ${it.name}", it.exists())
        }
    }

    private fun rollbackCopies(): List<java.io.File> = RollbackCopies.candidates(
        E2e.context.dataDir,
        E2e.context.getDatabasePath(DeviceStorage.DATABASE_NAME).parentFile!!,
        "${E2e.PKG}_preferences",
        DeviceStorage.DATABASE_NAME,
    ).filter { it.name == DeviceStorage.DATABASE_NAME || it.name.endsWith(".xml") }

    /**
     * CARD-24, the time-shift step (emulatorE2e only; the hook is absent from release builds, the
     * release guard proves it): the retention clock moves 4 days forward, the clean-up runs
     * through the real service, and then: the recipient is gone, the incoming text and parties
     * are gone, the CE rollback copies are gone, the bare id stays and a repeat of the id is
     * refused. Counts and booleans only: no number and no text is read into an assertion message.
     */
    @Test
    fun retentionAfterShift() {
        val id = firstSentId()
        val retention = koin<RetentionService>()
        val summary = try {
            E2eOverride.retentionShiftMs = 4 * RetentionPolicy.DAY_MS
            retention.runCleanup()
        } finally {
            E2eOverride.retentionShiftMs = 0L
        }
        assertEquals("no clean-up step failed", 0, summary.failedSteps)

        assertTrue("the wal was truncated", summary.walTruncated)
        assertTrue("the rollback copies were removed", rollbackCopies().none { it.exists() })

        val row = koin<MessagesDao>().get(id)
        assertNotNull("the bare id is kept (tombstone)", row)
        assertTrue("the recipient number was deleted", row!!.recipients.isEmpty())
        assertEquals("the text is still gone", "", row.message.textContent?.text)

        // a repeat of the same id is refused, not sent again
        val messages = koin<MessagesService>()
        try {
            messages.enqueueMessage(
                SendRequest(
                    EntitySource.Cloud,
                    SendMessage(id, MessageContent.Text("e2e repeat"), listOf("+15550000001"), false, Date()),
                    SendParams(true, false, null, null, null, null),
                )
            )
            fail("the repeated id was accepted")
        } catch (e: ConflictException) {
            // refused: the id row is still there
        }
        assertNotNull(koin<MessagesDao>().get(id))
        assertTrue("the repeat added no recipient", koin<MessagesDao>().get(id)!!.recipients.isEmpty())

        // incoming: no body and no sender is left in the table
        val db = koin<AppDatabase>().openHelper.readableDatabase
        db.query("SELECT COUNT(*) FROM incoming_messages WHERE contentPreview <> '' OR sender <> ''").use {
            it.moveToFirst()
            assertEquals("no incoming body or sender is left", 0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM gateway__inbox_upload").use {
            it.moveToFirst()
            assertEquals("the inbox upload queue is empty (acknowledged)", 0, it.getInt(0))
        }
    }

    private fun firstSentId(): String {
        val reports = E2e.stub().getJSONArray("messageReports")
        for (i in 0 until reports.length()) {
            val r = reports.getJSONObject(i)
            if (r.optString("state") == "Sent") return r.getString("id")
        }
        fail("no message was reported Sent")
        throw IllegalStateException()
    }

    private fun activeLineSlots(): Set<Int>? = koin<ActiveLineStore>().slots()

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

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
