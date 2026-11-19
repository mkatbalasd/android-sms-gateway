package me.capcom.smsgateway.modules.retention

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-24 follow-up (owner decision 2026-11-19): a customer phone fails closed; the platform
 * phone is an explicit, default-closed role; a list that cannot be refreshed is kept; an open
 * number-proof window passes.
 */
class ActiveLineStoreTest {
    private var now = 1_000_000L
    private val prefs = MemoryPrefs()
    private val store = ActiveLineStore(prefs) { now }

    @Test
    fun aCustomerPhoneThatNeverReceivedAListKeepsNothing() {
        assertNull(store.slots())
        assertFalse(store.isPlatformPhone())
        assertFalse(store.accepts(1))
        assertFalse(store.accepts(2))
        assertFalse(store.accepts(null))
    }

    @Test
    fun theRoleIsClosedByDefaultAndOnlyAnExplicitSettingOpensIt() {
        assertFalse(store.isPlatformPhone())
        store.setPlatformPhone(true)
        assertTrue(store.isPlatformPhone())
        assertTrue(store.accepts(1))
        assertTrue(store.accepts(null))
        store.setPlatformPhone(false)
        assertFalse(store.accepts(1))
    }

    @Test
    fun aMissingListNeverOpensTheFilterWithoutTheRole() {
        // the platform phone and a customer phone look the same to the list: only the role differs
        assertFalse(store.accepts(1))
        store.record(emptySet())
        assertFalse(store.accepts(1))
    }

    @Test
    fun aListThatCannotBeRefreshedIsKept() {
        store.record(setOf(1))
        assertTrue(store.accepts(1))
        // a failed refresh calls nothing on the store; the next read still sees the last list
        assertEquals(setOf(1), store.slots())
        assertTrue(store.accepts(1))
        assertFalse(store.accepts(2))
    }

    @Test
    fun anUnreadableStoreIsAClosedFilter() {
        prefs.broken = true
        assertFalse(store.accepts(1))
        assertFalse(store.isPlatformPhone())
    }

    @Test
    fun aProofWindowAcceptsItsSlotWithNoListAndEndsWithTheWindow() {
        store.addSlot(2, windowMs = 10 * 60_000L)
        assertNull("a proof does not invent a list", store.slots())
        assertTrue(store.accepts(2))
        assertFalse("another slot (the personal SIM) is still dropped", store.accepts(1))

        now += 10 * 60_000L + 1
        assertFalse("the window is over", store.accepts(2))
    }

    @Test
    fun aProofOnAKnownListKeepsTheSlotAfterTheWindow() {
        store.record(setOf(1))
        store.addSlot(2, windowMs = 60_000L)
        now += 120_000L
        assertTrue(store.accepts(2))
    }

    @Test
    fun unlinkingResetsListProofAndRoleToClosed() {
        store.setPlatformPhone(true)
        store.record(setOf(1))
        store.addSlot(2)
        store.clear()
        assertNull(store.slots())
        assertFalse(store.isPlatformPhone())
        assertFalse(store.accepts(1))
        assertFalse(store.accepts(2))
    }

    @Test
    fun theProofWindowIsBoundedAroundThePlatformsExpiry() {
        assertEquals(15 * 60_000L + 120_000L, ActiveLineStore.proofWindowMs(null))
        assertEquals(60_000L + 120_000L, ActiveLineStore.proofWindowMs(1))
        assertEquals(600_000L + 120_000L, ActiveLineStore.proofWindowMs(600))
        assertEquals(3_600_000L + 120_000L, ActiveLineStore.proofWindowMs(999_999))
    }

    @Test
    fun theProofFormRoundTripsAndDropsEndedWindows() {
        val windows = ActiveLineStore.withProof(mapOf(1 to 50L, 3 to 500L), 2, 900L, nowMs = 100L)
        assertEquals(mapOf(3 to 500L, 2 to 900L), windows)
        assertEquals(windows, ActiveLineStore.parseProof(ActiveLineStore.encodeProof(windows)))
        assertEquals(setOf(3, 2), ActiveLineStore.activeProofSlots(windows, 100L))
        assertEquals(emptyMap<Int, Long>(), ActiveLineStore.parseProof("x:1,2:y,,3"))
    }

    /** The smallest SharedPreferences the store needs; `broken` makes every read throw. */
    private class MemoryPrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        var broken = false

        private fun check() { if (broken) throw IllegalStateException("unreadable") }

        override fun getString(key: String, defValue: String?): String? { check(); return map[key] as String? ?: defValue }
        override fun getBoolean(key: String, defValue: Boolean): Boolean { check(); return map[key] as Boolean? ?: defValue }
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
            private val removed = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun commit(): Boolean {
                removed.forEach { map.remove(it) }
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
