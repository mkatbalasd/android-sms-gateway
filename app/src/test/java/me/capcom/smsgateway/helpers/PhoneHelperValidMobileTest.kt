package me.capcom.smsgateway.helpers

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
    fun garbageIsRejected() {
        assertNull(PhoneHelper.validMobileOrNull("abc"))
        assertNull(PhoneHelper.validMobileOrNull("12345"))
    }
}
