package me.capcom.smsgateway.modules.account

/**
 * What the screens of CARD-12 need from the platform. Tests substitute a fake.
 * Errors are [AccountException] (an error envelope) or [java.io.IOException].
 */
interface AppClient {
    suspend fun verifyLine(slot: Int, msisdn: String?): LineVerifyStart
    suspend fun lines(): List<AppLine>
    suspend fun home(): AppHome
    suspend fun testRecipients(): List<TestRecipient>
    suspend fun sendTestMessage(idempotencyKey: String, recipientId: String, text: String): MessageView
    suspend fun message(id: String): MessageView

    /** The one-time workspace link. The caller opens it and forgets it. */
    suspend fun handoffUrl(): String
    suspend fun keepAlive(checklist: KeepAliveChecklist)
    suspend fun unlink(password: String)

    /** V2-L23: tells the platform this phone is paused; the caller stops the services only after it returns. */
    suspend fun pauseDevice()

    /** V2-L23: tells the platform this phone is back. */
    suspend fun resumeDevice()

    /** Step 1 of the re-attach: a nonce encrypted to this phone's E2E key. */
    suspend fun reattachChallenge(externalDeviceId: String): ReattachChallenge

    /** Step 2: the decrypted nonce; the platform ties the new app session to the phone. */
    suspend fun reattach(challengeId: String, nonce: String): ReattachResult
}

/** [AppClient] over [AppBackend], with the session refresh of [AccountRepository] around every call. */
class AppRepository(
    private val api: AppBackend,
    private val account: AccountRepository,
) : AppClient {

    override suspend fun verifyLine(slot: Int, msisdn: String?): LineVerifyStart =
        account.withSession { api.verifyLine(it, LineVerifyRequest(slot, msisdn?.trim()?.ifEmpty { null })) }

    override suspend fun lines(): List<AppLine> =
        account.withSession { api.lines(it) }.items.orEmpty()

    override suspend fun home(): AppHome = account.withSession { api.home(it) }

    override suspend fun testRecipients(): List<TestRecipient> =
        account.withSession { api.testRecipients(it) }.items.orEmpty()

    override suspend fun sendTestMessage(
        idempotencyKey: String,
        recipientId: String,
        text: String,
    ): MessageView = account.withSession {
        api.sendTestMessage(it, idempotencyKey, TestMessageRequest(recipientId, text))
    }

    override suspend fun message(id: String): MessageView =
        account.withSession { api.message(it, id) }

    override suspend fun handoffUrl(): String {
        val url = account.withSession { api.handoff(it) }.url
        // The platform's HANDOFF_URL_BASE (handoff.service.ts): any other host is refused.
        if (url.isNullOrEmpty() || !url.startsWith(HANDOFF_URL_BASE)) {
            throw AccountException(AccountError("BAD_RESPONSE", 200))
        }
        return url
    }

    override suspend fun keepAlive(checklist: KeepAliveChecklist) {
        account.withSession { api.keepAlive(it, checklist) }
    }

    override suspend fun unlink(password: String) {
        account.withSession { api.unlink(it, password) }
    }

    override suspend fun pauseDevice() {
        account.withSession { api.pauseDevice(it) }
    }

    override suspend fun resumeDevice() {
        account.withSession { api.resumeDevice(it) }
    }

    override suspend fun reattachChallenge(externalDeviceId: String): ReattachChallenge =
        account.withSession { api.reattachChallenge(it, ReattachChallengeRequest(externalDeviceId)) }

    override suspend fun reattach(challengeId: String, nonce: String): ReattachResult =
        account.withSession { api.reattach(it, ReattachRequest(challengeId, nonce)) }

    private companion object {
        const val HANDOFF_URL_BASE = "https://app.mnaseb.com/handoff#t="
    }
}
