package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.account.AppHome
import me.capcom.smsgateway.modules.account.AppLine
import me.capcom.smsgateway.modules.account.KeepAliveChecklist
import me.capcom.smsgateway.modules.account.LineVerifyStart
import me.capcom.smsgateway.modules.account.MessageView
import me.capcom.smsgateway.modules.account.TestRecipient

/** A scripted platform: answers are queued, calls are recorded. */
internal class FakeAppClient : AppClient {

    var verifyAnswer: () -> LineVerifyStart = { error("verifyLine not scripted") }
    var linesAnswer: (call: Int) -> List<AppLine> = { emptyList() }
    var sendAnswer: () -> MessageView = { error("sendTestMessage not scripted") }
    var messageAnswer: (call: Int) -> MessageView = { error("message not scripted") }

    val verifyCalls = mutableListOf<Pair<Int, String?>>()
    var linesCalls = 0
    val sent = mutableListOf<Triple<String, String, String>>()
    var messageCalls = 0

    override suspend fun verifyLine(slot: Int, msisdn: String?): LineVerifyStart {
        verifyCalls.add(slot to msisdn?.trim()?.ifEmpty { null })
        return verifyAnswer()
    }

    override suspend fun lines(): List<AppLine> = linesAnswer(linesCalls++)

    override suspend fun home(): AppHome = error("home not scripted")

    override suspend fun testRecipients(): List<TestRecipient> = emptyList()

    override suspend fun sendTestMessage(
        idempotencyKey: String,
        recipientId: String,
        text: String,
    ): MessageView {
        sent.add(Triple(idempotencyKey, recipientId, text))
        return sendAnswer()
    }

    override suspend fun message(id: String): MessageView = messageAnswer(messageCalls++)

    override suspend fun handoffUrl(): String = error("handoff not scripted")

    override suspend fun keepAlive(checklist: KeepAliveChecklist) = Unit

    var unlinkAnswer: () -> Unit = {}
    val unlinkCalls = mutableListOf<String>()

    override suspend fun unlink(password: String) {
        unlinkCalls.add(password)
        unlinkAnswer()
    }

    /** V2-L23: what each pause or resume call does; the default is a 2xx. Calls go to [calls]. */
    var pauseAnswer: () -> Unit = {}
    var resumeAnswer: () -> Unit = {}
    val calls = mutableListOf<String>()

    override suspend fun pauseDevice() {
        calls.add("pause")
        pauseAnswer()
    }

    override suspend fun resumeDevice() {
        calls.add("resume")
        resumeAnswer()
    }
}

internal fun line(
    slot: Int,
    status: String = "ACTIVE",
    method: String? = "MO",
    verifiedAt: String? = "2026-11-10T10:00:00.000Z",
    masked: String? = "+967******200",
) = AppLine("l$slot", slot, masked, status, method, "STRONG", verifiedAt)

internal fun view(status: String?, id: String? = "m1") =
    MessageView(id, "+967******200", status, false, null, null)
