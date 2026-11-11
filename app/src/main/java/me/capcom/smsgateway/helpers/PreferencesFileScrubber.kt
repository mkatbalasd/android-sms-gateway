package me.capcom.smsgateway.helpers

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * CARD-21: scrubs a copy of the default SharedPreferences XML file, before the copy is
 * renamed into device-protected storage, so the gateway password and the local server
 * secrets are never there, not even for a moment (a kill between the copy and a later
 * scrub would have left them).
 *
 * The original (credential-encrypted) file is never touched.
 */
object PreferencesFileScrubber {
    private const val REGISTRATION_INFO_KEY = "gateway.REGISTRATION_INFO"

    /** Mnaseb never runs the local server; these are regenerated if ever needed. */
    private val REMOVED_KEYS = setOf("localserver.PASSWORD", "localserver.JWT_SECRET")

    /**
     * @return `true` when the file was rewritten; a file with nothing to scrub is left untouched
     * @throws Exception when the file cannot be read as preferences XML (nothing is written)
     */
    fun scrub(file: File): Boolean {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = file.inputStream().use { factory.newDocumentBuilder().parse(it) }
        val root = doc.documentElement

        var changed = false
        val toRemove = ArrayList<Element>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            if (node.tagName != "string") continue

            val name = node.getAttribute("name")
            when {
                name in REMOVED_KEYS -> toRemove += node
                name == REGISTRATION_INFO_KEY -> {
                    RegistrationInfoScrubber.scrub(node.textContent)?.let {
                        node.textContent = it
                        changed = true
                    }
                }
            }
        }
        for (node in toRemove) {
            root.removeChild(node)
            changed = true
        }

        if (!changed) return false

        doc.xmlStandalone = true
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        file.outputStream().use { transformer.transform(DOMSource(doc), StreamResult(it)) }
        return true
    }
}
