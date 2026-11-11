package me.capcom.smsgateway.receivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CARD-21 (rev-fork17-3): the restart after the deferred storage copy is wired, and the
 * relaunch path cannot be triggered by another app. These read the sources, in the style of
 * ManifestDirectBootTest, because the wiring lives in Android entry points the JVM cannot run.
 */
class RelaunchWiringTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun src(rel: String): File =
        listOf(File("src/main/$rel"), File("app/src/main/$rel")).first { it.exists() }

    private fun receivers(): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(src("AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("receiver")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun receiver(name: String) =
        receivers().first { it.getAttributeNS(androidNs, "name") == name }

    @Test
    fun theRelaunchReceiverIsNotExportedAndHasNoIntentFilter() {
        val r = receiver(".receivers.RelaunchReceiver")
        assertEquals("false", r.getAttributeNS(androidNs, "exported"))
        assertEquals(0, r.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun theExportedBootReceiverNeitherListensToNorActsOnTheRelaunchAction() {
        val boot = receiver(".receivers.BootReceiver")
        val actions = boot.getElementsByTagName("action")
        val names = (0 until actions.length).map {
            (actions.item(it) as Element).getAttributeNS(androidNs, "name")
        }
        assertFalse(names.contains(BootPolicy.RELAUNCH))

        val code = src("java/me/capcom/smsgateway/receivers/BootReceiver.kt").readText()
        assertTrue(code.contains("BootPolicy.RELAUNCH) return"))
    }

    @Test
    fun theAlarmTargetsTheRelaunchReceiverWithTheAppPrivateAction() {
        val code = src("java/me/capcom/smsgateway/helpers/DeviceStorage.kt").readText()
        assertTrue(code.contains("Intent(BootPolicy.RELAUNCH).setClass(app, RelaunchReceiver::class.java)"))
        assertFalse(code.contains("Intent(Intent.ACTION_BOOT_COMPLETED)"))
    }

    @Test
    fun appOnCreateRestartsBeforeKoinStartsWhenTheCopyWasSkippedAndTheUserIsUnlocked() {
        val code = src("java/me/capcom/smsgateway/App.kt").readText()
        val restart = code.indexOf("MigrationFollowUp.Action.RESTART_NOW")
        val exit = code.indexOf("DeviceStorage.exitAndRelaunch(this)")
        val koin = code.indexOf("startKoin {")

        assertTrue("RESTART_NOW check missing in App", restart >= 0)
        assertTrue("exitAndRelaunch call missing in App", exit >= 0)
        assertTrue("the restart must come before startKoin", restart < koin && exit < koin)
    }
}
