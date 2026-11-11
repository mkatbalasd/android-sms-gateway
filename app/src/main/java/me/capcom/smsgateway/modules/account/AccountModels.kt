package me.capcom.smsgateway.modules.account

import com.google.gson.annotations.SerializedName

/*
 * Mnaseb SIM v0.3.0: wire models of the platform account API
 * (https://api.mnaseb.com/v1, sign-up CARD-04, app sessions CARD-05,
 * self-enrolment CARD-07).
 *
 * Every response field is nullable: Gson fills a missing field with null even
 * for a non-null Kotlin type, so a server that omits a field must not crash
 * the app. toString() of anything that carries a password, a token, a login
 * code or a signing key is redacted, so a stray log line cannot leak one.
 */

data class DeviceInfo(
    @SerializedName("install_id") val installId: String,
    val model: String?,
    val os: String?,
    @SerializedName("app_version") val appVersion: String?,
)

/** What the repository needs to describe this phone; filled from Build in the Koin module. */
data class DeviceProfile(
    val model: String,
    val os: String,
    val appVersion: String,
)

data class SignupContact(val type: String, val value: String)

data class SignupRequest(
    @SerializedName("business_name") val businessName: String,
    val contact: SignupContact,
    val password: String,
    val locale: String,
    @SerializedName("terms_version") val termsVersion: String,
    val device: DeviceInfo,
) {
    override fun toString() = "SignupRequest(redacted)"
}

data class SignupVerifyRequest(
    @SerializedName("signup_id") val signupId: String,
    val code: String,
) {
    override fun toString() = "SignupVerifyRequest(redacted)"
}

data class SignupResendRequest(@SerializedName("signup_id") val signupId: String)

data class LoginRequest(
    val contact: String,
    val password: String,
    val device: DeviceInfo,
    val locale: String?,
) {
    override fun toString() = "LoginRequest(redacted)"
}

data class LoginVerifyRequest(
    @SerializedName("login_id") val loginId: String,
    val code: String,
    val device: DeviceInfo,
    /**
     * true only when the app re-signs in on an already linked phone (V2-L14): the platform then
     * mints no enrolment. Null otherwise, so Gson leaves it out of the body.
     */
    val reattach: Boolean? = null,
) {
    override fun toString() = "LoginVerifyRequest(redacted)"
}

data class LoginResendRequest(@SerializedName("login_id") val loginId: String)

data class RefreshRequest(
    val refresh: String,
    @SerializedName("install_id") val installId: String?,
) {
    override fun toString() = "RefreshRequest(redacted)"
}

data class EnrolmentCompleteRequest(
    @SerializedName("gateway_device_id") val gatewayDeviceId: String,
    val nonce: String,
)

data class OtpInfo(
    val channel: String?,
    val sender: String?,
    @SerializedName("sent_to_masked") val sentToMasked: String?,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("resend_after") val resendAfter: String?,
)

data class SignupStarted(
    @SerializedName("signup_id") val signupId: String?,
    val otp: OtpInfo?,
)

data class LoginStarted(
    @SerializedName("login_id") val loginId: String?,
    val otp: OtpInfo?,
)

data class AppSession(
    val access: String?,
    val refresh: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    @SerializedName("session_id") val sessionId: String?,
) {
    override fun toString() = "AppSession(redacted)"
}

data class RefreshResponse(
    @SerializedName("app_session") val appSession: AppSession?,
)

data class TenantInfo(val id: String?, val name: String?, val mode: String?)

data class UserInfo(
    val id: String?,
    @SerializedName("tenant_role") val tenantRole: String?,
)

/**
 * What the server hands to an app session so the phone can enrol itself
 * (CARD-07). The login code and the signing key are shown to nobody.
 */
data class Enrolment(
    @SerializedName("server_url") val serverUrl: String?,
    @SerializedName("login_code") val loginCode: String?,
    @SerializedName("code_expires_at") val codeExpiresAt: String?,
    val label: String?,
    @SerializedName("signing_key") val signingKey: String?,
    val nonce: String?,
    @SerializedName("device_id") val deviceId: String?,
) {
    override fun toString() = "Enrolment(redacted)"
}

/**
 * The answer of POST /v1/app/enrolment: the enrolment object itself (CARD-07),
 * read leniently from either the top level or an `enrolment` member.
 */
data class EnrolmentEnvelope(
    val enrolment: Enrolment?,
    @SerializedName("server_url") val serverUrl: String?,
    @SerializedName("login_code") val loginCode: String?,
    @SerializedName("code_expires_at") val codeExpiresAt: String?,
    val label: String?,
    @SerializedName("signing_key") val signingKey: String?,
    val nonce: String?,
    @SerializedName("device_id") val deviceId: String?,
) {
    fun resolve(): Enrolment? = enrolment
        ?: if (loginCode != null) {
            Enrolment(serverUrl, loginCode, codeExpiresAt, label, signingKey, nonce, deviceId)
        } else {
            null
        }

    override fun toString() = "EnrolmentEnvelope(redacted)"
}

/** The answer of sign-up verify and of login verify. */
data class VerifyResponse(
    val tenant: TenantInfo?,
    val user: UserInfo?,
    @SerializedName("app_session") val appSession: AppSession?,
    val enrolment: Enrolment?,
    val next: String?,
)

/** A parsed error envelope `{success:false, error:{code, message, details}}`. */
data class AccountError(
    val code: String,
    val httpStatus: Int,
    val message: String? = null,
    val retryAfterSeconds: Int? = null,
    val attemptsLeft: Int? = null,
)

class AccountException(val error: AccountError) :
    RuntimeException("${error.code} (HTTP ${error.httpStatus})")

/** A code that was sent and has not been confirmed yet (sign-up or app login). */
data class PendingVerification(
    val kind: String,
    val id: String,
    val sender: String?,
    val sentToMasked: String?,
    val expiresAt: String?,
    val resendAfter: String?,
) {
    companion object {
        const val KIND_SIGNUP = "signup"
        const val KIND_LOGIN = "login"
    }
}

/** The result of confirming a code. */
data class VerifyOutcome(
    /** An app session was stored. False after a repeat sign-up verify: sign in instead. */
    val signedIn: Boolean,
    val enrolment: Enrolment?,
    val next: String?,
)
