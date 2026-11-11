package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.webhooks.WebhookPayloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CARD-21 (review rev-fork17-1, low): unlinking forgets everything the old link left in
 * device-protected storage that the next link must not inherit.
 */
class LinkSecretsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun forgetsTheSigningKeyThePassphraseAndTheTokens() {
        assertEquals(
            setOf(
                "webhooks.signing_key",
                "encryption.passphrase",
                "gateway.private_token",
                "gateway.fcm_token",
            ),
            LinkSecrets.PREFERENCE_KEYS.toSet()
        )
    }

    @Test
    fun theKeysAreRemovedAndNothingElse() {
        val removed = mutableListOf<String>()
        LinkSecrets.forget { removed += it }
        assertEquals(LinkSecrets.PREFERENCE_KEYS, removed)
        // the autostart switch, the settings and the registration are handled by their own steps
        assertFalse(removed.any { it.startsWith("messages.") || it == "gateway.REGISTRATION_INFO" })
    }

    @Test
    fun theCredentialEncryptedRollbackCopyLosesTheSameKeysAndTheRegistration() {
        // a downgrade after unlink + relink must not bring the old token, password or
        // signing key back from the copy the migration left in credential-encrypted storage
        val removed = mutableListOf<String>()
        LinkSecrets.forgetInRollbackCopy { removed += it }
        assertEquals(
            LinkSecrets.PREFERENCE_KEYS + "gateway.REGISTRATION_INFO",
            removed
        )
    }

    @Test
    fun payloadFilesOfTheOldLinkAreDeletedFromBothDirectories() {
        val de = tmp.newFolder("de")
        val ce = tmp.newFolder("ce")
        File(de, "p1").writeText("body of a webhook payload")
        File(ce, "p2").writeText("legacy payload")
        val untouched = File(tmp.root, "other").apply { writeText("keep") }

        WebhookPayloadStorage.deleteAllIn(de, ce, File(tmp.root, "missing"))

        assertEquals(0, de.list()!!.size)
        assertEquals(0, ce.list()!!.size)
        assertTrue(de.exists() && ce.exists())
        assertTrue(untouched.exists())
    }
}
