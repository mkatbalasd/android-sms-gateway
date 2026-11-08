package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.gateway.DeviceNames
import java.net.URI

/**
 * What the app checks in an enrolment before it uses the login code. The app
 * never follows the server address in the answer: the gateway server is fixed
 * (decision 0008), so an answer that names another host is refused instead of
 * sending the one-time code there.
 */
object EnrolmentChecks {
    const val INVALID = "ENROLMENT_INVALID"
    const val SERVER_MISMATCH = "ENROLMENT_SERVER_MISMATCH"

    // Hex, base64 and base64url. Anything else is refused rather than stored
    // in a form the settings store may not return unchanged.
    private val keyShape = Regex("^[A-Za-z0-9+/=_-]{16,512}$")

    /** Null when the enrolment can be used, else the reason. */
    fun problem(enrolment: Enrolment, fixedServerUrl: String): String? {
        if (enrolment.loginCode.isNullOrBlank()) return INVALID
        if (!DeviceNames.isValidNonce(enrolment.nonce)) return INVALID
        val key = enrolment.signingKey
        if (key == null || !keyShape.matches(key)) return INVALID
        val server = enrolment.serverUrl
        if (!server.isNullOrBlank() && !sameHost(server, fixedServerUrl)) return SERVER_MISMATCH
        return null
    }

    private fun sameHost(offered: String, fixed: String): Boolean = try {
        val a = URI(offered)
        val b = URI(fixed)
        a.scheme.equals("https", ignoreCase = true) &&
            a.host != null &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)
    } catch (e: Exception) {
        false
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
