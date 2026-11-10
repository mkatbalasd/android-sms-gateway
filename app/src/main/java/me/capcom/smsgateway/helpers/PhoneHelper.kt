package me.capcom.smsgateway.helpers

import com.google.i18n.phonenumbers.PhoneNumberUtil

object PhoneHelper {
    /**
     * Returns the E.164 form of [raw] only when it is a valid MOBILE number that already
     * carries its country code (leading '+', '00', or digits that parse as '+<digits>').
     * Android often reports the 15-digit IMSI as the SIM "number": a bare 15-digit string
     * without '+' is never accepted. Used at every site that prefills, shows or reports a
     * SIM number.
     */
    fun validMobileOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val sb = StringBuilder()
        var plus = false
        for ((i, c) in raw.trim().withIndex()) {
            when {
                c == '+' && i == 0 -> plus = true
                c in '0'..'9' -> sb.append(c)
                c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' ||
                    c == '‎' || c == '‏' -> Unit
                else -> return null
            }
        }
        var digits = sb.toString()
        if (!plus && digits.startsWith("00")) {
            plus = true
            digits = digits.drop(2)
        }
        if (!plus && digits.length == 15) return null
        if (digits.length < 8 || digits.length > 15 || digits.startsWith("0")) return null
        return try {
            val util = PhoneNumberUtil.getInstance()
            val number = util.parse("+$digits", null)
            val type = util.getNumberType(number)
            if (util.isValidNumber(number) &&
                (type == PhoneNumberUtil.PhoneNumberType.MOBILE ||
                    type == PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE)
            ) util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (e: Exception) {
            null
        }
    }

    fun filterPhoneNumber(phoneNumber: String, countryCode: String): String {
        val phoneUtil = PhoneNumberUtil.getInstance()
        val number = phoneUtil.parse(phoneNumber, countryCode.uppercase())
        if (!phoneUtil.isValidNumber(number)) {
            throw RuntimeException("Invalid phone number")
        }
        if (phoneUtil.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.MOBILE
            && phoneUtil.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE
        ) {
            throw RuntimeException("Invalid phone number type")
        }
        return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }
}