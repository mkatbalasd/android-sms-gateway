package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

internal class SubscriptionsHelperTest {

    // (subscriptionId, simSlotIndex): Huawei EMUI dual SIM, ids 1 and 3 for slots 0 and 1
    private val emui = listOf(1 to 0, 3 to 1)

    @Test
    fun normalizeProviderSubId_slotIndexNotASubscriptionId_isMappedToSlotSubscriptionId() {
        assertEquals(1, SubscriptionsHelper.normalizeProviderSubId(0, emui))
    }

    @Test
    fun normalizeProviderSubId_activeSubscriptionId_isKept() {
        assertEquals(3, SubscriptionsHelper.normalizeProviderSubId(3, emui))
        assertEquals(1, SubscriptionsHelper.normalizeProviderSubId(1, emui))
    }

    @Test
    fun normalizeProviderSubId_stockAndroidIdsEqualToSlots_areUnchanged() {
        val active = listOf(1 to 0, 2 to 1)
        assertEquals(1, SubscriptionsHelper.normalizeProviderSubId(1, active))
        assertEquals(2, SubscriptionsHelper.normalizeProviderSubId(2, active))
    }

    @Test
    fun normalizeProviderSubId_valueMatchingNeitherId_norSlot_isUnchanged() {
        assertEquals(7, SubscriptionsHelper.normalizeProviderSubId(7, emui))
        assertEquals(2, SubscriptionsHelper.normalizeProviderSubId(2, emui))
    }

    @Test
    fun normalizeProviderSubId_negativeValue_isUnchanged() {
        assertEquals(-1, SubscriptionsHelper.normalizeProviderSubId(-1, emui))
    }

    @Test
    fun normalizeProviderSubId_noActiveSubscriptions_isUnchanged() {
        assertEquals(0, SubscriptionsHelper.normalizeProviderSubId(0, emptyList()))
    }

    @Test
    fun normalizeProviderSubId_singleSimInSecondSlot_slotIndexIsMapped() {
        assertEquals(3, SubscriptionsHelper.normalizeProviderSubId(1, listOf(3 to 1)))
    }

    @Test
    fun normalizeProviderSubId_singleSimInFirstSlot_slotIndexIsMapped() {
        assertEquals(5, SubscriptionsHelper.normalizeProviderSubId(0, listOf(5 to 0)))
    }
}
