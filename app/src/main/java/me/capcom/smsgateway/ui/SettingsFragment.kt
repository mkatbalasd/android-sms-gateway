package me.capcom.smsgateway.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.MainActivity
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.LocaleHelper
import me.capcom.smsgateway.modules.account.AccountException
import me.capcom.smsgateway.modules.account.AccountSettings
import me.capcom.smsgateway.modules.account.AppEnrolled
import me.capcom.smsgateway.modules.account.AppUnlink
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.ui.account.AccountMessages
import me.capcom.smsgateway.ui.account.UiError
import me.capcom.smsgateway.ui.dialogs.PasswordPromptDialogFragment
import org.koin.android.ext.android.inject
import java.io.IOException

class SettingsFragment : PreferenceFragmentCompat() {

    private val accountSettings: AccountSettings by inject()
    private val gatewaySettings: GatewaySettings by inject()
    private val appUnlink: AppUnlink by inject()

    private var unlinking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // «فصل هذا الهاتف»: the password prompt answers here. Other prompts are not ours.
        setFragmentResultListener(PasswordPromptDialogFragment.REQUEST_KEY) { _, data ->
            if (PasswordPromptDialogFragment.getAction(data) != ACTION_UNLINK) {
                return@setFragmentResultListener
            }
            PasswordPromptDialogFragment.getPassword(data)?.let { unlink(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // The phone may have been linked since this screen was drawn (first run).
        applyVisibility()
    }

    // Mnaseb SIM v0.3.0: the customer phone hides the upstream settings (V2-D11).
    private fun applyVisibility() {
        val enrolled = AppEnrolled.check(accountSettings, gatewaySettings)
        val hidden = SettingsVisibility.hiddenKeys(enrolled)
        SettingsVisibility.allKeys().forEach { key ->
            findPreference<Preference>(key)?.isVisible = key !in hidden
        }
        findPreference<Preference>(SettingsVisibility.UNLINK_CATEGORY)?.isVisible = enrolled
    }

    private fun askForUnlink() {
        if (unlinking || parentFragmentManager.findFragmentByTag(UNLINK_PROMPT_TAG) != null) return
        PasswordPromptDialogFragment.newInstance(
            message = getString(R.string.unlink_prompt),
            action = ACTION_UNLINK,
        ).show(parentFragmentManager, UNLINK_PROMPT_TAG)
    }

    private fun unlink(password: String) {
        if (unlinking) return
        unlinking = true
        // The activity context carries the chosen language; the application one may not.
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            fun say(error: UiError) {
                Toast.makeText(ctx, AccountMessages.text(ctx, error), Toast.LENGTH_LONG).show()
            }
            try {
                appUnlink.run(password)
                // Back to the welcome screen: a fresh start of the app.
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                unlinking = false
                say(UiError.from(e.error))
                // A wrong password may be typed again.
                if (e.error.code == "INVALID_CREDENTIALS") askForUnlink()
            } catch (e: IOException) {
                unlinking = false
                say(UiError(UiError.NETWORK))
            } catch (e: Exception) {
                unlinking = false
                say(UiError(UiError.GENERIC))
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected() // CARD-21
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        applyVisibility()

        findPreference<Preference>("transient.app_version")?.summary =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            findPreference<Preference>("system")?.isEnabled = false
            findPreference<Preference>("system.disable_battery_optimizations")?.summary =
                getString(R.string.battery_optimization_is_not_supported_on_this_device)
        } else {
            findPreference<Preference>("system.disable_battery_optimizations")?.summary =
                if (isIgnoringBatteryOptimizations()) getString(R.string.disabled) else getString(R.string.enabled)
        }

        findPreference<Preference>("app.language")?.setOnPreferenceChangeListener { _, newValue ->
            LocaleHelper.setLocale(requireContext(), newValue as String)
            val intent =
                MainActivity.starter(requireContext(), MainActivity.TAB_INDEX_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            startActivity(intent)
            requireActivity().finish()
            false
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "encryption.passphrase") {
            (preference as EditTextPreference).setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
                it.selectAll()
            }
        }

        if (preference.key == "ping.interval_seconds"
            || preference.key == "logs.lifetime_days"
        ) {
            (preference as EditTextPreference).setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelectAllOnFocus(true)
                it.selectAll()
            }
        }

        super.onDisplayPreferenceDialog(preference)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "system.disable_battery_optimizations") {
            requestIgnoreBatteryOptimizations()
            return true
        }
        if (preference.key == SettingsVisibility.UNLINK) {
            askForUnlink()
            return true
        }

        return super.onPreferenceTreeClick(preference)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(
                requireContext(),
                getString(R.string.battery_optimization_is_not_supported_on_this_device),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.battery_optimization_already_disabled),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${requireContext().packageName}")
        startActivity(intent)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    companion object {
        private const val ACTION_UNLINK = "unlink"
        private const val UNLINK_PROMPT_TAG = "unlink_password"

        fun newInstance() = SettingsFragment()
    }
}