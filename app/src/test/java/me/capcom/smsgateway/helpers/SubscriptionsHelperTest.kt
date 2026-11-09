package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

internal class SubscriptionsHelperTest {
    // (subscriptionId, simSlotIndex) as on the Huawei HED-L29
    private val active = listOf(1 to 0, 3 to 1)

    @Test
    fun activeSubscriptionIdIsKept() {
        assertEquals(1, SubscriptionsHelper.normalizeProviderSubId(1, active))
        assertEquals(3, SubscriptionsHelper.normalizeProviderSubId(3, active))
    }

    @Test
    fun slotIndexIsMappedToSubscriptionId() {
        assertEquals(1, SubscriptionsHelper.normalizeProviderSubId(0, active))
        assertEquals(6, SubscriptionsHelper.normalizeProviderSubId(1, listOf(5 to 0, 6 to 1)))
    }

    @Test
    fun unknownValueIsUnchanged() {
        assertEquals(7, SubscriptionsHelper.normalizeProviderSubId(7, active))
        assertEquals(2, SubscriptionsHelper.normalizeProviderSubId(2, active))
        assertEquals(0, SubscriptionsHelper.normalizeProviderSubId(0, emptyList()))
    }
}
