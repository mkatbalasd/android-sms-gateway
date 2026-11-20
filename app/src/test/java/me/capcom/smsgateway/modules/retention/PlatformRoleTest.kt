package me.capcom.smsgateway.modules.retention

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The platform-phone role comes only from the server's webhook pull, as a device-scoped marker
 * hook (event system:ping, url query exactly `mnaseb_role=platform_phone`). A customer phone never
 * opens it from settings or from the local server.
 */
class PlatformRoleTest {
    private val marker = "https://platform.example/hook?mnaseb_role=platform_phone"
    private val own = "dev-1"

    private fun hook(
        event: WebHookEvent = WebHookEvent.SystemPing,
        url: String = marker,
        deviceId: String? = own,
        source: EntitySource = EntitySource.Cloud,
    ) = PlatformRole.Hook(event, url, deviceId, source)

    private fun role(vararg hooks: PlatformRole.Hook, ownId: String? = own) =
        PlatformRole.fromWebhooks(hooks.toList(), ownId)

    @Test
    fun theExactMarkerOpensTheRole() {
        assertTrue(role(hook()))
        assertTrue(role(hook(url = "http://127.0.0.1:8787/hook?mnaseb_role=platform_phone")))
        // among other hooks
        assertTrue(role(hook(url = "https://a/b", event = WebHookEvent.SmsReceived), hook()))
    }

    @Test
    fun noHooksIsNotThePlatformPhone() {
        assertFalse(role())
    }

    @Test
    fun aWrongEventDoesNotCount() {
        assertFalse(role(hook(event = WebHookEvent.SmsReceived)))
        assertFalse(role(hook(event = WebHookEvent.SmsSent)))
        assertFalse(role(hook(event = WebHookEvent.AppStarted)))
    }

    @Test
    fun aWrongQueryDoesNotCount() {
        listOf(
            "https://platform.example/hook",
            "https://platform.example/hook?",
            "https://platform.example/hook?mnaseb_role=customer_phone",
            "https://platform.example/hook?mnaseb_role=platform_phone2",
            "https://platform.example/hook?mnaseb_role=Platform_Phone",
            "https://platform.example/hook?MNASEB_ROLE=platform_phone",
            "https://platform.example/hook?a=1&mnaseb_role=platform_phone",
            "https://platform.example/hook?mnaseb_role=platform_phone&a=1",
            "https://platform.example/hook?a=1?mnaseb_role=platform_phone",
            "https://platform.example/hook?mnaseb_role=platform_phone#x",
            "https://platform.example/hook?mnaseb_role=platform_phone ",
            "https://platform.example/hook/mnaseb_role=platform_phone",
            "?mnaseb_role=platform_phone",
            "",
        ).forEach {
            assertFalse("url must not count: $it", role(hook(url = it)))
        }
    }

    @Test
    fun theMarkerIsTheRealQueryWithNoFragment() {
        // V2-L63: a `?` inside a fragment is not a query
        listOf(
            "https://h/x#?mnaseb_role=platform_phone",
            "https://h/x#a?mnaseb_role=platform_phone",
            "https://h/x?mnaseb_role=platform_phone#x",
            "https://h/x?mnaseb_role=platform_phone#",
            // encoded variants
            "https://h/x?mnaseb%5Frole=platform_phone",
            "https://h/x?mnaseb_role=platform%5Fphone",
            "https://h/x?mnaseb_role%3Dplatform_phone",
            "https://h/x?mnaseb_role=platform%5fphone",
            // not a usable absolute URL
            "ftp://h/x?mnaseb_role=platform_phone",
            "http:///x?mnaseb_role=platform_phone",
            "https://h/x?mnaseb_role=platform_phone\u0000",
            "https://h:bad/x?mnaseb_role=platform_phone",
            "ht tp://h/x?mnaseb_role=platform_phone",
            "https://[::1/x?mnaseb_role=platform_phone",
        ).forEach {
            assertFalse("url must not count: $it", PlatformRole.isMarkerUrl(it))
        }
        assertTrue(PlatformRole.isMarkerUrl("https://h/x?mnaseb_role=platform_phone"))
        assertTrue(PlatformRole.isMarkerUrl("HTTPS://H/x?mnaseb_role=platform_phone"))
        assertTrue(PlatformRole.isMarkerUrl("http://10.0.2.2:8787/hook?mnaseb_role=platform_phone"))
    }

