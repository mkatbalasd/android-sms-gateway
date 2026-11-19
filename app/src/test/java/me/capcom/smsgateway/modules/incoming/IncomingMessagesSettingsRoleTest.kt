package me.capcom.smsgateway.modules.incoming

import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

/** CARD-24 follow-up: the platform-phone role comes only from an explicit server setting. */
class IncomingMessagesSettingsRoleTest {
    private class NoStorage : KeyValueStorage {
        override fun <T> set(key: String, value: T) {}
        override fun <T> get(key: String, typeOfT: Type): T? = null
        override fun remove(key: String) {}
    }

    private val seen = mutableListOf<Boolean>()
    private val settings = IncomingMessagesSettings(NoStorage()) { seen += it }

    @Test
    fun aFullPullWithNoIncomingSectionIsDetected() {
        assertTrue(IncomingMessagesSettings.sectionMissing(emptyMap<String, Any>()))
        assertTrue(IncomingMessagesSettings.sectionMissing(mapOf("messages" to mapOf("x" to 1))))
        assertTrue(IncomingMessagesSettings.sectionMissing(mapOf("incoming" to null)))
        assertFalse(IncomingMessagesSettings.sectionMissing(mapOf("incoming" to mapOf("lifetime_days" to 3))))
    }

    @Test
    fun onlyARealTrueOpensTheRole() {
        assertTrue(IncomingMessagesSettings.parsePlatformPhone(true))
        assertTrue(IncomingMessagesSettings.parsePlatformPhone("true"))
        assertFalse(IncomingMessagesSettings.parsePlatformPhone(false))
        assertFalse(IncomingMessagesSettings.parsePlatformPhone(null))
        assertFalse(IncomingMessagesSettings.parsePlatformPhone("yes"))
        assertFalse(IncomingMessagesSettings.parsePlatformPhone(1))
        assertFalse(IncomingMessagesSettings.parsePlatformPhone(""))
    }

    @Test
    fun anAbsentKeyClosesTheRoleAndTheServerSettingReachesTheStoreHook() {
        settings.import(mapOf("lifetime_days" to null))
        assertEquals(listOf(false), seen)
        seen.clear()

        settings.import(mapOf(IncomingMessagesSettings.PLATFORM_PHONE to true))
        settings.import(mapOf(IncomingMessagesSettings.PLATFORM_PHONE to false))
        settings.import(mapOf(IncomingMessagesSettings.PLATFORM_PHONE to "garbage"))
        assertEquals(listOf(true, false, false), seen)
    }
}
