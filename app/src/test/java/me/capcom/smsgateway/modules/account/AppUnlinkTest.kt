package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.capcom.smsgateway.ui.account.FakeAppClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

internal class AppUnlinkTest {

    private class Recording : LocalCleanup {
        val steps = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()

        override suspend fun stopService() {
            steps.add("stop")
            started.complete(Unit)
            gate?.await()
        }

        override suspend fun purgeData() {
            steps.add("purge")
        }

        override suspend fun forgetLink() {
            steps.add("forget")
        }
    }

    private fun refused(code: String, status: Int) = AccountException(AccountError(code, status))

    @Test
    fun aSuccessfulUnlinkStopsThenPurgesThenForgetsInThatOrder() = runBlocking {
        val client = FakeAppClient()
        val local = Recording()

        AppUnlink(client, local).run("pw")

        assertEquals(listOf("pw"), client.unlinkCalls)
        assertEquals(listOf("stop", "purge", "forget"), local.steps)
    }

    @Test
    fun aWrongPasswordClearsNothing() = runBlocking {
        val client = FakeAppClient()
        client.unlinkAnswer = { throw refused("INVALID_CREDENTIALS", 403) }
        val local = Recording()

        try {
            AppUnlink(client, local).run("wrong")
            fail("expected an error")
        } catch (e: AccountException) {
            assertEquals("INVALID_CREDENTIALS", e.error.code)
        }
        assertTrue(local.steps.isEmpty())
    }

    @Test
    fun beingOfflineClearsNothing() = runBlocking {
        val client = FakeAppClient()
        client.unlinkAnswer = { throw IOException("offline") }
        val local = Recording()

        try {
            AppUnlink(client, local).run("pw")
            fail("expected an error")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(local.steps.isEmpty())
    }

    @Test
    fun aNonOwnerRefusalClearsNothing() = runBlocking {
        val client = FakeAppClient()
        client.unlinkAnswer = { throw refused("OWNER_REQUIRED", 403) }
        val local = Recording()

        try {
            AppUnlink(client, local).run("pw")
            fail("expected an error")
        } catch (e: AccountException) {
            // expected
        }
        assertTrue(local.steps.isEmpty())
    }

    @Test
    fun aSessionThePlatformNoLongerKnowsMeansAlreadyUnlinkedAndIsCleared() = runBlocking {
        val client = FakeAppClient()
        client.unlinkAnswer = { throw refused("APP_SESSION_INVALID", 401) }
        val local = Recording()

        AppUnlink(client, local).run("pw")

        assertEquals(listOf("stop", "purge", "forget"), local.steps)
    }

    @Test
    fun aPhoneThePlatformNoLongerListsIsAlsoCleared() = runBlocking {
        val client = FakeAppClient()
        client.unlinkAnswer = { throw refused("NOT_FOUND", 404) }
        val local = Recording()

        AppUnlink(client, local).run("pw")

        assertEquals(listOf("stop", "purge", "forget"), local.steps)
    }

    @Test
    fun theCleanupFinishesEvenIfTheScreenIsCancelledAfterThe200() = runBlocking {
        val client = FakeAppClient()
        val local = Recording()
        local.gate = CompletableDeferred()

        val job = launch { AppUnlink(client, local).run("pw") }
        local.started.await()
        job.cancel()
        local.gate!!.complete(Unit)
        job.join()

        assertEquals(listOf("stop", "purge", "forget"), local.steps)
    }
}
