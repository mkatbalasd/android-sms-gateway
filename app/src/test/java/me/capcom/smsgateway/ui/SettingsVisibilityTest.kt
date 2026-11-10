package me.capcom.smsgateway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class SettingsVisibilityTest {

    private fun rootPreferences(): String {
        // Gradle runs unit tests with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/res/xml/root_preferences.xml"),
            File("app/src/main/res/xml/root_preferences.xml"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun anAppEnrolledInstallHidesTheUpstreamSettings() {
        val hidden = SettingsVisibility.hiddenKeys(appEnrolled = true)
        assertEquals(
            setOf(
                SettingsVisibility.DEVICE,
                SettingsVisibility.CLOUD_SERVER,
                SettingsVisibility.MESSAGES,
                SettingsVisibility.INBOX,
                SettingsVisibility.WEBHOOKS,
                SettingsVisibility.ENCRYPTION,
                SettingsVisibility.PING,
                SettingsVisibility.LOGS,
            ),
            hidden.toSet()
        )
        assertEquals(listOf(SettingsVisibility.UNLINK_CATEGORY), SettingsVisibility.shownKeys(true))
    }

    @Test
    fun theQrPathInstallKeepsEveryScreenAndShowsNoUnlink() {
        assertTrue(SettingsVisibility.hiddenKeys(appEnrolled = false).isEmpty())
        assertTrue(SettingsVisibility.shownKeys(appEnrolled = false).isEmpty())
    }

    @Test
    fun languageAndBatteryStayVisibleForEveryone() {
        for (enrolled in listOf(true, false)) {
            val hidden = SettingsVisibility.hiddenKeys(enrolled)
            SettingsVisibility.KEPT_KEYS.forEach { assertFalse(it in hidden) }
        }
    }

    @Test
    fun everyKeyIsInTheXmlSoNothingIsHiddenByAMissingKey() {
        val xml = rootPreferences()
        SettingsVisibility.allKeys().forEach { key ->
            assertTrue("missing key $key", xml.contains("key=\"$key\""))
        }
    }

    @Test
    fun theUnlinkEntryIsHiddenInTheXmlUntilTheCodeShowsIt() {
        val xml = rootPreferences()
        val start = xml.indexOf("key=\"${SettingsVisibility.UNLINK_CATEGORY}\"")
        assertTrue(start > 0)
        // The attribute that hides it sits in the same element as the category key.
        val element = xml.substring(xml.lastIndexOf('<', start), xml.indexOf('>', start))
        assertTrue(element.contains("isPreferenceVisible=\"false\""))
    }

    @Test
    fun hiddenEntriesAreNotRemovedFromTheXml() {
        // The upstream fragments are still reachable on the QR-path install.
        val xml = rootPreferences()
        listOf(
            "CloudServerSettingsFragment",
            "MessagesSettingsFragment",
            "InboxSettingsFragment",
            "WebhooksSettingsFragment",
            "DeviceSettingsFragment",
            "LogsListFragment",
        ).forEach { assertTrue(it, xml.contains(it)) }
    }
}
