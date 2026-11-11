package me.capcom.smsgateway.modules.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

internal class IntlPhoneTest {

    private val yemen = PhoneCountry("YE", 967)
    private val saudi = PhoneCountry("SA", 966)

    // region E.164 assembly

    @Test
    fun aNationalNumberBecomesE164WithTheSelectedDialCode() {
        assertEquals("+967712345678", IntlPhone.e164(yemen, "712345678"))
        assertEquals("+967712345678", IntlPhone.e164(yemen, "71 234 5678"))
        assertEquals("+966512345678", IntlPhone.e164(saudi, "512345678"))
    }

    @Test
    fun aTrunkZeroIsDroppedAndArabicIndicDigitsAreAccepted() {
        assertEquals("+967712345678", IntlPhone.e164(yemen, "0712345678"))
        assertEquals("+967712345678", IntlPhone.e164(yemen, "٧١٢٣٤٥٦٧٨"))
        assertEquals("+966512345678", IntlPhone.e164(saudi, "‎0512345678"))
    }

    @Test
    fun aFullInternationalNumberIsReadAsTypedWhateverTheSelectorSays() {
        assertEquals("+967712345678", IntlPhone.e164(saudi, "+967 712 345 678"))
        assertEquals("+967712345678", IntlPhone.e164(saudi, "00967712345678"))
        assertEquals("+967712345678", IntlPhone.e164(null, "+967712345678"))
    }

    @Test
    fun withoutACountryANationalNumberIsRefusedNotGuessed() {
        assertNull(IntlPhone.e164(null, "712345678"))
    }

    @Test
    fun junkAndLandlinesAreRefused() {
        assertNull(IntlPhone.e164(yemen, "abc"))
        assertNull(IntlPhone.e164(yemen, "12345"))
        assertNull(IntlPhone.e164(yemen, "1234567")) // a Sana'a landline shape, not mobile
    }

    // endregion

    // region IMSI

    @Test
    fun anImsiShapedInputIsRejectedWithOrWithoutACountry() {
        val imsi = "421030000000000"
        assertNull(IntlPhone.e164(yemen, imsi))
        assertNull(IntlPhone.e164(null, imsi))
        assertNull(IntlPhone.e164(yemen, "٤٢١٠٣٠٠٠٠٠٠٠٠٠٠"))
        assertNull(IntlPhone.e164(yemen, "+$imsi"))
        assertNull(IntlPhone.e164(yemen, "00$imsi"))
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.required(yemen, imsi))
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.optional(yemen, imsi))
    }

    // endregion

    // region preselection

    @Test
    fun theSimCountryWinsThenTheNetworkThenTheLocale() {
        assertEquals("YE", IntlPhone.preselect("ye", "sa", "EG")?.iso)
        assertEquals(967, IntlPhone.preselect("ye", "sa", "EG")?.dial)
        assertEquals("SA", IntlPhone.preselect("", "sa", "EG")?.iso)
        assertEquals("SA", IntlPhone.preselect(null, " SA ", "EG")?.iso)
        assertEquals("EG", IntlPhone.preselect(null, null, "EG")?.iso)
        assertEquals("EG", IntlPhone.preselect("", "  ", "eg")?.iso)
    }

    @Test
    fun anUnknownCodeIsSkippedAndNothingKnownGivesNull() {
        assertEquals("SA", IntlPhone.preselect("zz", "SA", null)?.iso)
        assertEquals("EG", IntlPhone.preselect("zz", "xx", "EG")?.iso)
        assertNull(IntlPhone.preselect("zz", null, ""))
        assertNull(IntlPhone.preselect(null, null, null))
    }

    // endregion

    // region optional and required

    @Test
    fun onTheVerifyScreenTheFieldIsOptional() {
        assertEquals(IntlPhone.Result.Empty, IntlPhone.optional(yemen, ""))
        assertEquals(IntlPhone.Result.Empty, IntlPhone.optional(yemen, "  ‏ "))
        assertEquals(IntlPhone.Result.Valid("+967712345678"), IntlPhone.optional(yemen, "712345678"))
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.optional(yemen, "71"))
    }

    @Test
    fun onSignUpTheFieldIsRequired() {
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.required(yemen, ""))
        assertEquals(IntlPhone.Result.Valid("+967712345678"), IntlPhone.required(yemen, "712345678"))
    }

    // endregion

    @Test
    fun theCountryListHasEveryDialableCountryOnceAndSelectsYemen() {
        val list = IntlPhone.countries(Locale.ENGLISH)
        assertTrue(list.size > 150)
        assertEquals(list.size, list.map { it.iso }.toSet().size)
        assertTrue(list.all { it.dial > 0 })
        assertNotNull(list.firstOrNull { it.iso == "YE" && it.dial == 967 })
    }
}
