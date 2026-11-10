package me.capcom.smsgateway.ui.settings

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.fragment.app.DialogFragment
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.gateway.GatewayApi
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.ui.account.AccountActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * V2-L8: the Cloud server screen must never render the cloud-account password, and its
 * edit dialog must be a non-copyable password field. Seeds a dummy registration, hosts
 * the real fragment, walks every view (text and content descriptions), opens the dialog.
 */
@RunWith(AndroidJUnit4::class)
class CloudPasswordScreenTest {

    private val password = "Zq7RmT4xKc9LpV2nBd8WfHs3JyGa6UeN"

    private fun collect(v: View, out: MutableList<String>) {
        (v as? TextView)?.text?.let { out += it.toString() }
        v.contentDescription?.let { out += it.toString() }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    @Test
    fun homeCardNeverRendersThePassword() {
        val settings = GlobalContext.get().get<GatewaySettings>()
        val bus = GlobalContext.get().get<me.capcom.smsgateway.modules.events.EventBus>()
        val previous = settings.registrationInfo
        val previousEnabled = settings.enabled
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.getString(R.string.signing_key_set)
        try {
            settings.registrationInfo = GatewayApi.DeviceRegisterResponse(
                id = "test-device", token = "test-token", login = "test-login", password = password
            )
            ActivityScenario.launch(me.capcom.smsgateway.MainActivity::class.java).use { scenario ->
                // The EventBus has no replay: re-emit until the Home collector is
                // subscribed and the card shows the masked value, or time out.
                val deadline = System.currentTimeMillis() + 10_000
                var text: String? = null
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.runBlocking {
                        bus.emit(
                            me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent.Success(
                                "https://example.invalid", "test-login", password
                            )
                        )
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        text = it.window.decorView
                            .findViewById<TextView>(R.id.textRemotePassword)?.text?.toString()
                    }
                    if (text == expected) break
                    Thread.sleep(250)
                }
                assertEquals("Home password card never showed the masked value", expected, text)
                val rendered = mutableListOf<String>()
                scenario.onActivity {
                    val tv = it.window.decorView.findViewById<TextView>(R.id.textRemotePassword)
                    assertFalse("selectable", tv.isTextSelectable)
                    collect(it.window.decorView, rendered)
                }
                for (len in 5..password.length) {
                    for (i in 0..password.length - len) {
                        val part = password.substring(i, i + len)
                        rendered.forEach { r -> assertFalse("leaked '$part' in '$r'", r.contains(part)) }
                    }
                }
            }
        } finally {
            settings.registrationInfo = previous
            settings.enabled = previousEnabled
            context.stopService(
                android.content.Intent(
                    context,
                    me.capcom.smsgateway.modules.gateway.services.SSEForegroundService::class.java
                )
            )
        }
    }

    @Test
    fun passwordIsNeverRenderedAndDialogIsNotCopyable() {
        val settings = GlobalContext.get().get<GatewaySettings>()
        val previous = settings.registrationInfo
        try {
            settings.registrationInfo = GatewayApi.DeviceRegisterResponse(
                id = "test-device", token = "test-token", login = "test-login", password = password
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val setText = context.getString(R.string.signing_key_set)

            ActivityScenario.launch(AccountActivity::class.java).use { scenario ->
                val fragment = CloudServerSettingsFragment()
                scenario.onActivity {
                    it.supportFragmentManager.beginTransaction()
                        .add(android.R.id.content, fragment)
                        .commitNow()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                fun assertNoLeak(rendered: List<String>) {
                    for (len in 5..password.length) {
                        for (i in 0..password.length - len) {
                            val part = password.substring(i, i + len)
                            rendered.forEach { assertFalse("leaked '$part' in '$it'", it.contains(part)) }
                        }
                    }
                }

                val rendered = mutableListOf<String>()
                scenario.onActivity {
                    collect(fragment.requireView(), rendered)
                    val pref = fragment.findPreference<EditTextPreference>("gateway.password")
                    assertEquals(setText, pref?.summary?.toString())
                    assertFalse("copying enabled", pref!!.isCopyingEnabled)
                }
                assertTrue("password row not rendered", rendered.contains(setText))
                assertNoLeak(rendered)

                scenario.onActivity {
                    fragment.onDisplayPreferenceDialog(
                        fragment.findPreference<EditTextPreference>("gateway.password")!!
                    )
                    it.supportFragmentManager.executePendingTransactions()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                scenario.onActivity {
                    val dialog = it.supportFragmentManager
                        .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
                            as DialogFragment
                    val edit = dialog.dialog!!.findViewById<EditText>(android.R.id.edit)
                    assertNotNull(edit)
                    assertTrue(
                        "not a password input",
                        edit.inputType and InputType.TYPE_MASK_VARIATION ==
                            InputType.TYPE_TEXT_VARIATION_PASSWORD
                    )
                    assertFalse("selectable", edit.isTextSelectable)
                    assertFalse("long clickable", edit.isLongClickable)
                    assertFalse(
                        edit.customSelectionActionModeCallback!!
                            .onCreateActionMode(null, null)
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        assertFalse(
                            edit.customInsertionActionModeCallback!!
                                .onCreateActionMode(null, null)
                        )
                    }
                    val dialogTexts = mutableListOf<String>()
                    collect(dialog.dialog!!.window!!.decorView, dialogTexts)
                    assertNoLeak(dialogTexts)
                }
            }
        } finally {
            settings.registrationInfo = previous
        }
    }
}
