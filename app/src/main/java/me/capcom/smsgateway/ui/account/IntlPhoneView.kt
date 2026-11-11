package me.capcom.smsgateway.ui.account

import android.content.Context
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.account.IntlPhone
import me.capcom.smsgateway.modules.account.PhoneCountry
import java.util.Locale

/**
 * The international phone field: a country (name and dial code) selector plus the national
 * number. The caller preselects the country ([preselect]); the customer can change it. Until a
 * country is known or chosen the selector shows a "Choose country" placeholder and [country]
 * is null, so a number is never read against a country nobody chose. The number itself is
 * never prefilled. All rules live in [IntlPhone].
 */
class IntlPhoneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val spinner: Spinner
    private val layout: TextInputLayout
    private val edit: TextInputEditText
    private val countries: List<PhoneCountry>

    /** Called after every change of the country or the number. */
    var onChanged: (() -> Unit)? = null

    var optional: Boolean = false

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        LayoutInflater.from(context).inflate(R.layout.view_intl_phone, this, true)
        spinner = findViewById(R.id.spinnerIntlCountry)
        layout = findViewById(R.id.layoutIntlNational)
        edit = findViewById(R.id.editIntlNational)

        val locale = Locale.getDefault()
        countries = IntlPhone.countries(locale)
        // Position 0 is the placeholder; countries start at 1.
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(context.getString(R.string.intl_phone_choose_country)) +
                countries.map { "${it.displayName(locale)} (+${it.dial})" }
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                layout.error = null
                onChanged?.invoke()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        edit.doAfterTextChanged {
            layout.error = null
            onChanged?.invoke()
        }
    }

    /** The chosen country, or null while the placeholder is showing. */
    val country: PhoneCountry? get() = countries.getOrNull(spinner.selectedItemPosition - 1)

    var nationalText: String
        get() = edit.text?.toString().orEmpty()
        set(value) {
            if (nationalText != value) edit.setText(value)
        }

    /**
     * Preselects from [simIso] (the SIM's own country, for a slot row: that slot's SIM), else
     * the network country, else the device locale. Leaves the placeholder when none is known.
     */
    fun preselect(simIso: String?) {
        select(IntlPhone.preselect(simIso, networkIso(), Locale.getDefault().country))
    }

    /** Preselects from the phone's default SIM (sign-up has no slot). */
    fun preselectDefault() = preselect(runCatching { telephony()?.simCountryIso }.getOrNull())

    /** Restores a country and national number (after rotation), without calling [onChanged]. */
    fun setValue(country: PhoneCountry?, national: String) {
        select(country)
        nationalText = national
    }

    /** The field's value by its rule: required (sign-up) or [optional] (verify screen). */
    fun result(): IntlPhone.Result =
        if (optional) IntlPhone.optional(country, nationalText) else IntlPhone.required(country, nationalText)

    fun setHint(text: CharSequence?) {
        layout.hint = text
    }

    fun setError(text: CharSequence?) {
        layout.error = text
    }

    fun setNumberEnabled(enabled: Boolean) {
        edit.isEnabled = enabled
        spinner.isEnabled = enabled
    }

    private fun select(country: PhoneCountry?) {
        val index = countries.indexOfFirst { it.iso == country?.iso }
        spinner.setSelection(if (index >= 0) index + 1 else 0, false)
    }

    private fun telephony(): TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun networkIso(): String? = runCatching { telephony()?.networkCountryIso }.getOrNull()
}
