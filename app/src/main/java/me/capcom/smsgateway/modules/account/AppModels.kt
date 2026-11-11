package me.capcom.smsgateway.modules.account

import com.google.gson.annotations.SerializedName

/*
 * Mnaseb SIM v0.3.0 (CARD-12): the app routes after linking, with the field
 * names the platform sends (`/v1/app/lines*`, `/home`, `/test-*`, `/handoff`,
 * `/unlink`, `PATCH /device`). Every field is nullable: an answer is read
 * leniently and the screens decide what a missing value means.
 */

data class LineVerifyRequest(
    /** 1-based SIM slot, as the platform numbers them. */
    val slot: Int,
    /** The number the keeper typed; null when nothing was typed (MO discovers it). */
    val msisdn: String? = null,
)

/**
 * The answer of POST /v1/app/lines/verify. [text] is the secret the platform
 * matches the SMS by: it is sent by the phone and never shown, never logged.
 */
data class LineVerifyStart(
    val method: String?,
    val to: String?,
    val text: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
) {
    override fun toString() = "LineVerifyStart(method=$method)"

    companion object {
        const val METHOD_MO = "MO"
        const val METHOD_LOOPBACK = "LOOPBACK"
    }
}

data class AppLine(
    val id: String?,
    val slot: Int?,
    @SerializedName("msisdn_masked") val msisdnMasked: String?,
    val status: String?,
    val method: String?,
    val strength: String?,
    @SerializedName("verified_at") val verifiedAt: String?,
) {
    /** A line the platform proved: active, with a verification time. */
    val isVerified: Boolean get() = status == STATUS_ACTIVE && !verifiedAt.isNullOrEmpty()

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
    }
}

data class AppLines(val items: List<AppLine>?)

data class HomeTenant(
    val id: String?,
    val name: String?,
    val mode: String?,
    @SerializedName("approval_status") val approvalStatus: String?,
)

data class HomeLine(
    val slot: Int?,
    @SerializedName("msisdn_masked") val msisdnMasked: String?,
    val status: String?,
)

data class HomeDevice(
    val id: String?,
    val name: String?,
    val status: String?,
    @SerializedName("last_seen_at") val lastSeenAt: String?,
    val lines: List<HomeLine>?,
)

data class HomeRecipients(val verified: Int?, val max: Int?)

/** GET /v1/app/home: read only. */
data class AppHome(
    val tenant: HomeTenant?,
    @SerializedName("balance_minor") val balanceMinor: Long?,
    @SerializedName("sent_today") val sentToday: Int?,
    @SerializedName("sandbox_daily_max") val sandboxDailyMax: Int?,
    val devices: List<HomeDevice>?,
    @SerializedName("test_recipients") val testRecipients: HomeRecipients?,
    @SerializedName("keep_alive_pending") val keepAlivePending: Boolean?,
    /** V2-L23: the owner paused this phone on the platform. Absent from an older platform. */
    val paused: Boolean? = null,
    @SerializedName("paused_at") val pausedAt: String? = null,
    /** The platform's hold limit in minutes (top level of app-home); the phone falls back to 60. */
    @SerializedName("pause_hold_max_min") val pauseHoldMaxMin: Int? = null,
)

data class TestRecipient(
    val id: String?,
    val label: String?,
    @SerializedName("msisdn_masked") val msisdnMasked: String?,
    @SerializedName("verified_at") val verifiedAt: String?,
)

data class TestRecipients(val items: List<TestRecipient>?)

data class TestMessageRequest(
    @SerializedName("test_recipient_id") val testRecipientId: String,
    val text: String,
)

/** The app's view of a message: no content, the number masked. */
data class MessageView(
    val id: String?,
    @SerializedName("to_masked") val toMasked: String?,
    val status: String?,
    @SerializedName("not_billed") val notBilled: Boolean?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("expires_at") val expiresAt: String?,
)

/** The one-time link of POST /v1/app/handoff. [url] is a credential for 60 seconds: never logged, never shown. */
data class HandoffResponse(
    val url: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
) {
    override fun toString() = "HandoffResponse(redacted)"
}

data class KeepAliveChecklist(val battery: Boolean, val boot: Boolean, val oem: Boolean)

data class KeepAliveRequest(
    @SerializedName("keep_alive_checklist") val keepAliveChecklist: KeepAliveChecklist,
)

data class UnlinkRequest(val password: String) {
    override fun toString() = "UnlinkRequest(redacted)"
}

/** POST /v1/app/device/reattach/challenge: the gateway device id this phone holds. */
data class ReattachChallengeRequest(
    @SerializedName("externalDeviceId") val externalDeviceId: String,
)

/** A nonce encrypted to this phone's E2E public key. Never logged: the ciphertext is redacted. */
data class ReattachChallenge(
    @SerializedName("challengeId") val challengeId: String?,
    @SerializedName("ciphertext") val ciphertext: String?,
    @SerializedName("expiresAt") val expiresAt: String?,
) {
    override fun toString() = "ReattachChallenge(redacted)"
}

/** POST /v1/app/device/reattach: the decrypted nonce proves the phone holds the device key. */
data class ReattachRequest(
    @SerializedName("challengeId") val challengeId: String,
    @SerializedName("nonce") val nonce: String,
) {
    override fun toString() = "ReattachRequest(redacted)"
}

data class ReattachResult(
    @SerializedName("attached") val attached: Boolean?,
    @SerializedName("deviceId") val deviceId: String?,
)
