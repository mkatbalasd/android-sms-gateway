package me.capcom.smsgateway.modules.retention

import me.capcom.smsgateway.modules.account.AppLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CARD-24: only SMS on a slot holding an ACTIVE line of the account are kept and forwarded. */
class IncomingSlotFilterTest {
    private fun line(slot: Int?, status: String? = "ACTIVE", verifiedAt: String? = "2026-11-10T10:00:00Z") =
        AppLine(id = "l", slot = slot, msisdnMasked = null, status = status, method = null,
            strength = null, verifiedAt = verifiedAt)

    @Test
    fun aSlotHoldingAnActiveLineIsAccepted() {
        assertTrue(IncomingSlotFilter.accept(1, setOf(1)))
        assertTrue(IncomingSlotFilter.accept(2, setOf(1, 2)))
    }

    @Test
    fun theOwnersPersonalSlotIsDropped() {
        assertFalse(IncomingSlotFilter.accept(2, setOf(1)))
    }

    @Test
    fun aMessageWithNoKnownSlotIsDropped() {
        assertFalse(IncomingSlotFilter.accept(null, setOf(1)))
    }

    @Test
    fun withNoActiveLineKnownNothingIsKept() {
        assertFalse(IncomingSlotFilter.accept(1, emptySet()))
    }

    @Test
    fun onlyVerifiedActiveLinesCountAsActive() {
        val slots = IncomingSlotFilter.activeSlots(
            listOf(
                line(1),
                line(2, status = "PENDING"),
                line(3, verifiedAt = null),
                line(null),
            )
        )
        assertEquals(setOf(1), slots)
    }

    @Test
    fun theStoredFormRoundTrips() {
        assertEquals(setOf(1, 2), ActiveLineStore.decode(ActiveLineStore.encode(setOf(2, 1))))
        assertEquals(emptySet<Int>(), ActiveLineStore.decode(ActiveLineStore.encode(emptySet())))
        assertEquals(emptySet<Int>(), ActiveLineStore.decode(null))
        assertEquals(setOf(1), ActiveLineStore.decode("1,x, ,"))
    }
}
