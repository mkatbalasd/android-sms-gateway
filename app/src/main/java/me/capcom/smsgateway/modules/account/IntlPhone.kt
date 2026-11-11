package me.capcom.smsgateway.modules.account

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import me.capcom.smsgateway.helpers.PhoneHelper
import me.capcom.smsgateway.modules.gateway.LoginCodeSanitizer
import java.util.Locale

/** One entry of the country selector. [dial] is the calling code without the plus. */
data class PhoneCountry(val iso: String, val dial: Int) {
    /** Name in the UI language (Arabic when the phone is Arabic); the ISO code when unknown. */
    fun displayName(locale: Locale): String =
        Locale("", iso).getDisplayCountry(locale).ifBlank { iso }
}

/**
 * The international phone field (CARD-18 owner decision 2026-11-10): a country with its dial
 * code plus the national number, assembled into E.164. Pure logic, no Android types.
 *
 * The number is never read from the SIM: only the country is preselected. A SIM-reported
 * number or an IMSI is never put in the field; input shaped like an IMSI (15 digits without
 * a plus) is refused by [PhoneHelper.validMobileOrNull] like everywhere else in the app.
 */
object IntlPhone {

    sealed class Result {
        /** Nothing typed. On the verify screen this is allowed: the carrier-asserted proof decides. */
        object Empty : Result()
        data class Valid(val e164: String) : Result()
        object Invalid : Result()
    }

    /** Every country libphonenumber can dial, sorted by name in [locale], then by code. */
    fun countries(locale: Locale): List<PhoneCountry> {
        val util = PhoneNumberUtil.getInstance()
        return util.supportedRegions
            .map { PhoneCountry(it, util.getCountryCodeForRegion(it)) }
            .filter { it.dial > 0 }
            .sortedWith(compareBy({ it.displayName(locale) }, { it.iso }))
    }

    /**
     * The country to preselect: the SIM's country, else the network's, else the device locale.
     * (The owner's "IP country" fallback needs a geo source the app does not have.)
     * A value that is not a country libphonenumber knows is skipped. Null when none is known.
     */
    fun preselect(simIso: String?, networkIso: String?, localeIso: String?): PhoneCountry? {
        val util = PhoneNumberUtil.getInstance()
        for (candidate in listOf(simIso, networkIso, localeIso)) {
            val iso = candidate?.trim()?.uppercase(Locale.ROOT)
            if (iso.isNullOrEmpty() || iso !in util.supportedRegions) continue
            val dial = util.getCountryCodeForRegion(iso)
            if (dial > 0) return PhoneCountry(iso, dial)
        }
        return null
    }

    /**
     * E.164 from the selected [country] and what was typed. A full international number
     * (leading + or 00) is read as typed, whatever the selector says. Otherwise the digits are
     * the national number: a trunk zero is dropped by libphonenumber's rules for the country.
     * Only a valid MOBILE number is accepted, so an IMSI never gets through.
     */
    fun e164(country: PhoneCountry?, national: String): String? {
        val typed = LoginCodeSanitizer.clean(national).trim()
        val ascii = OtpDigits.ascii(typed).filter { it != '-' && it != '(' && it != ')' && it != '.' && it != ' ' }
        if (ascii.isEmpty()) return null
        if (ascii.startsWith("+") || ascii.startsWith("00")) return PhoneHelper.validMobileOrNull(ascii)
        if (country == null || ascii.any { it !in '0'..'9' }) return null
        // Reject IMSI-shaped input outright (15 digits, with or without the country code).
        if (ascii.length >= 15) return null
        val parsed = try {
            PhoneNumberUtil.getInstance().parse(ascii, country.iso)
        } catch (e: NumberParseException) {
            return null
        }
        if (parsed.countryCode != country.dial) return null
        val significant = PhoneNumberUtil.getInstance().getNationalSignificantNumber(parsed)
        return PhoneHelper.validMobileOrNull("+" + country.dial + significant)
    }

    /** An E.164 number back into its country and national digits, to restore the field. Null if unknown. */
    fun split(e164: String?): Pair<PhoneCountry, String>? {
        if (e164.isNullOrBlank()) return null
        val util = PhoneNumberUtil.getInstance()
        return try {
            val parsed = util.parse(e164, null)
            val iso = util.getRegionCodeForNumber(parsed) ?: return null
            PhoneCountry(iso, parsed.countryCode) to util.getNationalSignificantNumber(parsed)
        } catch (e: NumberParseException) {
            null
        }
    }

    /** For a REQUIRED field (sign-up): empty is invalid. */
    fun required(country: PhoneCountry?, national: String): Result =
        e164(country, national)?.let { Result.Valid(it) } ?: Result.Invalid

    /** For the OPTIONAL field of the verify screen: empty is fine and means "no hint". */
    fun optional(country: PhoneCountry?, national: String): Result {
        if (LoginCodeSanitizer.clean(national).isBlank()) return Result.Empty
        return required(country, national)
    }
}
