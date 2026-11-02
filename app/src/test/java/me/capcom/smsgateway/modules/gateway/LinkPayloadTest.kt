package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

internal class LinkPayloadTest {

    private val server = "https://smsgate.mnaseb.com/api/mobile/v1"
    private val encodedServer = "https%3A%2F%2Fsmsgate.mnaseb.com%2Fapi%2Fmobile%2Fv1"
    private val now = 1793660000L

    private fun parse(raw: String) = LinkPayload.parse(raw, server, now)

    private fun failed(error: LinkPayload.Error) = LinkPayload.Result.Failed(error)

    @Test
    fun documentedExampleIsAccepted() {
        // docs/design/ux/07-delivery.md §3.2 example
        val raw = "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd" +
            "&t=%D9%85%D8%B1%D9%83%D8%B2%20%D8%A7%D9%84%D8%B4%D9%81%D8%A7%D8%A1&e=1793664000"
        assertEquals(
            LinkPayload.Result.Ok(
                "7wr3bd",
                "مركز الشفاء",
                1793664000L
            ),
            parse(raw)
        )
    }

    @Test
    fun orderIsFreeAndOptionalFieldsMayBeAbsent() {
        assertEquals(
            LinkPayload.Result.Ok("7wr3bd", null, null),
            parse("mnaseb-sim://link?c=7wr3bd&s=$encodedServer&v=1")
        )
    }

    @Test
    fun codeCaseIsKept() {
        assertEquals(
            LinkPayload.Result.Ok("7WR3BD", null, null),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7WR3BD")
        )
    }

    @Test
    fun formatCharactersAroundAndInsideTheCodeAreRemoved() {
        assertEquals(
            LinkPayload.Result.Ok("7wr3bd", null, null),
            parse("‫ mnaseb-sim://link?v=1&s=$encodedServer&c=%E2%80%AC7wr3bd%E2%81%A9‬ ")
        )
    }

    @Test
    fun unknownAndReservedKeyParametersAreIgnored() {
        assertEquals(
            LinkPayload.Result.Ok("7wr3bd", null, null),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&k=c2VjcmV0&x=1")
        )
    }

    @Test
    fun plusIsNotASpace() {
        assertEquals(
            LinkPayload.Result.Ok("7wr3bd", "a+b", null),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&t=a+b")
        )
    }

    @Test
    fun otherVersionIsUnsupported() {
        assertEquals(
            failed(LinkPayload.Error.UnsupportedVersion),
            parse("mnaseb-sim://link?v=2&s=$encodedServer&c=7wr3bd")
        )
    }

    @Test
    fun plainHttpServerIsForbidden() {
        assertEquals(
            failed(LinkPayload.Error.ForbiddenServer),
            parse("mnaseb-sim://link?v=1&s=http%3A%2F%2Fsmsgate.mnaseb.com%2Fapi%2Fmobile%2Fv1&c=7wr3bd")
        )
    }

    @Test
    fun upstreamCloudIsForbidden() {
        assertEquals(
            failed(LinkPayload.Error.ForbiddenServer),
            parse("mnaseb-sim://link?v=1&s=https%3A%2F%2Fx.sms-gate.app%2Fapi%2Fmobile%2Fv1&c=7wr3bd")
        )
    }

    @Test
    fun otherServerIsForbidden() {
        assertEquals(
            failed(LinkPayload.Error.ForbiddenServer),
            parse("mnaseb-sim://link?v=1&s=https%3A%2F%2Fsms.clinic.local%2Fapi%2Fmobile%2Fv1&c=7wr3bd")
        )
    }

    @Test
    fun shortCodeIsInvalid() {
        assertEquals(
            failed(LinkPayload.Error.Invalid),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3b")
        )
    }

    @Test
    fun repeatedParameterIsInvalid() {
        assertEquals(
            failed(LinkPayload.Error.Invalid),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&c=8xs4ce")
        )
    }

    @Test
    fun pastExpiryIsRefused() {
        assertEquals(
            failed(LinkPayload.Error.Expired),
            parse("mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&e=${now - 1}")
        )
    }

    @Test
    fun malformedInputIsInvalid() {
        val bad = listOf(
            "https://smsgate.mnaseb.com/?v=1&c=7wr3bd",
            "mnaseb-sim://other?v=1&s=$encodedServer&c=7wr3bd",
            "mnaseb-sim://link?s=$encodedServer&c=7wr3bd",
            "mnaseb-sim://link?v=1&c=7wr3bd",
            "mnaseb-sim://link?v=1&s=$encodedServer",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&e=soon",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&t=%ZZ",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&t=%E2",
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&t=" + "a".repeat(41),
            "mnaseb-sim://link?v=1&s=$encodedServer&c=7wr3bd&x=" + "a".repeat(256),
        )
        for (raw in bad) {
            assertEquals(raw, failed(LinkPayload.Error.Invalid), parse(raw))
        }
    }
}
