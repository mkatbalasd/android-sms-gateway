package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CARD-21: the gateway password and the local server secrets never reach device-protected
 * storage, not even for a moment: the copy is scrubbed before it is renamed into place.
 */
class PreferencesFileScrubberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prefs = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="gateway.ENABLED" value="true" />
    <string name="gateway.REGISTRATION_INFO">{"id":"d1","token":"t","login":"l","password":"p"}</string>
    <string name="localserver.PASSWORD">lp</string>
    <string name="localserver.JWT_SECRET">js</string>
    <string name="webhooks.signing_key">k</string>
    <int name="messages.limit" value="5" />
</map>
"""

    private fun write(text: String): File = tmp.newFile().apply { writeText(text) }

    private fun entries(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val map = doc.documentElement
        return (0 until map.childNodes.length)
            .mapNotNull { map.childNodes.item(it) as? Element }
            .associate { it.getAttribute("name") to (it.getAttribute("value").ifEmpty { it.textContent }) }
    }

    @Test
    fun removesThePasswordAndTheLocalServerSecretsAndKeepsTheRest() {
        val file = write(prefs)

        assertTrue(PreferencesFileScrubber.scrub(file))

        val e = entries(file)
        assertFalse(e["gateway.REGISTRATION_INFO"]!!.contains("password"))
        assertTrue(e["gateway.REGISTRATION_INFO"]!!.contains("\"token\":\"t\""))
        assertFalse(e.containsKey("localserver.PASSWORD"))
        assertFalse(e.containsKey("localserver.JWT_SECRET"))
        assertEquals("true", e["gateway.ENABLED"])
        assertEquals("k", e["webhooks.signing_key"])
        assertEquals("5", e["messages.limit"])
        assertFalse(file.readText().contains("\"password\""))
    }

    @Test
    fun aSecondRunChangesNothing() {
        val file = write(prefs)
        PreferencesFileScrubber.scrub(file)
        val once = file.readText()

        assertFalse(PreferencesFileScrubber.scrub(file))
        assertEquals(once, file.readText())
    }

    @Test
    fun aFileWithNothingToScrubIsLeftByteForByte() {
        val clean = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="gateway.REGISTRATION_INFO">{"id":"d1","token":"t"}</string>
</map>
"""
        val file = write(clean)

        assertFalse(PreferencesFileScrubber.scrub(file))
        assertEquals(clean, file.readText())
    }

    @Test(expected = Exception::class)
    fun anUnreadableFileFailsInsteadOfPublishingAnUnscrubbedCopy() {
        PreferencesFileScrubber.scrub(write("<map><string name=\"a\">"))
    }
}
