package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Type

/**
 * CARD-21 (CI: CloudPasswordScreenTest): the password is never stored, but a registration that
 * carries one still counts as "a password exists" for this session, so the Cloud server screen
 * shows "Set (hidden)" and not the "not stored" bullets.
 */
internal class GatewaySettingsPasswordTest {

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

    private val info = GatewayApi.DeviceRegisterResponse(
        id = "d1", token = "t", login = "l", password = "p-never-stored"
    )

    @Before
    @After
    fun reset() = SessionPassword.forget()

    @Test
    fun theStoredCopyNeverHoldsThePassword() {
        val storage = MapStorage()
        GatewaySettings(storage).registrationInfo = info

        val stored = storage.values.values.filterIsInstance<GatewayApi.DeviceRegisterResponse>().single()
        assertNull(stored.password)
        assertEquals("t", stored.token)
    }

    @Test
    fun aPasswordGivenAtRegistrationCountsAsSetForTheSession() {
        val storage = MapStorage()
        val settings = GatewaySettings(storage)
        settings.registrationInfo = info

        assertTrue(settings.hasPassword)
        assertEquals("p-never-stored", settings.password)

        // another injection of the settings in the same process sees it too (Koin factory)
        assertTrue(GatewaySettings(storage).hasPassword)
    }

    @Test
    fun afterARestartThereIsNoPasswordAndTheAccountStillReadsAsLinked() {
        val storage = MapStorage()
        GatewaySettings(storage).registrationInfo = info

        SessionPassword.forget() // what a new process starts with

        val settings = GatewaySettings(storage)
        assertFalse(settings.hasPassword)
        assertNull(settings.password)
        assertEquals("l", settings.username)
        assertEquals("t", settings.registrationInfo?.token)
    }

    @Test
    fun clearPasswordForgetsIt() {
        val settings = GatewaySettings(MapStorage())
        settings.registrationInfo = info
        settings.clearPassword()

        assertFalse(settings.hasPassword)
    }

    @Test
    fun unlinkingForgetsIt() {
        val storage = MapStorage()
        val settings = GatewaySettings(storage)
        settings.registrationInfo = info
        settings.registrationInfo = null

        assertNull(settings.registrationInfo)
        assertFalse(settings.hasPassword)
    }

    @Test
    fun aPasswordFromAChangeIsKeptInMemoryToo() {
        val settings = GatewaySettings(MapStorage())
        settings.registrationInfo = info
        settings.registrationInfo = settings.registrationInfo!!.copy(password = "changed")

        assertEquals("changed", settings.password)
    }
}
