package me.capcom.smsgateway.e2e

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The test side of the stub (e2e/stub-server): the emulator reaches the host as 10.0.2.2. */
object StubControl {
    private const val BASE = "http://10.0.2.2:8787/__control"

    private fun call(method: String, path: String, body: String? = null): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 10_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun reachable(): Boolean = try {
        call("GET", "/health") == "ok"
    } catch (e: Exception) {
        false
    }

    fun state(): JSONObject = JSONObject(call("GET", "/state"))

    fun reset() {
        call("POST", "/reset", "{}")
    }

    fun injectOutbound(to: String, text: String) {
        call("POST", "/inject/outbound", JSONObject().put("to", to).put("text", text).toString())
    }

    /** Asks the host watcher (e2e/sms-watcher.sh) to fire `adb emu sms send` at the emulator. */
    fun requestInboundSms(from: String, text: String) {
        call("POST", "/inject/inbound-sms", JSONObject().put("from", from).put("text", text).toString())
    }
}
