package me.capcom.smsgateway.modules.account

import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess

/**
 * The platform account API as the app uses it. Tests substitute a fake.
 * Every method throws [AccountException] for an error envelope and lets I/O
 * errors (timeouts, no network) propagate as [java.io.IOException].
 */
interface AccountBackend {
    suspend fun signup(request: SignupRequest): SignupStarted
    suspend fun signupVerify(request: SignupVerifyRequest): VerifyResponse
    suspend fun signupResend(request: SignupResendRequest): SignupStarted
    suspend fun login(request: LoginRequest): LoginStarted
    suspend fun loginVerify(request: LoginVerifyRequest): VerifyResponse
    suspend fun loginResend(request: LoginResendRequest): LoginStarted
    suspend fun refresh(request: RefreshRequest): AppSession

    /** POST /v1/app/enrolment (app bearer): mints a fresh enrolment. */
    suspend fun mintEnrolment(access: String): Enrolment?

    /** POST /v1/app/enrolment/complete (app bearer). */
    suspend fun completeEnrolment(access: String, request: EnrolmentCompleteRequest)
}

/**
 * The app routes after linking (CARD-12). Every call carries the app bearer
 * [access]; the repository refreshes the session around them.
 */
interface AppBackend {
    suspend fun verifyLine(access: String, request: LineVerifyRequest): LineVerifyStart
    suspend fun lines(access: String): AppLines
    suspend fun home(access: String): AppHome
    suspend fun testRecipients(access: String): TestRecipients
    suspend fun sendTestMessage(
        access: String,
        idempotencyKey: String,
        request: TestMessageRequest,
    ): MessageView

    suspend fun message(access: String, id: String): MessageView
    suspend fun handoff(access: String): HandoffResponse
    suspend fun keepAlive(access: String, checklist: KeepAliveChecklist)
    suspend fun unlink(access: String, password: String)
}

/**
 * Ktor client for https://api.mnaseb.com/v1. JSON in and out, 15 s timeouts.
 * `Authorization: Bearer` is sent on the app routes only (enrolment); sign-up,
 * login and refresh carry none. Bodies are serialised here with Gson so the
 * wire format does not depend on a response content type.
 */
class AccountApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    engine: HttpClientEngine? = null,
) : AccountBackend, AppBackend {

    private val gson = Gson()

    // An injected engine is a test double; the 15 s timeouts belong to the real OkHttp engine.
    private val client: HttpClient = if (engine != null) {
        HttpClient(engine) { configureCommon(withTimeouts = false) }
    } else {
        HttpClient(OkHttp) { configureCommon(withTimeouts = true) }
    }

    private fun HttpClientConfig<*>.configureCommon(withTimeouts: Boolean) {
        // The status is checked by [send]; the error envelope is parsed there.
        expectSuccess = false
        if (withTimeouts) {
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    override suspend fun signup(request: SignupRequest): SignupStarted =
        parse(send("/signup", request, null))

    override suspend fun signupVerify(request: SignupVerifyRequest): VerifyResponse =
        parse(send("/signup/verify", request, null))

    override suspend fun signupResend(request: SignupResendRequest): SignupStarted =
        parse(send("/signup/resend", request, null))

    override suspend fun login(request: LoginRequest): LoginStarted =
        parse(send("/app/login", request, null))

    override suspend fun loginVerify(request: LoginVerifyRequest): VerifyResponse =
        parse(send("/app/login/verify", request, null))

    override suspend fun loginResend(request: LoginResendRequest): LoginStarted =
        parse(send("/app/login/resend", request, null))

    override suspend fun refresh(request: RefreshRequest): AppSession {
        val response = parse<RefreshResponse>(send("/app/refresh", request, null))
        return response.appSession
            ?: throw AccountException(AccountError("BAD_RESPONSE", 200))
    }

    override suspend fun mintEnrolment(access: String): Enrolment? =
        parse<EnrolmentEnvelope>(send("/app/enrolment", null, access)).resolve()

    override suspend fun completeEnrolment(access: String, request: EnrolmentCompleteRequest) {
        send("/app/enrolment/complete", request, access)
    }

    //region App routes (CARD-12)

    override suspend fun verifyLine(access: String, request: LineVerifyRequest): LineVerifyStart =
        parse(send("/app/lines/verify", request, access))

    override suspend fun lines(access: String): AppLines =
        parse(send("/app/lines", null, access, HttpMethod.Get))

    override suspend fun home(access: String): AppHome =
        parse(send("/app/home", null, access, HttpMethod.Get))

    override suspend fun testRecipients(access: String): TestRecipients =
        parse(send("/app/test-recipients", null, access, HttpMethod.Get))

    override suspend fun sendTestMessage(
        access: String,
        idempotencyKey: String,
        request: TestMessageRequest,
    ): MessageView = parse(
        send(
            "/app/test-message", request, access,
            extraHeaders = mapOf(IDEMPOTENCY_HEADER to idempotencyKey)
        )
    )

    override suspend fun message(access: String, id: String): MessageView {
        // The id comes from the platform; anything but a plain id never reaches a path.
        if (!ID_PATTERN.matches(id)) throw AccountException(AccountError("BAD_RESPONSE", 200))
        return parse(send("/app/messages/$id", null, access, HttpMethod.Get))
    }

    override suspend fun handoff(access: String): HandoffResponse =
        parse(send("/app/handoff", null, access))

    override suspend fun keepAlive(access: String, checklist: KeepAliveChecklist) {
        send("/app/device", KeepAliveRequest(checklist), access, HttpMethod.Patch)
    }

    override suspend fun unlink(access: String, password: String) {
        send("/app/unlink", UnlinkRequest(password), access)
    }

    //endregion

    private suspend fun send(
        path: String,
        body: Any?,
        bearer: String?,
        method: HttpMethod = HttpMethod.Post,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val response: HttpResponse = client.request(baseUrl + path) {
            this.method = method
            header(HttpHeaders.Accept, "application/json")
            if (bearer != null) {
                header(HttpHeaders.Authorization, "Bearer $bearer")
            }
            extraHeaders.forEach { (name, value) -> header(name, value) }
            if (body != null) {
                setBody(TextContent(gson.toJson(body), ContentType.Application.Json))
            }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw AccountException(parseError(response.status.value, text))
        }
        return text
    }

    private inline fun <reified T> parse(text: String): T {
        val value = try {
            gson.fromJson(text, T::class.java)
        } catch (e: com.google.gson.JsonParseException) {
            null
        }
        return value ?: throw AccountException(AccountError("BAD_RESPONSE", 200))
    }

    private data class ErrorEnvelope(val success: Boolean?, val error: ErrorBody?)

    // The platform's `details` is an object for the rate limits, an array for a
    // validation failure and a plain string for the per-tenant throughput guard,
    // so it is read as a JSON tree and only an object is used.
    private data class ErrorBody(
        val code: String?,
        val message: String?,
        val details: com.google.gson.JsonElement?,
    )

    internal fun parseError(status: Int, text: String): AccountError {
        val body = try {
            gson.fromJson(text, ErrorEnvelope::class.java)?.error
        } catch (e: com.google.gson.JsonParseException) {
            null
        }
        val code = body?.code?.takeIf { it.isNotBlank() } ?: "HTTP_$status"
        val details = body?.details?.takeIf { it.isJsonObject }?.asJsonObject
        return AccountError(
            code = code,
            httpStatus = status,
            message = body?.message,
            retryAfterSeconds = details.number("retry_after_seconds"),
            attemptsLeft = details.number("attempts_left") ?: details.number("attemptsLeft"),
        )
    }

    private fun com.google.gson.JsonObject?.number(name: String): Int? {
        val member = this?.get(name) ?: return null
        return if (member.isJsonPrimitive && member.asJsonPrimitive.isNumber) member.asInt else null
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mnaseb.com/v1"
        private const val TIMEOUT_MS = 15_000L
        const val IDEMPOTENCY_HEADER = "X-Idempotency-Key"
        private val ID_PATTERN = Regex("^[0-9A-Za-z-]{1,64}$")
    }
}
