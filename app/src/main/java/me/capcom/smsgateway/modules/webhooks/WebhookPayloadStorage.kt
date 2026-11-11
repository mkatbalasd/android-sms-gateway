package me.capcom.smsgateway.modules.webhooks

import android.content.Context
import me.capcom.smsgateway.helpers.DeviceStorage
import java.io.File

/**
 * Webhook payload files. CARD-21: they live in device-protected storage so the
 * queue can be written and read before the first unlock; files written by an
 * earlier version stay readable from the credential-encrypted directory.
 */
class WebhookPayloadStorage(
    private val context: Context,
) {
    private val payloadDir = File(DeviceStorage.deContext(context).filesDir, PAYLOAD_DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    // Touched only when a file is not in the device-protected directory (old queue rows).
    private val legacyDir by lazy { File(context.filesDir, PAYLOAD_DIR_NAME) }

    fun save(key: String, payload: String): String {
        val file = File(payloadDir, key)
        file.writeText(payload)
        return FILE_REF_PREFIX + key
    }

    fun read(payloadRefOrInlinePayload: String): String? {
        if (!payloadRefOrInlinePayload.startsWith(FILE_REF_PREFIX)) {
            // Backward compatibility for queue rows that still store inline payload.
            return payloadRefOrInlinePayload
        }

        val key = payloadRefOrInlinePayload.removePrefix(FILE_REF_PREFIX)
        val file = File(payloadDir, key)
        if (file.exists()) {
            return file.readText()
        }

        return try {
            File(legacyDir, key).takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun delete(key: String) {
        val file = File(payloadDir, key)
        if (file.exists()) {
            file.delete()
        }

        try {
            val legacy = File(legacyDir, key)
            if (legacy.exists()) {
                legacy.delete()
            }
        } catch (e: Exception) {
            // credential-encrypted storage is not available yet; nothing to remove
        }
    }

    companion object {
        private const val PAYLOAD_DIR_NAME = "webhook_payloads"
        private const val FILE_REF_PREFIX = "file:"
    }
}
