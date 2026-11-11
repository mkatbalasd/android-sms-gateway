package me.capcom.smsgateway.ui.settings

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import me.capcom.smsgateway.R

class WebhooksSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected() // CARD-21
        setPreferencesFromResource(R.xml.webhooks_preferences, rootKey)

        // The signing key must never be rendered in the list: masked summary only.
        findPreference<EditTextPreference>("webhooks.signing_key")?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { pref ->
                SigningKeyMask.summary(
                    pref.text,
                    getString(R.string.signing_key_set),
                    getString(R.string.not_set)
                )
            }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == "webhooks.retry_count") {
            (preference as EditTextPreference).setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelectAllOnFocus(true)
                it.selectAll()
            }
        }

        if (preference.key == "webhooks.signing_key") {
            (preference as EditTextPreference).setOnBindEditTextListener {
                it.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
                it.selectAll()
            }
        }

        super.onDisplayPreferenceDialog(preference)
    }
}