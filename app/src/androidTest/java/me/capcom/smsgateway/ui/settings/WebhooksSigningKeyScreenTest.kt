package me.capcom.smsgateway.ui.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.capcom.smsgateway.R
import me.capcom.smsgateway.ui.account.AccountActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V2-L7: the Webhooks screen must never render the signing key. Stores a known dummy
 * key where the preference reads it, hosts the real fragment, and walks every view
 * (including the preference list rows) checking text and content descriptions.
 */
@RunWith(AndroidJUnit4::class)
class WebhooksSigningKeyScreenTest {

    private val prefKey = "webhooks.signing_key"
    private val key = "Zq7RmT4xKc9LpV2nBd8WfHs3JyGa6UeN"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(
        me.capcom.smsgateway.helpers.DeviceStorage.deContext(context)
    )
    private var previous: String? = null

    @Before
    fun storeKey() {
        previous = prefs.getString(prefKey, null)
        prefs.edit().putString(prefKey, key).commit()
    }

    @After
    fun restoreKey() {
        prefs.edit().apply {
            if (previous == null) remove(prefKey) else putString(prefKey, previous)
        }.commit()
    }

    private fun collect(v: View, out: MutableList<String>) {
        (v as? TextView)?.text?.let { out += it.toString() }
        v.contentDescription?.let { out += it.toString() }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    @Test
    fun signingKeyIsNeverRenderedOnTheWebhooksScreen() {
        val setText = context.getString(R.string.signing_key_set)
        ActivityScenario.launch(AccountActivity::class.java).use { scenario ->
            val fragment = WebhooksSettingsFragment()
            scenario.onActivity {
                it.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment)
                    .commitNow()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val rendered = mutableListOf<String>()
            scenario.onActivity { collect(fragment.requireView(), rendered) }

            assertTrue("signing key row not rendered", rendered.contains(setText))
            for (len in 5..key.length) {
                for (i in 0..key.length - len) {
                    val part = key.substring(i, i + len)
                    rendered.forEach { assertFalse("leaked '$part' in '$it'", it.contains(part)) }
                }
            }
            scenario.onActivity {
                val pref = fragment.findPreference<androidx.preference.EditTextPreference>(prefKey)
                assertEquals(setText, pref?.summary?.toString())
            }
        }
    }
}
