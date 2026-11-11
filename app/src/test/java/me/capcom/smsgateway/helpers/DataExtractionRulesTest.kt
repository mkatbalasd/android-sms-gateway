package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CARD-21: device-protected data must not leave the phone by backup or device-to-device transfer.
 *
 * The device_* domains cannot be listed in data_extraction_rules.xml (the AGP 8.1.2 release lint
 * rejects them, see the file comment), so the guarantee for them is android:allowBackup="false",
 * pinned here together with the credential-protected domains the rules file does list.
 */
class DataExtractionRulesTest {

    private val domains = listOf("root", "file", "database", "sharedpref", "external")

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }
    )

    private fun excluded(section: String): Set<String> {
        val sec = parse("res/xml/data_extraction_rules.xml")
            .getElementsByTagName(section).item(0) as Element
        val nodes = sec.getElementsByTagName("exclude")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("domain") }.toSet()
    }

    @Test
    fun cloudBackupExcludesEveryListedDomain() {
        assertEquals(domains.toSet(), excluded("cloud-backup"))
    }

    @Test
    fun deviceTransferExcludesEveryListedDomain() {
        assertEquals(domains.toSet(), excluded("device-transfer"))
    }

    @Test
    fun rulesFileHasNoDeviceDomainsThatBreakTheReleaseLint() {
        assertFalse(excluded("cloud-backup").any { it.startsWith("device_") })
        assertFalse(excluded("device-transfer").any { it.startsWith("device_") })
    }

    @Test
    fun manifestDisablesBackupSoDeviceProtectedDataStaysOnThePhone() {
        val app = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttribute("android:allowBackup"))
    }
}
