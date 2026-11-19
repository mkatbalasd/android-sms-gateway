package me.capcom.smsgateway.modules.incoming

import android.content.SharedPreferences
import me.capcom.smsgateway.modules.retention.ActiveLineStore
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Type

/**
 * CARD-24 follow-up, inverted: settings can no longer open the platform-phone role. A
 * `platform_phone` value in an `incoming` section (PATCH /settings on the local server, a settings
 * import, the server pull) is ignored; the role comes only from the webhook pull (PlatformRole).
 */
class IncomingMessagesSettingsRoleTest {
    private class RecordingStorage : KeyValueStorage {
        val written = mutableMapOf<String, Any?>()
        override fun <T> set(key: String, value: T) { written[key] = value }
        override fun <T> get(key: String, typeOfT: Type): T? = null
        override fun remove(key: String) { written.remove(key) }
    }

    private val storage = RecordingStorage()
    private val settings = IncomingMessagesSettings(storage)
    private val prefs = EmptyPrefs()
    private val role = ActiveLineStore(prefs)

    @Test
    fun anImportOfTrueLeavesTheRoleClosed() {
        assertFalse(role.isPlatformPhone())
        val changed = settings.import(mapOf("platform_phone" to true))
        assertFalse("nothing changed", changed)
        assertFalse(role.isPlatformPhone())
        assertFalse(role.accepts(1))
        assertTrue("nothing was stored under the key either", storage.written.isEmpty())
        assertEquals("no role key reached the store", emptyMap<String, Any?>(), prefs.written)
    }

    @Test
    fun everyShapeOfTheKeyIsIgnored() {
        listOf<Any?>(true, "true", "TRUE", 1, mapOf("a" to 1), null).forEach {
            assertFalse(settings.import(mapOf("platform_phone" to it)))
            assertFalse(role.isPlatformPhone())
        }
        assertTrue(storage.written.isEmpty())
    }

    @Test
    fun theKeyDoesNotDisturbTheRealSetting() {
        val changed = settings.import(mapOf("lifetime_days" to 3, "platform_phone" to true))
        assertTrue("the real setting still applies", changed)
        assertEquals("3", storage.written["lifetime_days"])
        assertFalse(storage.written.containsKey("platform_phone"))
        assertFalse(role.isPlatformPhone())
    }

    @Test
    fun theExportNeverShowsARole() {
        assertEquals(setOf("lifetime_days"), settings.export().keys)
    }

    /** Remembers every write, so a stray role key would show. */
    private class EmptyPrefs : SharedPreferences {
        val written = HashMap<String, Any?>()

        override fun getString(key: String, defValue: String?): String? = written[key] as String? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = written[key] as Boolean? ?: defValue
        override fun getAll(): MutableMap<String, *> = written
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun contains(key: String) = written.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun commit(): Boolean {
                written.putAll(pending)
                return true
            }
            override fun apply() { commit() }
            override fun clear() = apply { written.clear() }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
        }
    }
}
