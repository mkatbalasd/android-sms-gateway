package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.get
import java.security.SecureRandom

/**
 * Mnaseb SIM v0.3.0: what the app remembers about the platform account.
 *
 * Production wiring gives this class an encrypted store ("mnaseb-account",
 * [AccountStorageFactory]). It holds the app session tokens, the tenant name
 * and mode, a random install id and, while a code is waiting, the identifier
 * of that sign-up or login. It never holds the password: the password is only
 * a parameter of the sign-up and login calls and there is no setter for it.
 */
/** What complete needs after a crash: the gateway device id and the nonce of the enrolment. */
data class PendingCompletion(val gatewayDeviceId: String, val nonce: String)

class AccountSettings(private val storage: KeyValueStorage) {

    /** One JSON document under one key: strings with spaces survive the round trip. */
    data class Stored(
        val access: String? = null,
        val refresh: String? = null,
        val accessExpiresAtMs: Long? = null,
        val tenantId: String? = null,
        val tenantName: String? = null,
        val tenantMode: String? = null,
        val userId: String? = null,
        val installId: String? = null,
        val linked: Boolean? = null,
        val pending: PendingVerification? = null,
        val pendingCompletion: PendingCompletion? = null,
        val reattachPending: Boolean? = null,
    ) {
        override fun toString() = "Stored(redacted)"
    }

    @Synchronized
    private fun read(): Stored = storage.get<Stored>(KEY) ?: Stored()

    @Synchronized
    private fun update(change: (Stored) -> Stored) {
        storage.set(KEY, change(read()))
    }

    /** A random identifier of this installation, created on first use. */
    @get:Synchronized
    val installId: String
        get() {
            read().installId?.let { return it }
            val created = newInstallId()
            update { it.copy(installId = created) }
            return created
        }

    val access: String? get() = read().access
    val refresh: String? get() = read().refresh
    val hasSession: Boolean get() = !read().refresh.isNullOrEmpty()

    val tenantId: String? get() = read().tenantId
    val tenantName: String? get() = read().tenantName
    val tenantMode: String? get() = read().tenantMode

    /** True after the phone enrolled itself through an app session. */
    val linked: Boolean get() = read().linked == true

    /** True when the access token is missing or about to expire (local clock, an optimisation only). */
    fun accessExpired(nowMs: Long, marginMs: Long = 30_000L): Boolean {
        val stored = read()
        val expiresAt = stored.accessExpiresAtMs ?: return true
        return stored.access.isNullOrEmpty() || nowMs + marginMs >= expiresAt
    }

    fun saveSession(session: AppSession, nowMs: Long) {
        val access = session.access
        val refresh = session.refresh
        require(!access.isNullOrEmpty() && !refresh.isNullOrEmpty()) { "incomplete app session" }
        val lifetimeMs = (session.expiresIn ?: DEFAULT_EXPIRES_IN_S) * 1000L
        update {
            it.copy(access = access, refresh = refresh, accessExpiresAtMs = nowMs + lifetimeMs)
        }
    }

    fun saveIdentity(tenant: TenantInfo?, user: UserInfo?) {
        update {
            it.copy(
                tenantId = tenant?.id ?: it.tenantId,
                tenantName = tenant?.name ?: it.tenantName,
                tenantMode = tenant?.mode ?: it.tenantMode,
                userId = user?.id ?: it.userId,
            )
        }
    }

    var pending: PendingVerification?
        get() = read().pending
        set(value) = update { it.copy(pending = value) }

    /** The gateway device whose complete has not been confirmed yet; survives a crash. */
    var pendingCompletion: PendingCompletion?
        get() = read().pendingCompletion
        set(value) = update { it.copy(pendingCompletion = value) }

    /**
     * True after a linked phone signed in again and until the platform tied the new app
     * session to the phone (the re-attach). Survives a crash; cleared by [reset].
     */
    var reattachPending: Boolean
        get() = read().reattachPending == true
        set(value) = update { it.copy(reattachPending = value) }

    /** One write: linked, and nothing left to complete. */
    fun markLinked() = update { it.copy(linked = true, pendingCompletion = null) }

    /** Forgets the tokens (a refused refresh, a sign-out). The install id and the link flag stay. */
    fun clearSession() = update {
        it.copy(access = null, refresh = null, accessExpiresAtMs = null)
    }

    /**
     * Forgets the account after «فصل هذا الهاتف»: tokens, tenant, link flag and anything pending.
     * Only the random install id stays, so a later sign-in is still one install.
     */
    fun reset() = update { Stored(installId = it.installId) }

    private fun newInstallId(): String {
        val random = SecureRandom()
        val out = StringBuilder(INSTALL_ID_LENGTH)
        repeat(INSTALL_ID_LENGTH) { out.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return out.toString()
    }

    companion object {
        private const val KEY = "state"
        private const val DEFAULT_EXPIRES_IN_S = 900L
        private const val INSTALL_ID_LENGTH = 24
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
