package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** CARD-21: device-protected data must not leave the phone by backup or device-to-device transfer. */
class DataExtractionRulesTest {

    private val domains = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun excluded(section: String): Set<String> {
        val f = listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("app/src/main/res/xml/data_extraction_rules.xml"),
        ).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val sec = doc.getElementsByTagName(section).item(0) as Element
        val nodes = sec.getElementsByTagName("exclude")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("domain") }.toSet()
    }

    @Test
    fun cloudBackupExcludesEveryDomainIncludingDeviceProtected() {
        assertEquals(domains.toSet(), excluded("cloud-backup"))
    }

    @Test
    fun deviceTransferExcludesEveryDomainIncludingDeviceProtected() {
        assertEquals(domains.toSet(), excluded("device-transfer"))
    }
}
