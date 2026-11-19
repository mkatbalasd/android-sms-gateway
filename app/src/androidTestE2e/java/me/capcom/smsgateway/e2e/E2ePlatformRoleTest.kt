package me.capcom.smsgateway.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.modules.retention.ActiveLineStore
import me.capcom.smsgateway.modules.retention.RetentionService
import me.capcom.smsgateway.modules.settings.SettingsService
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookDTO
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The platform-phone role comes ONLY from the server's webhook pull (a device-scoped marker hook).
 * Platform phone: a reply with no line list is kept and forwarded. Customer phone: the same
 * personal-SIM SMS is neither stored nor forwarded, also after the local server was asked (a local
 * POST /webhooks with the marker, a local PATCH /settings with an incoming section) to open the role.
 * Counts only: no body, sender or number is read into an assertion message.
 */
@E2eFull
@RunWith(AndroidJUnit4::class)
class E2ePlatformRoleTest {

    /** Idle window in which a wrongly kept SMS would have been stored and forwarded. */
    private val settleMs = 20_000L

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun scalar(sql: String): Int =
        koin<AppDatabase>().openHelper.readableDatabase.query(sql).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun incomingRows() = scalar("SELECT COUNT(*) FROM incoming_messages")

    private fun uploadRows() = scalar("SELECT COUNT(*) FROM gateway__inbox_upload")

    private fun dropLogRows() = scalar(
        "SELECT COUNT(*) FROM logs_entries WHERE message LIKE '%dropped, the SIM slot holds no active line%'"
    )

    private fun inboxCount() = E2e.stub().getJSONArray("inbox").length()

    private fun pull(expectSuccess: Boolean) = E2e.pullWebhooks(expectSuccess)

    private fun restore() {
        StubControl.setWebhooksUnavailable(false)
        StubControl.setPlatformRole(false)
        StubControl.setLinesUnavailable(false)
        StubControl.seedLine(1)
        pull(expectSuccess = true)
        runBlocking { koin<RetentionService>().refreshActiveLines() }
    }

    /** A personal-SIM SMS that must be dropped: the drop is logged, nothing is stored or forwarded. */
    private fun assertPersonalSmsDropped(lines: ActiveLineStore) {
        val inboxBefore = inboxCount()
        val storedBefore = incomingRows()
        val uploadsBefore = uploadRows()
        val dropsBefore = dropLogRows()

        StubControl.requestInboundSms("5551234", "e2e personal sim ${SystemClock.elapsedRealtime()}")
        E2e.waitFor("the filter to drop the SMS") { dropLogRows() > dropsBefore }
        SystemClock.sleep(settleMs) // the idle window of a negative assertion

        assertEquals("nothing was forwarded", inboxBefore, inboxCount())
        assertEquals("nothing was stored", storedBefore, incomingRows())
        assertEquals("nothing was queued for upload", uploadsBefore, uploadRows())
        assertNull("the filter did not invent a list", lines.slots())
    }

    @Test
    fun aPlatformPhoneWithNoLineListKeepsAndForwardsAReply() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }
        val lines = koin<ActiveLineStore>()

        try {
            StubControl.setLinesUnavailable(true)
            lines.clear()
            assertNull("no line list was ever received", lines.slots())
            assertFalse("closed before the pull", lines.isPlatformPhone())

            // the server states the role: a marker webhook for this device
            StubControl.setPlatformRole(true)
            pull(expectSuccess = true)
            assertTrue("the marker opened the role", lines.isPlatformPhone())
            assertNull("the role did not invent a list", lines.slots())

            val inboxBefore = inboxCount()
            StubControl.requestInboundSms("5551234", "e2e platform reply ${SystemClock.elapsedRealtime()}")
            try {
                E2e.waitFor("the reply to be forwarded with no line list") { inboxCount() > inboxBefore }
            } catch (e: AssertionError) {
                E2e.dumpEvidence()
                throw e
            }
            assertEquals("exactly the reply was forwarded", inboxBefore + 1, inboxCount())
            assertNull("the reply did not create a list", lines.slots())

            // a failed pull keeps the role
            StubControl.setWebhooksUnavailable(true)
            pull(expectSuccess = false)
            assertTrue("a failed pull keeps an open role", lines.isPlatformPhone())
            StubControl.setWebhooksUnavailable(false)

            // a pull that succeeds without the marker closes it
            StubControl.setPlatformRole(false)
            pull(expectSuccess = true)
            assertFalse("a pull without the marker closed the role", lines.isPlatformPhone())
        } finally {
            restore()
        }
    }

    @Test
    fun aCustomerPhoneWhoseLineListIsUnavailableDropsAPersonalSimSms() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }
        val lines = koin<ActiveLineStore>()

        try {
            StubControl.setPlatformRole(false)
            StubControl.setLinesUnavailable(true)
            lines.clear()
            pull(expectSuccess = true)
            assertFalse("no marker, no role", lines.isPlatformPhone())
            assertNull("no line list was ever received", lines.slots())

            assertPersonalSmsDropped(lines)
        } finally {
            restore()
        }
    }

    @Test
    fun aLocalWriteAskingForTheRoleLeavesTheCustomerPhoneClosed() {
        E2e.ensureLinkedAndHome()
        E2e.waitFor("the app to read /settings") { E2e.stub().getInt("settingsServed") >= 1 }
        val lines = koin<ActiveLineStore>()
        val hooks = koin<WebHooksService>()
        var localId: String? = null

        try {
            StubControl.setPlatformRole(false)
            StubControl.setLinesUnavailable(true)
            lines.clear()
            pull(expectSuccess = true)
            assertFalse(lines.isPlatformPhone())

            // what a local POST /webhooks does: the marker as a Local hook
            val created = hooks.replace(
                EntitySource.Local,
                WebHookDTO(
                    id = null,
                    deviceId = null,
                    url = "http://127.0.0.1:1/e2e-local-hook?mnaseb_role=platform_phone",
                    event = WebHookEvent.SystemPing,
                    source = EntitySource.Local,
                )
            )
            localId = created.id
            assertFalse("a Local marker hook did not open the role", lines.isPlatformPhone())

            // what a local PATCH /settings does: an incoming section naming the role
            koin<SettingsService>().update(mapOf("incoming" to mapOf("platform_phone" to true)))
            assertFalse("a settings write did not open the role", lines.isPlatformPhone())

            assertPersonalSmsDropped(lines)
            assertFalse("still closed after the SMS", lines.isPlatformPhone())
        } finally {
            localId?.let { hooks.delete(EntitySource.Local, it) }
            restore()
        }
    }
}
