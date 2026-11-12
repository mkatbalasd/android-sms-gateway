package me.capcom.smsgateway.modules.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** rev-fork24-1 L6 and rev-fork24-2 L3/L5/L6: ending held messages while paused, and the repair reports. */
internal class SweepWhilePausedTest {

    private val now = 1_800_000_000_000L
    private val hourMs = 3_600_000L

    private class Plain : PauseStore {
        override var pausedAtMs: Long? = null
        override var resumedAtMs: Long? = null
        override var holdMaxMin: Int? = null
    }

    private class Queue(var stamps: List<PendingStamp>) : PendingStore {
        val failed = mutableListOf<Pair<String, String>>()
        override suspend fun pending() = stamps
        override suspend fun fail(id: String, reason: String) {
            failed.add(id to reason)
        }
    }

    private class Reports : StateReporter {
        val ids = mutableListOf<String>()
        override fun report(messageId: String) {
            ids.add(messageId)
        }
    }

    @Test
    fun aPausedPhoneFailsAndReportsWhatItHeldPastTheLimit() = runBlocking {
        val store = Plain().apply { pausedAtMs = now - 2 * hourMs }
        val queue = Queue(listOf(PendingStamp("old", now - 3 * hourMs), PendingStamp("fresh", now - 1_000)))
        val reports = Reports()

        val dropped = StaleSweep(queue, store, reports) { now }.sweepWhilePaused()

        assertEquals(1, dropped)
        assertEquals(listOf("old" to StaleSweep.REASON), queue.failed)
        assertEquals(listOf("old"), reports.ids)
    }

    @Test
    fun aPhoneThatIsNotPausedFailsNothingEvenWithAKeptOldPauseStamp() = runBlocking {
        // after a resume pausedAtMs is kept (the drop rule needs it): resumedAtMs is set
        val store = Plain().apply {
            pausedAtMs = now - 5 * hourMs
            resumedAtMs = now - 4 * hourMs
        }
        val queue = Queue(listOf(PendingStamp("m", now - 3 * hourMs)))
        val reports = Reports()

        assertEquals(0, StaleSweep(queue, store, reports) { now }.sweepWhilePaused())

        assertTrue(queue.failed.isEmpty())
        assertTrue(reports.ids.isEmpty())
    }

    @Test
    fun anUnreadablePauseStateIsNotSweptAndDoesNotThrow() = runBlocking {
        val unreadable = object : PauseStore {
            override var pausedAtMs: Long?
                get() = throw PauseStateUnreadableException("locked")
                set(value) {}
            override var resumedAtMs: Long?
                get() = throw PauseStateUnreadableException("locked")
                set(value) {}
            override var holdMaxMin: Int?
                get() = null
                set(value) {}
            override val isPaused: Boolean get() = throw PauseStateUnreadableException("locked")
        }
        val queue = Queue(listOf(PendingStamp("m", 0L)))

        assertEquals(0, StaleSweep(queue, unreadable, Reports()) { now }.sweepWhilePaused())
        assertTrue(queue.failed.isEmpty())
    }

    @Test
    fun aLostPauseFileThatIsRepairedAsPausedLetsTheSweepEndOldMessages() = runBlocking {
        val storage = FakeKeyValueStorage()
        val legacy = Plain().apply { resumedAtMs = now }
        val first = DevicePauseStore(storage, { legacy }, isUnlocked = { true })
        first.resumedAtMs = now
        storage.values.clear() // the DE file is lost
        val store = DevicePauseStore(storage, { legacy }, isUnlocked = { true })
        val queue = Queue(listOf(PendingStamp("old", now - 3 * hourMs), PendingStamp("fresh", now - 1_000)))

        val dropped = StaleSweep(queue, store, Reports()) { now }.sweepWhilePaused()

        assertEquals("only the message older than the limit ends", listOf("old"), queue.failed.map { it.first })
        assertEquals(1, dropped)
    }

    @Test
    fun aMarkThatKeepsFailingIsReportedOncePerProcess() {
        val storage = FakeKeyValueStorage()
        storage.set("state", DevicePauseStore.Stored(migrated = true))
        val failing = object : PauseStore {
            override var pausedAtMs: Long?
                get() = null
                set(value) = throw IllegalStateException("keystore")
            override var resumedAtMs: Long?
                get() = null
                set(value) {}
            override var holdMaxMin: Int?
                get() = null
                set(value) {}
        }
        val warnings = mutableListOf<String>()
        val store = DevicePauseStore(storage, { failing }, isUnlocked = { true }, onRepair = { warnings.add(it) })

        repeat(5) { store.isPaused }

        assertEquals(1, warnings.size)
    }

    @Test
    fun aFailingCommitOnResetThrowsAndTheUnlinkCatchesIt() {
        val failing = object : me.capcom.smsgateway.modules.settings.KeyValueStorage {
            override fun <T> set(key: String, value: T) = throw java.io.IOException("commit failed")
            override fun <T> get(key: String, typeOfT: java.lang.reflect.Type): T? = null
            override fun remove(key: String) {}
        }
        val store = DevicePauseStore(failing, { Plain() }, isUnlocked = { true })

        var thrown = false
        try {
            store.reset()
        } catch (e: java.io.IOException) {
            thrown = true
        }
        assertTrue("reset surfaces the failed commit", thrown)

        val source = me.capcom.smsgateway.modules.gateway.PauseGateSources.read("modules/account/AndroidLocalCleanup.kt")
        val at = source.indexOf("pauseStore.reset()")
        assertTrue(at >= 0)
        val before = source.substring(maxOf(0, at - 40), at)
        assertTrue("forgetLink guards the reset", before.contains("try"))
    }
}
