package me.capcom.smsgateway.modules.logs

import me.capcom.smsgateway.modules.account.FakeKeyValueStorage
import org.junit.Assert.assertEquals
import org.junit.Test

/** CARD-24: logs are kept 7 days, on a fresh install and on one that held the old 30. */
class LogsSettingsRetentionTest {
    @Test
    fun aFreshInstallKeepsSevenDays() {
        assertEquals(7, LogsSettings(FakeKeyValueStorage()).lifetimeDays)
    }

    @Test
    fun anInstallThatHeldThirtyDaysMovesToSeven() {
        val storage = FakeKeyValueStorage()
        storage.set("lifetime_days", "30")
        storage.set("version", 1)

        assertEquals(7, LogsSettings(storage).lifetimeDays)
    }

    @Test
    fun anOwnerWhoSwitchedLogCleaningOffGetsSevenDaysToo() {
        val storage = FakeKeyValueStorage()
        storage.set("lifetime_days", "0")
        storage.set("version", 1)

        assertEquals(7, LogsSettings(storage).lifetimeDays)
    }

    @Test
    fun theMigrationRunsOnce() {
        val storage = FakeKeyValueStorage()
        LogsSettings(storage)
        storage.set("lifetime_days", "14") // chosen later by the owner

        assertEquals(14, LogsSettings(storage).lifetimeDays)
    }
}
