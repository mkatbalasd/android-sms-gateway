package me.capcom.smsgateway.modules.account

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** CARD V2-L23: the two app routes, no body, app bearer. */
internal class PauseApiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String?>()

    private fun api(status: HttpStatusCode, body: String): AccountApi {
        val engine = MockEngine { request ->
            requests.add(request)
            bodies.add((request.body as? TextContent)?.text)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return AccountApi(engine = engine)
    }

    @Test
    fun pauseAndResumeArePostsWithTheBearerAndNoBody() = runBlocking {
        val api = api(HttpStatusCode.OK, """{"paused":true}""")

        api.pauseDevice("tok")
        api.resumeDevice("tok")

        assertEquals(
            listOf("POST /v1/app/device/pause", "POST /v1/app/device/resume"),
            requests.map { "${it.method.value} ${it.url.encodedPath}" }
        )
        assertEquals(listOf("Bearer tok", "Bearer tok"), requests.map { it.headers[HttpHeaders.Authorization] })
        assertNull(bodies[0])
        assertNull(bodies[1])
    }

    @Test
    fun anEmptyTwoHundredIsASuccess() = runBlocking {
        api(HttpStatusCode.NoContent, "").pauseDevice("tok")
        Unit
    }

    @Test
    fun aRefusedPauseIsAnAccountException() = runBlocking {
        val api = api(
            HttpStatusCode.ServiceUnavailable,
            """{"success":false,"error":{"code":"UNAVAILABLE","message":"x"}}"""
        )
        try {
            api.pauseDevice("tok")
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals(503, e.error.httpStatus)
        }
    }
}
