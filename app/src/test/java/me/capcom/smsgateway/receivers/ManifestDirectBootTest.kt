package me.capcom.smsgateway.receivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CARD-21: the set of direct-boot aware components is exactly the three the locked
 * path needs. Anything else (above all WorkManager's services and the activities)
 * must stay credential-encrypted.
 */
class ManifestDirectBootTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): File =
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }

    private fun components(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(manifest())
        val result = mutableListOf<Element>()
        for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                result += nodes.item(i) as Element
            }
        }
        return result
    }

    private fun Element.componentName(): String = getAttributeNS(androidNs, "name")

    @Test
    fun directBootAwareIsSetOnExactlyTheThreeComponents() {
        val aware = components()
            .filter { it.getAttributeNS(androidNs, "directBootAware") == "true" }
            .map { it.componentName() }
            .toSet()

        assertEquals(
            setOf(
                ".receivers.BootReceiver",
                ".modules.gateway.services.SSEForegroundService",
                ".receivers.EventsReceiver",
            ),
            aware
        )
    }

    @Test
    fun workManagerAndActivitiesAreNeverDirectBootAware() {
        for (c in components()) {
            val name = c.componentName()
            val flag = c.getAttributeNS(androidNs, "directBootAware")
            if (name.startsWith("androidx.work") || c.tagName.startsWith("activity")) {
                assertTrue("$name must not be direct boot aware", flag != "true")
            }
        }
    }

    @Test
    fun bootReceiverListensToLockedBootCompleted() {
        val boot = components().first { it.componentName() == ".receivers.BootReceiver" }
        val actions = boot.getElementsByTagName("action")
        val names = (0 until actions.length).map {
            (actions.item(it) as Element).getAttributeNS(androidNs, "name")
        }
        assertTrue(names.contains("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertTrue(names.contains("android.intent.action.BOOT_COMPLETED"))
    }
}
