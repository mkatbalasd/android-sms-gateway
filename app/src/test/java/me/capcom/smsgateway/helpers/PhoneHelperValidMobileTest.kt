package me.capcom.smsgateway.helpers

import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class PhoneHelperValidMobileTest {

    @Test
    fun imsiShapedValueIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull("421030000000000"))
        // Arabic-Indic digits of the same shape
        assertNull(PhoneHelper.validMobileOrNull("٤٢١٠٣٠٠٠٠٠٠٠٠٠٠"))
    }

    @Test
    fun plusPrefixedImsiLikeValueIsRejected() {
        // '+' plus 15 digits: not a valid Yemeni (or any) number in libphonenumber metadata.
        assertNull(PhoneHelper.validMobileOrNull("+421030000000000"))
    }

    @Test
    fun blankIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull(null))
        assertNull(PhoneHelper.validMobileOrNull(""))
        assertNull(PhoneHelper.validMobileOrNull("   "))
    }

    @Test
    fun validYemeniMobileIsAccepted() {
        assertEquals("+967712345678", PhoneHelper.validMobileOrNull("+967 712 345 678"))
        assertEquals("+967712345678", PhoneHelper.validMobileOrNull("967712345678"))
        assertEquals("+967712345678", PhoneHelper.validMobileOrNull("00967712345678"))
    }

    @Test
    fun yemeniMobileRanges() {
        assertEquals("+967701468023", PhoneHelper.validMobileOrNull("+967 70 146 8023"))
        assertEquals("+967711468023", PhoneHelper.validMobileOrNull("+967 71 146 8023"))
        assertEquals("+967731468023", PhoneHelper.validMobileOrNull("+967 73 146 8023"))
        assertEquals("+967771468023", PhoneHelper.validMobileOrNull("+967 77 146 8023"))
        // 78: assert what the bundled libphonenumber metadata says (may vary by version).
        val util = PhoneNumberUtil.getInstance()
        val n = util.parse("+967781468023", null)
        val metadataMobile = util.isValidNumber(n) &&
            util.getNumberType(n) == PhoneNumberUtil.PhoneNumberType.MOBILE
        val expected = if (metadataMobile) "+967781468023" else null
        assertEquals(expected, PhoneHelper.validMobileOrNull("+967 78 146 8023"))
    }

    @Test
    fun yemeniFixedLineIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull("+967 1 468 023"))
    }

    @Test
    fun arabicIndicDigitsAreAccepted() {
        // +967 71 146 8023 written with Arabic-Indic digits
        assertEquals(
            "+967711468023",
            PhoneHelper.validMobileOrNull("+٩٦٧٧١١٤٦٨٠٢٣")
        )
    }

    @Test
    fun bareLocalNumberWithoutCountryCodeIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull("771468023"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull("abc"))
        assertNull(PhoneHelper.validMobileOrNull("12345"))
    }
}
