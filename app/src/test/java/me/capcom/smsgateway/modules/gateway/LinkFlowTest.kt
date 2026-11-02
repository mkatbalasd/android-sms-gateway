package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LinkFlowTest {

    private val server = "https://smsgate.mnaseb.com/api/mobile/v1"
    private val encodedServer = "https%3A%2F%2Fsmsgate.mnaseb.com%2Fapi%2Fmobile%2Fv1"

    private fun payload(extra: String = "") = LinkPayload.parse(
        "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd$extra",
        server,
        0L
    )

    @Test
    fun validPayloadAloneDoesNotRegister() {
        val step = LinkFlow.onPayload(payload("&t=Clinic"), server)
        assertFalse(step is LinkFlow.Step.Register)
        assertEquals(LinkFlow.Step.Confirm("7wr3bd", "Clinic", "smsgate.mnaseb.com"), step)
    }

    @Test
    fun registrationNeedsTheConfirmTap() {
        val step = LinkFlow.onPayload(payload(), server) as LinkFlow.Step.Confirm
        assertEquals(LinkFlow.Step.Register("7wr3bd", null), LinkFlow.onConfirm(step))
    }

    @Test
    fun invalidPayloadShowsAnError() {
        val bad = LinkPayload.parse("mnaseb-sim://link?v=2&s=$encodedServer&c=7wr3bd", server, 0L)
        assertEquals(
            LinkFlow.Step.ShowError(LinkPayload.Error.UnsupportedVersion),
            LinkFlow.onPayload(bad, server)
        )
    }

    @Test
    fun noPayloadEverRegistersWithoutConfirmation() {
        val raws = listOf(
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&t=x&e=99",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=short",
            "garbage",
        )
        for (raw in raws) {
            val step = LinkFlow.onPayload(LinkPayload.parse(raw, server, 0L), server)
            assertTrue(raw, step is LinkFlow.Step.Confirm || step is LinkFlow.Step.ShowError)
        }
    }

    @Test
    fun manualCodeIsCleanedAndRegisters() {
        assertEquals(
            LinkFlow.Step.Register("7wr3bd", null),
            LinkFlow.onManualCode(" ${cp(0x202C)}7wr3bd${cp(0xFEFF)}")
        )
        assertEquals(LinkFlow.Step.CodeRequired, LinkFlow.onManualCode(" ${cp(0x2066, 0x2069)} "))
    }

    @Test
    fun labelIsSanitisedAndCapped() {
        assertEquals("Evil Corp", LinkFlow.displayLabel("${cp(0x202E)}Evil\n\tCorp${cp(0x2069)}"))
        assertEquals("a".repeat(LinkFlow.MAX_LABEL_DISPLAY), LinkFlow.displayLabel("a".repeat(60)))
        assertEquals(null, LinkFlow.displayLabel("${cp(0x200B)} "))
        assertEquals(null, LinkFlow.displayLabel(null))
    }
}