    @Test
    fun aLocalSourceHookNeverCounts() {
        assertFalse(role(hook(source = EntitySource.Local)))
        assertFalse(role(hook(source = EntitySource.Local, deviceId = null)))
        // a Local marker beside a real Cloud one changes nothing either way
        assertTrue(role(hook(source = EntitySource.Local), hook()))
    }

    @Test
    fun aMarkerForAnotherDeviceDoesNotCount() {
        assertFalse(role(hook(deviceId = "dev-2")))
        assertTrue(role(hook(deviceId = "dev-2"), hook(deviceId = own)))
        // the pull names a device but this phone does not know its own id: closed
        assertFalse(role(hook(deviceId = own), ownId = null))
    }

    @Test
    fun anAccountScopedMarkerIsTheRoleWhenThePullGivesNoDeviceId() {
        assertTrue(role(hook(deviceId = null)))
        assertTrue(role(hook(deviceId = null), ownId = null))
    }

    // ---- what a pull does to the stored role

    private val prefs = FakePrefs()
    private val store = ActiveLineStore(prefs)

    private fun pullOk(vararg hooks: PlatformRole.Hook) = runBlocking {
        PlatformRole.apply(store, own) { hooks.toList() }
    }

    private fun pullFails() {
        try {
            runBlocking { PlatformRole.apply(store, own) { throw java.io.IOException("offline") } }
            throw AssertionError("a failed pull must rethrow so the worker retries")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    @Test
    fun theRoleIsClosedUntilAPullSaysOtherwise() {
        assertFalse(store.isPlatformPhone())
        assertFalse(store.accepts(1))
    }

    @Test
    fun aSuccessfulPullWithTheMarkerOpensTheRole() {
        pullOk(hook())
        assertTrue(store.isPlatformPhone())
        assertTrue("no list, platform phone: still accepts", store.accepts(1))
    }

    @Test
    fun aSuccessfulPullWithoutTheMarkerClosesAnOpenRole() {
        store.setPlatformPhone(true)
        pullOk(hook(event = WebHookEvent.SmsReceived))
        assertFalse(store.isPlatformPhone())
        store.setPlatformPhone(true)
        pullOk()
        assertFalse(store.isPlatformPhone())
        assertFalse(store.accepts(1))
    }

    @Test
    fun aFailedPullKeepsTheLastValueEitherWay() {
        store.setPlatformPhone(true)
        pullFails()
        assertTrue("an open role stays open", store.isPlatformPhone())

        store.setPlatformPhone(false)
        pullFails()
        assertFalse("a closed role stays closed", store.isPlatformPhone())
    }

    @Test
    fun aMarkerForAnotherDevicePullClosesTheRole() {
        store.setPlatformPhone(true)
        pullOk(hook(deviceId = "dev-2"))
        assertFalse(store.isPlatformPhone())
    }

    // ---- no other writer

    @Test
    fun onlyThePlatformRoleCodeOpensTheStoredRole() {
        val root = File("src/main/java")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val writers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("setPlatformPhone(") }
            .map { it.name }
            .toSortedSet()
        assertEquals(setOf("ActiveLineStore.kt", "PlatformRole.kt"), writers)
    }

    /** The smallest SharedPreferences the store needs. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
        override fun getAll(): MutableMap<String, *> = map
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun commit(): Boolean {
                map.putAll(pending)
                return true
            }
            override fun apply() { commit() }
            override fun clear() = apply { map.clear() }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
        }
    }
}
