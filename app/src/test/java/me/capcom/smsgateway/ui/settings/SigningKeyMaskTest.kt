package me.capcom.smsgateway.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

internal class SigningKeyMaskTest {

    private val key = "whsig-9fA3kLq82xZpR7vTbN4mWc01HdYeJ6uG"

    private fun moduleFile(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.first { it.exists() }
    }

    @Test
    fun summaryNeverContainsKeySubstringLongerThanFour() {
        val s = SigningKeyMask.summary(key, "Set (hidden)", "Not set")
        for (len in 5..key.length) {
            for (i in 0..key.length - len) {
                assertFalse(s.contains(key.substring(i, i + len)))
            }
        }
    }

    @Test
    fun summaryReflectsSetState() {
        assertEquals("Not set", SigningKeyMask.summary(null, "Set (hidden)", "Not set"))
        assertEquals("Not set", SigningKeyMask.summary("", "Set (hidden)", "Not set"))
        assertEquals("Set (hidden)", SigningKeyMask.summary(key, "Set (hidden)", "Not set"))
    }

    @Test
    fun xmlDoesNotUseSimpleSummaryProviderForSigningKey() {
        val xml = moduleFile("src/main/res/xml/webhooks_preferences.xml").readText()
        val block = xml.substringAfter("webhooks.signing_key").substringBefore("/>")
        assertFalse(block.contains("useSimpleSummaryProvider"))
        val before = xml.substringBefore("webhooks.signing_key").substringAfterLast("<EditTextPreference")
        assertFalse(before.contains("useSimpleSummaryProvider"))
    }
}
