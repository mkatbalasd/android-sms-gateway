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
 * number. The country is preselected from the SIM country, else the network country, else the
 * device locale, and the customer can change it. The number itself is never prefilled.
 * All rules live in [IntlPhone].
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
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            countries.map { "${it.displayName(locale)} (+${it.dial})" }
        )
        select(IntlPhone.preselect(simIso(), networkIso(), locale.country))
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

    val country: PhoneCountry? get() = countries.getOrNull(spinner.selectedItemPosition)

    var nationalText: String
        get() = edit.text?.toString().orEmpty()
        set(value) {
            if (nationalText != value) edit.setText(value)
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
        if (index >= 0) spinner.setSelection(index, false)
    }

    private fun telephony(): TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun simIso(): String? = runCatching { telephony()?.simCountryIso }.getOrNull()

    private fun networkIso(): String? = runCatching { telephony()?.networkCountryIso }.getOrNull()
}
