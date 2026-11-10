package me.capcom.smsgateway.ui.account

import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.modules.account.AccountError
import me.capcom.smsgateway.modules.account.AccountException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TestMessagePollingTest {

    private class Clock {
        var now = 1_000L
    }

    private suspend fun run(client: FakeAppClient, clock: Clock = Clock()): TestOutcome =
        TestMessagePolling.run(
            client = client,
            idempotencyKey = "key-12345678",
            recipientId = "r1",
            text = "hello",
            delayMs = { clock.now += it },
            nowMs = { clock.now },
        )

    @Test
    fun itPollsEveryTwoSecondsUntilTheMessageArrivedAndCountsTheSeconds() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { view("QUEUED") }
        client.messageAnswer = { call -> view(if (call < 3) "DISPATCHED" else "SENT") }

        val outcome = run(client)

        // Four polls, two seconds apart: the fourth answer says SENT, eight seconds after the tap.
        assertEquals(TestOutcome.Arrived(8), outcome)
        assertEquals(4, client.messageCalls)
        assertEquals(listOf(Triple("key-12345678", "r1", "hello")), client.sent)
    }

    @Test
    fun aMessageAlreadySentIsReportedWithoutPolling() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { view("DELIVERED") }

        assertEquals(TestOutcome.Arrived(0), run(client))
        assertEquals(0, client.messageCalls)
    }

    @Test
    fun aFinalFailureIsReportedWithItsStatus() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { view("QUEUED") }
        client.messageAnswer = { view("FAILED") }

        assertEquals(TestOutcome.Failed("FAILED"), run(client))
    }

    @Test
    fun anExpiredAndAnUnknownOutcomeAreDistinct() = runBlocking {
        val expired = FakeAppClient()
        expired.sendAnswer = { view("QUEUED") }
        expired.messageAnswer = { view("EXPIRED") }
        assertEquals(TestOutcome.Failed("EXPIRED"), run(expired))

        val unknown = FakeAppClient()
        unknown.sendAnswer = { view("ACCEPTANCE_UNKNOWN") }
        assertEquals(TestOutcome.Failed("ACCEPTANCE_UNKNOWN"), run(unknown))
    }

    @Test
    fun aMessageStillOnItsWayAfterSixtySecondsStopsPolling() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { view("QUEUED") }
        client.messageAnswer = { view("DISPATCHED") }
        val clock = Clock()

        val outcome = run(client, clock)

        assertEquals(TestOutcome.StillOnItsWay, outcome)
        assertEquals(30, client.messageCalls)
        assertEquals(1_000L + 60_000L, clock.now)
    }

    @Test
    fun theDailyCapIsARefusalWithItsCodeAndSendsNothingElse() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { throw AccountException(AccountError("SANDBOX_DAILY_LIMIT", 429)) }

        val outcome = run(client)

        assertEquals(TestOutcome.Refused(TestMessagePolling.CAP_CODE), outcome)
        assertEquals(0, client.messageCalls)
    }

    @Test
    fun aDroppedPollIsRetriedOnTheNextTick() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { view("QUEUED") }
        client.messageAnswer = { call ->
            if (call == 0) throw java.io.IOException("offline") else view("SENT")
        }

        assertEquals(TestOutcome.Arrived(4), run(client))
    }

    @Test
    fun noNetworkAtSendIsARefusalNotACrash() = runBlocking {
        val client = FakeAppClient()
        client.sendAnswer = { throw java.io.IOException("offline") }

        assertEquals(TestOutcome.Refused(UiError.NETWORK), run(client))
    }

    @Test
    fun theTextIsLimitedToWhatThePlatformAccepts() {
        assertTrue(TestMessagePolling.textValid("a"))
        assertTrue(TestMessagePolling.textValid("a".repeat(300)))
        assertFalse(TestMessagePolling.textValid("a".repeat(301)))
        assertFalse(TestMessagePolling.textValid("   "))
        assertEquals(300, TestMessagePolling.defaultText("p", "x".repeat(400)).length)
        assertEquals("Test — Clinic", TestMessagePolling.defaultText("Test", " Clinic "))
        assertEquals("Test", TestMessagePolling.defaultText("Test", null))
    }
}
