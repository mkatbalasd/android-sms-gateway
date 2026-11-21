package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R5-7: the shipped build types (release and sendOnly) trust the system CA store only.
 * No user-installed CA and no bundled upstream CA (@raw/ca): a user-installed root must not be
 * able to intercept the app's traffic to the Mnaseb hosts, which use a public CA.
 *
 * The test reads the XML file that app/build.gradle maps to those two build types, so pointing a
 * type back at the permissive file, or adding a certificates source to the release file, fails.
 * The debug and e2e types keep their own (permissive) files by design and are not asserted here.
 */
class NetworkSecurityConfigTest {

    private val releaseConfigName = "network_security_config_release"

    private fun file(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun parse(xml: ByteArray): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private fun parseConfig(name: String): Document =
        parse(file("src/main/res/xml/$name.xml").readBytes())

    /** Every `src` of every `<certificates>` in the document (base, domain and debug-overrides). */
    private fun certificateSources(doc: Document): List<String> {
        val nodes = doc.getElementsByTagName("certificates")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("src") }
    }

    /** The non-system sources in [doc]; empty means the config is system-anchors-only. */
    private fun nonSystemSources(doc: Document): List<String> =
        certificateSources(doc).filter { it != "system" }

    /** The body of `name { ... }` inside [text] from [from], matching braces; null when absent. */
    private fun blockBody(text: String, name: String, from: Int = 0): String? {
        val m = Regex("(^|\\s)${Regex.escape(name)}\\s*\\{").find(text, from) ?: return null
        val open = m.range.last
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /** The @xml/... name that app/build.gradle sets for build type [type] (null when it sets none). */
    private fun configOfBuildType(type: String): String? {
        val gradle = file("build.gradle").readText()
        val buildTypes = blockBody(gradle, "buildTypes")
        assertNotNull("buildTypes block not found in app/build.gradle", buildTypes)
        val body = blockBody(buildTypes!!, type)
        assertNotNull("build type $type not found in app/build.gradle", body)
        return Regex("networkSecurityConfig\\s*:\\s*\"@xml/([A-Za-z0-9_]+)\"")
            .find(body!!)?.groupValues?.get(1)
    }

    @Test
    fun releaseAndSendOnlyBuildTypesUseTheReleaseConfig() {
        assertEquals(releaseConfigName, configOfBuildType("release"))
        assertEquals(releaseConfigName, configOfBuildType("sendOnly"))
    }

    @Test
    fun theConfigOfEachShippedBuildTypeTrustsOnlySystemAnchors() {
        for (type in listOf("release", "sendOnly")) {
            val name = configOfBuildType(type)
            assertNotNull("$type has no networkSecurityConfig placeholder", name)
            val doc = parseConfig(name!!)
            assertTrue("$type ($name) declares no trust anchors", certificateSources(doc).isNotEmpty())
            assertEquals(
                "$type ($name) trusts a non-system anchor",
                emptyList<String>(),
                nonSystemSources(doc)
            )
        }
    }

    @Test
    fun releaseConfigBaseTrustsExactlySystemAndNoDomainOrDebugOverrideWidensIt() {
        val doc = parseConfig(releaseConfigName)
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        val anchors = base.getElementsByTagName("certificates")
        assertEquals(1, anchors.length)
        assertEquals("system", (anchors.item(0) as Element).getAttribute("src"))
        assertEquals(0, doc.getElementsByTagName("debug-overrides").length)
    }

    @Test
    fun releaseConfigKeepsCleartextOffExceptTheLoopbackAddress() {
        val doc = parseConfig(releaseConfigName)
        val base = doc.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))

        val domainConfigs = doc.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        val dc = domainConfigs.item(0) as Element
        assertEquals("true", dc.getAttribute("cleartextTrafficPermitted"))
        val domains = dc.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        val domain = domains.item(0) as Element
        assertEquals("127.0.0.1", domain.textContent.trim())
        assertEquals("false", domain.getAttribute("includeSubdomains"))
    }

    @Test
    fun noReleaseConfigReferencesTheBundledUpstreamCa() {
        val text = file("src/main/res/xml/$releaseConfigName.xml").readText()
        // the file comment explains the rule in words; only an actual attribute value counts
        assertFalse(Regex("src\\s*=\\s*\"[^\"]*@raw/").containsMatchIn(text))
        assertFalse(Regex("src\\s*=\\s*\"user\"").containsMatchIn(text))
    }

    // The checker must itself fail on a bad config: prove it against inline documents.

    private fun doc(xml: String) = parse(xml.toByteArray(Charsets.UTF_8))

    @Test
    fun theCheckRejectsAUserAnchorInTheBaseConfig() {
        val bad = doc(
            """<network-security-config><base-config><trust-anchors>
               <certificates src="system"/><certificates src="user"/>
               </trust-anchors></base-config></network-security-config>"""
        )
        assertEquals(listOf("user"), nonSystemSources(bad))
    }

    @Test
    fun theCheckRejectsABundledCaInTheBaseConfig() {
        val bad = doc(
            """<network-security-config><base-config><trust-anchors>
               <certificates src="@raw/ca"/>
               </trust-anchors></base-config></network-security-config>"""
        )
        assertEquals(listOf("@raw/ca"), nonSystemSources(bad))
    }

    @Test
    fun theCheckRejectsANonSystemAnchorInADomainConfig() {
        val bad = doc(
            """<network-security-config>
               <base-config><trust-anchors><certificates src="system"/></trust-anchors></base-config>
               <domain-config><domain>example.com</domain>
               <trust-anchors><certificates src="user"/></trust-anchors></domain-config>
               </network-security-config>"""
        )
        assertEquals(listOf("user"), nonSystemSources(bad))
    }

    @Test
    fun theCheckAcceptsASystemOnlyConfig() {
        val good = doc(
            """<network-security-config><base-config><trust-anchors>
               <certificates src="system"/></trust-anchors></base-config></network-security-config>"""
        )
        assertTrue(nonSystemSources(good).isEmpty())
    }
}
