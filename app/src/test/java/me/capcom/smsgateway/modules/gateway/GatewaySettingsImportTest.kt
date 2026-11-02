package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

internal class GatewaySettingsImportTest {

    private class MapStorage : KeyValueStorage {
        val values = HashMap<String, Any?>()

        override fun <T> set(key: String, value: T) {
            values[key] = value
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: String, typeOfT: Type): T? = values[key] as T?

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun importNeverRepointsTheServer() {
        val storage = MapStorage()
        val settings = GatewaySettings(storage)

        val changed = settings.import(
            mapOf(
                "cloud_url" to "https://evil.example.com/api/mobile/v1",
                "private_token" to "attacker-token",
            )
        )

        assertFalse(changed)
        assertEquals(GatewaySettings.PUBLIC_URL, settings.serverUrl)
        assertNull(settings.privateToken)
        assertFalse(storage.values.containsKey("cloud_url"))
        assertFalse(storage.values.containsKey("private_token"))
    }

    @Test
    fun storedCloudUrlIsIgnored() {
        val storage = MapStorage()
        storage.values["cloud_url"] = "https://old.example.com/api/mobile/v1"

        assertEquals(GatewaySettings.PUBLIC_URL, GatewaySettings(storage).serverUrl)
    }

    @Test
    fun notificationChannelIsStillImported() {
        val storage = MapStorage()
        val settings = GatewaySettings(storage)

        assertTrue(settings.import(mapOf("notification_channel" to "SSE_ONLY")))
        assertEquals("SSE_ONLY", storage.values["notification_channel"])
    }
}
