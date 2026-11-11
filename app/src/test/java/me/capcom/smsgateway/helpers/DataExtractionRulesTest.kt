package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CARD-21: device-protected data must not leave the phone by backup or device-to-device transfer.
 *
 * The device_* excludes carry a targeted tools:ignore="FullBackupContent" because the AGP 8.1.2
 * release lint applies the full-backup domain list to data-extraction rules (see the file
 * comment). allowBackup="false" alone does not stop device-to-device transfer on API 31+.
 */
class DataExtractionRulesTest {

    private val credentialDomains = listOf("root", "file", "database", "sharedpref", "external")
    private val deviceDomains = listOf("device_root", "device_file", "device_database", "device_sharedpref")
    private val domains = credentialDomains + deviceDomains

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }
    )

    private fun excludes(section: String): List<Element> {
        val sec = parse("res/xml/data_extraction_rules.xml")
            .getElementsByTagName(section).item(0) as Element
        val nodes = sec.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun excluded(section: String): Set<String> =
        excludes(section).map { it.getAttribute("domain") }.toSet()

    @Test
    fun cloudBackupExcludesEveryDomainIncludingDeviceProtected() {
        assertEquals(domains.toSet(), excluded("cloud-backup"))
    }

    @Test
    fun deviceTransferExcludesEveryDomainIncludingDeviceProtected() {
        assertEquals(domains.toSet(), excluded("device-transfer"))
    }

    @Test
    fun everyDeviceDomainExcludeCarriesTheTargetedLintIgnoreAndNoOtherDoes() {
        for (section in listOf("cloud-backup", "device-transfer")) {
            for (e in excludes(section)) {
                val domain = e.getAttribute("domain")
                val ignore = e.getAttribute("tools:ignore")
                if (domain in deviceDomains) {
                    assertEquals("$section/$domain", "FullBackupContent", ignore)
                } else {
                    assertEquals("$section/$domain", "", ignore)
                }
            }
        }
    }

    @Test
    fun lintIgnoreIsNotOnTheFileRoot() {
        val root = parse("res/xml/data_extraction_rules.xml").documentElement
        assertEquals("", root.getAttribute("tools:ignore"))
    }

    @Test
    fun manifestDisablesBackup() {
        val app = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttribute("android:allowBackup"))
    }
}
