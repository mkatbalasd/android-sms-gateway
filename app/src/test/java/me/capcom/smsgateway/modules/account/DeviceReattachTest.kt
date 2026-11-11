package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.ui.account.AccountRouting
import me.capcom.smsgateway.ui.account.FakeAppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

internal class DeviceReattachTest {

    private val settings = AccountSettings(FakeKeyValueStorage()).apply {
        saveIdentity(TenantInfo("t", "Demo", "SANDBOX"), null)
        markLinked()
        reattachPending = true
    }

    private val client = FakeAppClient().apply {
        challengeAnswer = { ReattachChallenge("c1", "CIPHER", "2026-11-11T10:00:00.000Z") }
        reattachAnswer = { ReattachResult(true, "d1") }
    }

    private fun reattach(
        deviceId: String? = "gw-device",
        decrypt: suspend (String) -> String = { "nonce-of-" + it },
    ) = DeviceReattach(client, settings, { deviceId }, decrypt)

    @Test
    fun aGoodChallengeIsDecryptedAnsweredAndClearsThePending() = runBlocking {
        val outcome = reattach().run()

        assertEquals(ReattachOutcome.Attached, outcome)
        assertEquals(listOf("gw-device"), client.challengeCalls)
        // The decrypted nonce, nothing else, goes back with the challenge id.
        assertEquals(listOf("c1" to "nonce-of-CIPHER"), client.reattachCalls)
        assertFalse(settings.reattachPending)
        assertTrue(settings.linked)
    }

    @Test
    fun aDecryptThatFailsIsAnErrorAndNothingIsSentBack() = runBlocking {
        val outcome = reattach(decrypt = { throw IllegalStateException("bad key") }).run()

        assertEquals(ReattachOutcome.Failed(DeviceReattach.DECRYPT_FAILED), outcome)
        assertTrue(client.reattachCalls.isEmpty())
        // The link and the pending re-attach stay: a retry is possible.
        assertTrue(settings.reattachPending)
        assertTrue(settings.linked)
    }

    @Test
    fun aSessionAlreadyAttachedIsASuccessNoop() = runBlocking {
        client.challengeAnswer = {
            throw AccountException(AccountError(DeviceReattach.SESSION_ALREADY_ATTACHED, 409))
        }

        assertEquals(ReattachOutcome.Attached, reattach().run())
        assertFalse(settings.reattachPending)
    }

    @Test
    fun aSessionAlreadyAttachedOnTheSecondStepIsAlsoASuccess() = runBlocking {
        client.reattachAnswer = {
            throw AccountException(AccountError(DeviceReattach.SESSION_ALREADY_ATTACHED, 409))
        }

        assertEquals(ReattachOutcome.Attached, reattach().run())
    }

    @Test
    fun aDeviceTheAccountDoesNotHaveIsAnErrorThatKeepsTheLink() = runBlocking {
        client.challengeAnswer = { throw AccountException(AccountError("DEVICE_NOT_FOUND", 404)) }

        assertEquals(ReattachOutcome.Failed("DEVICE_NOT_FOUND"), reattach().run())
        assertTrue(settings.reattachPending)
        assertTrue(settings.linked)
    }

    @Test
    fun aPhoneWithoutAGatewayDeviceIdIsNotFoundWithoutACall() = runBlocking {
        assertEquals(
            ReattachOutcome.Failed(DeviceReattach.DEVICE_NOT_FOUND),
            reattach(deviceId = null).run()
        )
        assertTrue(client.challengeCalls.isEmpty())
    }

    @Test
    fun aNetworkFailureIsARetryableError() = runBlocking {
        client.challengeAnswer = { throw IOException("offline") }

        assertEquals(ReattachOutcome.Failed("NETWORK"), reattach().run())
        assertTrue(settings.reattachPending)
    }

    @Test
    fun aRefusedAnswerIsAnError() = runBlocking {
        client.reattachAnswer = { ReattachResult(false, null) }

        assertEquals(ReattachOutcome.Failed("BAD_RESPONSE"), reattach().run())
        assertTrue(settings.reattachPending)
    }

    @Test
    fun theChallengeAndTheRequestNeverPrintTheirSecrets() {
        assertFalse(ReattachChallenge("c1", "SECRETCIPHER", null).toString().contains("SECRETCIPHER"))
        assertFalse(ReattachRequest("c1", "SECRETNONCE").toString().contains("SECRETNONCE"))
    }

    // region routing of the first-run screens

    @Test
    fun aLinkedPhoneOpensOnSignInWhateverItsSession() {
        for (hasSession in listOf(false, true)) {
            assertEquals(
                me.capcom.smsgateway.ui.account.AccountScreen.SIGN_IN,
                AccountRouting.start(linked = true, hasSession = hasSession, codeWaiting = false)
            )
        }
    }

    @Test
    fun anUnlinkedPhoneKeepsTheFirstRunRouting() {
        assertEquals(null, AccountRouting.start(false, false, false))
        assertEquals(
            me.capcom.smsgateway.ui.account.AccountScreen.LINKING,
            AccountRouting.start(false, true, false)
        )
        assertEquals(
            me.capcom.smsgateway.ui.account.AccountScreen.CODE,
            AccountRouting.start(true, false, true)
        )
    }

    @Test
    fun aVerifyOnALinkedPhoneNeverEnrols() {
        assertEquals(
            AccountRouting.AfterVerify.REATTACH,
            AccountRouting.afterVerify(resign = true, signedIn = true)
        )
        assertEquals(
            AccountRouting.AfterVerify.ENROL,
            AccountRouting.afterVerify(resign = false, signedIn = true)
        )
        assertEquals(
            AccountRouting.AfterVerify.SIGN_IN_NEXT,
            AccountRouting.afterVerify(resign = true, signedIn = false)
        )
    }

    // endregion
}
