package me.capcom.smsgateway.modules.retention

import me.capcom.smsgateway.modules.account.AppLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-24: with a fetched list only SMS on a slot holding a non-retired line of this phone are
 * kept (ACTIVE and PENDING); with no list a customer phone fails closed and only the explicit
 * platform-phone role or an open number-proof window lets an SMS through.
 */
class IncomingSlotFilterTest {
    private fun line(slot: Int?, status: String? = "ACTIVE", verifiedAt: String? = "2026-11-10T10:00:00Z") =
        AppLine(id = "l", slot = slot, msisdnMasked = null, status = status, method = null,
            strength = null, verifiedAt = verifiedAt)

    @Test
    fun aSlotHoldingALineIsAccepted() {
        assertTrue(IncomingSlotFilter.accept(1, setOf(1)))
        assertTrue(IncomingSlotFilter.accept(2, setOf(1, 2)))
    }

    @Test
    fun theOwnersPersonalSlotIsDropped() {
        assertFalse(IncomingSlotFilter.accept(2, setOf(1)))
    }

    @Test
    fun aMessageWithNoKnownSlotIsDroppedWhenAListExists() {
        assertFalse(IncomingSlotFilter.accept(null, setOf(1)))
    }

    @Test
    fun aFetchedListWithNoLineKeepsNothing() {
        assertFalse(IncomingSlotFilter.accept(1, emptySet()))
    }

    // Owner decision 2026-11-19: a customer phone FAILS CLOSED. This replaces the fail-open test
    // of rev-34-2 (withNoListEverFetchedEverythingIsAcceptedAsBefore).
    @Test
    fun aCustomerPhoneThatNeverGotAListKeepsNothing() {
        assertFalse(IncomingSlotFilter.accept(1, null))
        assertFalse(IncomingSlotFilter.accept(2, null))
        assertFalse(IncomingSlotFilter.accept(null, null))
        assertFalse(IncomingSlotFilter.accept(1, null, platformPhone = false, proofSlots = emptySet()))
    }

    @Test
    fun theExplicitPlatformPhoneStaysOpenWhileItHasNoList() {
        assertTrue(IncomingSlotFilter.accept(1, null, platformPhone = true))
        assertTrue(IncomingSlotFilter.accept(2, null, platformPhone = true))
        assertTrue(IncomingSlotFilter.accept(null, null, platformPhone = true))
    }

    @Test
    fun theRoleNeverOpensTheFilterOnceAListExists() {
        assertFalse(IncomingSlotFilter.accept(2, setOf(1), platformPhone = true))
        assertTrue(IncomingSlotFilter.accept(1, setOf(1), platformPhone = true))
    }

    @Test
    fun anOpenProofWindowAcceptsItsSlotEvenWithNoList() {
        assertTrue(IncomingSlotFilter.accept(2, null, proofSlots = setOf(2)))
        assertFalse(IncomingSlotFilter.accept(1, null, proofSlots = setOf(2)))
        assertFalse(IncomingSlotFilter.accept(null, null, proofSlots = setOf(2)))
    }

    @Test
    fun aProofWindowAlsoPassesNextToAList() {
        assertTrue(IncomingSlotFilter.accept(2, setOf(1), proofSlots = setOf(2)))
        assertFalse(IncomingSlotFilter.accept(3, setOf(1), proofSlots = setOf(2)))
    }

    @Test
    fun activeAndPendingClaimsCountButRetiredDoesNot() {
        val slots = IncomingSlotFilter.activeSlots(
            listOf(
                line(1),
                line(2, status = "PENDING", verifiedAt = null),
                line(3, status = "RETIRED"),
                line(4, status = "retired"),
                line(null),
            )
        )
        assertEquals(setOf(1, 2), slots)
    }

    @Test
    fun aPendingClaimSlotAcceptsItsProofReplies() {
        val slots = IncomingSlotFilter.activeSlots(listOf(line(2, status = "PENDING", verifiedAt = null)))
        assertTrue(IncomingSlotFilter.accept(2, slots))
    }

    @Test
    fun startingAProofAddsTheSlotToAKnownList() {
        assertEquals(setOf(1, 2), ActiveLineStore.withSlot(setOf(1), 2))
    }

    // The list itself stays "never received" (the proof window, not a list, opens the slot).
    @Test
    fun startingAProofDoesNotTurnAnUnknownListIntoARestrictiveOne() {
        assertNull(ActiveLineStore.withSlot(null, 2))
    }

    @Test
    fun theStoredFormRoundTripsAndNoValueMeansUnknown() {
        assertEquals(setOf(1, 2), ActiveLineStore.parse(ActiveLineStore.encode(setOf(2, 1))))
        assertEquals(emptySet<Int>(), ActiveLineStore.parse(ActiveLineStore.encode(emptySet())))
        assertNull(ActiveLineStore.parse(null))
        assertEquals(setOf(1), ActiveLineStore.decode("1,x, ,"))
    }
}
