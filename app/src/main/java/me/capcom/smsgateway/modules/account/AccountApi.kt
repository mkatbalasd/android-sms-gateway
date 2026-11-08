package me.capcom.smsgateway.modules.account

import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
 * Ktor client for https://api.mnaseb.com/v1. JSON in and out, 15 s timeouts.
 * `Authorization: Bearer` is sent on the app routes only (enrolment); sign-up,
 * login and refresh carry none. Bodies are serialised here with Gson so the
 * wire format does not depend on a response content type.
 */
class AccountApi(
    private val baseUrl: String = DEFAULT_BASE_URL,
    engine: HttpClientEngine? = null,
) : AccountBackend {

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

    private suspend fun send(path: String, body: Any?, bearer: String?): String {
        val response: HttpResponse = client.post(baseUrl + path) {
            header(HttpHeaders.Accept, "application/json")
            if (bearer != null) {
                header(HttpHeaders.Authorization, "Bearer $bearer")
            }
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

    private data class ErrorBody(
        val code: String?,
        val message: String?,
        val details: Map<String, Any?>?,
    )

    internal fun parseError(status: Int, text: String): AccountError {
        val body = try {
            gson.fromJson(text, ErrorEnvelope::class.java)?.error
        } catch (e: com.google.gson.JsonParseException) {
            null
        }
        val code = body?.code?.takeIf { it.isNotBlank() } ?: "HTTP_$status"
        val details = body?.details
        return AccountError(
            code = code,
            httpStatus = status,
            message = body?.message,
            retryAfterSeconds = (details?.get("retry_after_seconds") as? Number)?.toInt(),
            attemptsLeft = ((details?.get("attempts_left") ?: details?.get("attemptsLeft")) as? Number)
                ?.toInt(),
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mnaseb.com/v1"
        private const val TIMEOUT_MS = 15_000L
    }
}
