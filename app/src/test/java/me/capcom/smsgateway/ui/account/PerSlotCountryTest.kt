package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.modules.account.IntlPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review fixes of the international phone field: per-slot country, required phase, restore. */
internal class PerSlotCountryTest {

    private val sims = listOf(
        SimSlot(1, "Yemen Mobile", null, "ye"),
        SimSlot(2, "STC", null, "sa"),
    )

    @Test
    fun eachRowCarriesItsOwnSimCountryAndPreselectsFromIt() {
        val rows = NumbersState.rows(sims, emptyList())

        assertEquals(listOf("ye", "sa"), rows.map { it.countryIso })
        // The device-wide network and locale never override a slot's own SIM country.
        val chosen = rows.map { IntlPhone.preselect(it.countryIso, "EG", "US")?.iso }
        assertEquals(listOf("YE", "SA"), chosen)
    }

    @Test
    fun aSlotWithoutACountryFallsBackToTheNetworkThenTheLocale() {
        val row = NumbersState.rows(listOf(SimSlot(1, null, null, null)), emptyList()).single()
        assertNull(row.countryIso)
        assertEquals("EG", IntlPhone.preselect(row.countryIso, "eg", "US")?.iso)
        assertEquals("US", IntlPhone.preselect(row.countryIso, "", "US")?.iso)
    }

    @Test
    fun aRequiredNumberBlocksTheActionUntilValid() {
        val needs = NumberRow(1, null, null, phase = RowPhase.NEEDS_NUMBER)
        assertFalse(NumbersState.canVerify(listOf(needs)))
        assertTrue(NumbersState.canVerify(listOf(needs.copy(typed = "+967712345678"))))
        // An optional (idle) row never waits for a number.
        assertTrue(NumbersState.canVerify(listOf(NumberRow(1, null, null))))
    }

    @Test
    fun anE164NumberIsSplitBackIntoItsCountryAndNationalDigits() {
        val (country, national) = IntlPhone.split("+967712345678")!!
        assertEquals("YE", country.iso)
        assertEquals(967, country.dial)
        assertEquals("712345678", national)
        assertEquals("+967712345678", IntlPhone.e164(country, national))
        assertNull(IntlPhone.split(""))
        assertNull(IntlPhone.split(null))
    }
}
