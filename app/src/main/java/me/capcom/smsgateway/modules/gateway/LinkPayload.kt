package me.capcom.smsgateway.modules.gateway

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Mnaseb SIM link QR payload v1, also used as a deep link
 * (platform repo docs/design/ux/07-delivery.md §3.2):
 *
 *   mnaseb-sim://link?v=1&s=<server>&c=<code>&t=<label>&e=<expiry>
 *
 * Parameter order is free, unknown parameters are ignored, a repeated
 * parameter is an error, values are percent-encoded UTF-8 (`+` is not a
 * space), total length at most 256 characters.
 *
 * Differences from the UX text, by owner decision 0014 / decision 0008:
 * the code is not case-folded; the server is fixed, so `s` must be the
 * built-in Mnaseb server URL (no on-premise prompt in S1a). `k` (reserved
 * per-device signing key, UX D-02) is parsed and ignored, never stored or
 * logged.
 */
object LinkPayload {

    const val SCHEME = "mnaseb-sim"
    const val HOST = "link"

    private const val PREFIX = "$SCHEME://$HOST?"
    private const val MAX_LENGTH = 256
    private const val MAX_SERVER_LENGTH = 160
    private const val MAX_LABEL_LENGTH = 40
    private val CODE_PATTERN = Regex("^[0-9A-Za-z]{6,12}$")

    enum class Error {
        Invalid,
        UnsupportedVersion,
        ForbiddenServer,
        Expired,
    }

    sealed class Result {
        data class Ok(val code: String, val label: String?, val expiresAt: Long?) : Result()
        data class Failed(val error: Error) : Result()
    }

    fun parse(raw: String, allowedServer: String, nowEpochSeconds: Long): Result {
        // A URI has no legitimate whitespace or format characters.
        val text = LoginCodeSanitizer.clean(raw)
        if (text.length > MAX_LENGTH) return fail(Error.Invalid)
        if (!text.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) {
            return fail(Error.Invalid)
        }

        val params = HashMap<String, String>()
        for (part in text.substring(PREFIX.length).split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) return fail(Error.Invalid)
            val key = part.substring(0, eq)
            val value = percentDecode(part.substring(eq + 1)) ?: return fail(Error.Invalid)
            if (params.put(key, value) != null) return fail(Error.Invalid)
        }

        val version = params["v"] ?: return fail(Error.Invalid)
        if (version != "1") return fail(Error.UnsupportedVersion)

        val server = LoginCodeSanitizer.clean(params["s"] ?: return fail(Error.Invalid))
            .trimEnd('/')
        if (server.isEmpty() || server.length > MAX_SERVER_LENGTH) return fail(Error.Invalid)
        if (!isAllowedServer(server, allowedServer)) return fail(Error.ForbiddenServer)

        val code = LoginCodeSanitizer.clean(params["c"] ?: return fail(Error.Invalid))
        if (!CODE_PATTERN.matches(code)) return fail(Error.Invalid)

        val label = params["t"]?.let { LoginCodeSanitizer.stripFormatChars(it).trim() }
        if (label != null && label.length > MAX_LABEL_LENGTH) return fail(Error.Invalid)

        val expiresAt = params["e"]?.let { it.toLongOrNull() ?: return fail(Error.Invalid) }
        if (expiresAt != null && nowEpochSeconds > expiresAt) return fail(Error.Expired)

        // params["k"]: reserved, deliberately unused.

        return Result.Ok(code, label?.ifEmpty { null }, expiresAt)
    }

    private fun isAllowedServer(server: String, allowedServer: String): Boolean {
        val uri = try {
            URI(server)
        } catch (e: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "sms-gate.app" || host.endsWith(".sms-gate.app")) return false

        val allowed = try {
            URI(allowedServer.trimEnd('/'))
        } catch (e: Exception) {
            return false
        }
        return host == allowed.host?.lowercase() &&
            uri.port == allowed.port &&
            (uri.rawPath ?: "") == (allowed.rawPath ?: "") &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null
    }

    /** RFC 3986 percent-decoding into strict UTF-8; `+` stays `+`. Null if malformed. */
    private fun percentDecode(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%') {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (ch.code > 0x7F) return null
                bytes.write(ch.code)
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun fail(error: Error) = Result.Failed(error)
}
