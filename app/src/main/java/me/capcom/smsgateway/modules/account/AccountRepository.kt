package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mnaseb SIM v0.3.0: sign-up, sign-in, the app session and the enrolment
 * calls, over [AccountBackend] and [AccountSettings].
 *
 * The password is a parameter of [startSignup] and [startLogin] only: it is
 * passed to the backend and dropped, never stored and never returned.
 *
 * Refreshing: an access token is refreshed before use when it is about to
 * expire, and once after a 401. Refreshes are serialised by a mutex: the
 * server treats two refreshes with the same token as theft and ends the
 * session (CARD-05).
 */
class AccountRepository(
    private val api: AccountBackend,
    private val settings: AccountSettings,
    private val profile: DeviceProfile,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val refreshLock = Mutex()

    private fun device() = DeviceInfo(
        installId = settings.installId,
        model = profile.model.take(MODEL_MAX),
        os = profile.os.take(OS_MAX),
        appVersion = profile.appVersion.take(VERSION_MAX),
    )

    //region Sign-up and sign-in

    suspend fun startSignup(
        businessName: String,
        mobileE164: String,
        password: String,
        locale: String,
    ): PendingVerification {
        val started = api.signup(
            SignupRequest(
                businessName = businessName.trim(),
                contact = SignupContact("MOBILE", mobileE164),
                password = password,
                locale = locale,
                termsVersion = TERMS_VERSION,
                device = device(),
            )
        )
        val id = started.signupId ?: throw AccountException(AccountError("BAD_RESPONSE", 202))
        return remember(PendingVerification.KIND_SIGNUP, id, started.otp)
    }

    suspend fun startLogin(contact: String, password: String, locale: String): PendingVerification {
        val started = api.login(
            LoginRequest(
                contact = contact.trim(),
                password = password,
                device = device(),
                locale = locale,
            )
        )
        val id = started.loginId ?: throw AccountException(AccountError("BAD_RESPONSE", 202))
        return remember(PendingVerification.KIND_LOGIN, id, started.otp)
    }

    /** Asks for a new code for the pending sign-up or login. */
    suspend fun resend(): PendingVerification {
        val pending = settings.pending ?: throw AccountException(AccountError("NO_PENDING", 0))
        return if (pending.kind == PendingVerification.KIND_SIGNUP) {
            val answer = api.signupResend(SignupResendRequest(pending.id))
            remember(pending.kind, answer.signupId ?: pending.id, answer.otp, pending)
        } else {
            val answer = api.loginResend(LoginResendRequest(pending.id))
            remember(pending.kind, answer.loginId ?: pending.id, answer.otp, pending)
        }
    }

    /** Confirms the pending code. On success the app session is stored and the pending code is cleared. */
    suspend fun verify(rawCode: String): VerifyOutcome {
        val pending = settings.pending ?: throw AccountException(AccountError("NO_PENDING", 0))
        val code = OtpDigits.normalize(rawCode)
        val answer = if (pending.kind == PendingVerification.KIND_SIGNUP) {
            api.signupVerify(SignupVerifyRequest(pending.id, code))
        } else {
            api.loginVerify(LoginVerifyRequest(pending.id, code, device()))
        }
        val session = answer.appSession
        settings.saveIdentity(answer.tenant, answer.user)
        settings.pending = null
        if (session == null || session.access.isNullOrEmpty() || session.refresh.isNullOrEmpty()) {
            return VerifyOutcome(signedIn = false, enrolment = null, next = answer.next)
        }
        settings.saveSession(session, now())
        return VerifyOutcome(signedIn = true, enrolment = answer.enrolment, next = answer.next)
    }

    fun pending(): PendingVerification? = settings.pending

    fun discardPending() {
        settings.pending = null
    }

    private fun remember(
        kind: String,
        id: String,
        otp: OtpInfo?,
        previous: PendingVerification? = null,
    ): PendingVerification {
        val pending = PendingVerification(
            kind = kind,
            id = id,
            sender = otp?.sender ?: previous?.sender,
            sentToMasked = otp?.sentToMasked ?: previous?.sentToMasked,
            expiresAt = otp?.expiresAt ?: previous?.expiresAt,
            resendAfter = otp?.resendAfter ?: previous?.resendAfter,
        )
        settings.pending = pending
        return pending
    }

    //endregion

    //region Enrolment (needs the app session)

    /** POST /v1/app/enrolment. Null when the server has no enrolment to give yet. */
    suspend fun mintEnrolment(): Enrolment? = withSession { access -> api.mintEnrolment(access) }

    suspend fun completeEnrolment(gatewayDeviceId: String, nonce: String) {
        withSession { access ->
            api.completeEnrolment(access, EnrolmentCompleteRequest(gatewayDeviceId, nonce))
        }
    }

    //endregion

    //region App session

    private suspend fun <T> withSession(call: suspend (access: String) -> T): T {
        var access = currentAccess()
        try {
            return call(access)
        } catch (e: AccountException) {
            if (!isRefreshable(e.error)) throw e
        }
        // One refresh and one retry; a second 401 is the caller's problem.
        access = refreshAfter(access)
        return call(access)
    }

    private suspend fun currentAccess(): String {
        val access = settings.access
        if (access.isNullOrEmpty() || settings.accessExpired(now())) {
            return refreshAfter(access)
        }
        return access
    }

    /**
     * Refreshes the session unless another caller already did (the stored
     * access token is no longer [stale]). Persists the new pair before it is used.
     */
    private suspend fun refreshAfter(stale: String?): String = refreshLock.withLock {
        val current = settings.access
        if (!current.isNullOrEmpty() && current != stale && !settings.accessExpired(now())) {
            return@withLock current
        }
        val refresh = settings.refresh
        if (refresh.isNullOrEmpty()) {
            throw AccountException(AccountError("APP_SESSION_INVALID", 401))
        }
        val session = try {
            api.refresh(RefreshRequest(refresh, settings.installId))
        } catch (e: AccountException) {
            if (e.error.httpStatus == 401) {
                settings.clearSession()
            }
            throw e
        }
        settings.saveSession(session, now())
        settings.access ?: throw AccountException(AccountError("BAD_RESPONSE", 200))
    }

    private fun isRefreshable(error: AccountError): Boolean =
        error.httpStatus == 401 && error.code !in NOT_REFRESHABLE

    //endregion

    companion object {
        /** The terms text shown to the keeper in rc1; the download page serves the live version in CARD-12. */
        const val TERMS_VERSION = "draft-1"

        private const val MODEL_MAX = 60
        private const val OS_MAX = 40
        private const val VERSION_MAX = 20

        // A locked or suspended account is not helped by a new access token.
        private val NOT_REFRESHABLE = setOf("ACCOUNT_LOCKED", "TENANT_SUSPENDED")
    }
}

/** Codes arrive typed by the keeper or read from an SMS; Arabic-Indic digits are accepted. */
object OtpDigits {
    /** Arabic-Indic and Persian digits become ASCII; every other character is kept. */
    fun ascii(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> out.append('0' + (ch - '٠'))
                in '۰'..'۹' -> out.append('0' + (ch - '۰'))
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /** Only the digits, as ASCII. */
    fun normalize(input: String): String = ascii(input).filter { it in '0'..'9' }
}
