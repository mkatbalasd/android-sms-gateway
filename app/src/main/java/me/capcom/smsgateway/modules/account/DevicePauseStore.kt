package me.capcom.smsgateway.modules.account

import android.content.Context
import android.os.Build
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.PreferencesStorage
import me.capcom.smsgateway.modules.settings.get

/**
 * CARD-21 round 7: the pause state in device-protected (DE) storage, so every gate can read
 * it before the first unlock. The credential-encrypted [AccountSettings] cannot be read then.
 *
 * One JSON document: the three values of [PauseStore] and a `migrated` flag. The flag is set
 * once, by copying the values the credential-encrypted account store held before this
 * release; until it is set (a phone that has not been unlocked since the update) the pause
 * state is UNKNOWN and every read throws [PauseStateUnreadableException]. Callers treat an
 * exception as "paused" (see [PauseGate], [enqueueUnlessHeld] in the gateway module): the
 * pause fails closed, never open.
 *
 * Holds no secret: three timestamps/numbers. Excluded from backup and device transfer by the
 * extraction rules (domain `device_sharedpref`).
 */
class DevicePauseStore(
    private val storage: KeyValueStorage,
    /** The credential-encrypted source of the one-time copy; only called while unlocked. */
    private val legacy: () -> PauseStore,
    private val isUnlocked: () -> Boolean,
    /** Called with a short reason (no personal data) when the state had to be repaired. */
    private val onRepair: (String) -> Unit = {},
) : PauseStore {

    data class Stored(
        val pausedAtMs: Long? = null,
        val resumedAtMs: Long? = null,
        val holdMaxMin: Int? = null,
        val migrated: Boolean? = null,
    )

    @Synchronized
    private fun read(): Stored {
        val stored = try {
            storage.get<Stored>(KEY)
        } catch (e: Exception) {
            // A corrupt value (the store could not parse it). Never "not paused": it is
            // rewritten as a clean PAUSED state, so the reads work again and the resume
            // (which only sets resumedAtMs) recovers the phone without an unlink. pausedAtMs=0
            // makes the resume sweep and the pull drop rule judge a message by its own age.
            return rewriteCorrupt()
        }
        if (stored?.migrated == true) return stored

        if (!isUnlocked()) {
            // Not copied yet and the old copy cannot be read: unknown, never "not paused".
            throw PauseStateUnreadableException("pause state is not available before the first unlock")
        }
        val old = legacy()
        val copied = Stored(old.pausedAtMs, old.resumedAtMs, old.holdMaxMin, migrated = true)
        storage.set(KEY, copied)
        if (old.pausedAtMs == LEGACY_MARK && old.resumedAtMs == null) {
            // V2-L25: the copy was done before and the file is gone now. The old copy was
            // marked paused then, so this reads as paused, not as the stale pre-update value.
            report("the pause file was lost; the state was restored as paused")
        } else {
            // Mark the old copy paused: a lost DE file (or a downgrade to a build that reads
            // these fields) must fail closed, never reopen the gate from a stale value.
            try {
                old.pausedAtMs = LEGACY_MARK
                old.resumedAtMs = null
            } catch (e: Exception) {
                report("the old pause copy could not be marked: ${e.javaClass.simpleName}")
            }
        }
        return copied
    }

    private fun rewriteCorrupt(): Stored {
        val clean = Stored(pausedAtMs = 0L, resumedAtMs = null, holdMaxMin = null, migrated = true)
        try {
            storage.set(KEY, clean)
        } catch (e: Exception) {
            // still answer paused for this read
        }
        report("the pause value was unreadable; the state was reset to paused")
        return clean
    }

    private fun report(reason: String) {
        try {
            onRepair(reason)
        } catch (e: Exception) {
            // logging must never break the gate
        }
    }

    @Synchronized
    private fun update(change: (Stored) -> Stored) {
        storage.set(KEY, change(read()))
    }

    override var pausedAtMs: Long?
        get() = read().pausedAtMs
        set(value) = update { it.copy(pausedAtMs = value) }

    override var resumedAtMs: Long?
        get() = read().resumedAtMs
        set(value) = update { it.copy(resumedAtMs = value) }

    override var holdMaxMin: Int?
        get() = read().holdMaxMin
        set(value) = update { it.copy(holdMaxMin = value) }

    /** One snapshot, so the two timestamps are read together. */
    override val isPaused: Boolean
        get() = read().let { it.pausedAtMs != null && it.resumedAtMs == null }

    /**
     * Forgets the pause with the account («فصل هذا الهاتف»). Written as migrated, so the old
     * credential-encrypted copy (also cleared by [AccountSettings.reset]) is never copied back.
     */
    @Synchronized
    fun reset() {
        storage.set(KEY, Stored(migrated = true))
    }

    companion object {
        private const val FILE = "mnaseb_pause"
        private const val PREFIX = "pause"
        private const val KEY = "state"

        /** pausedAtMs written to the old credential-encrypted copy after the one-time copy: "paused". */
        const val LEGACY_MARK = 1L

        /** A fixed device-protected file, whatever the outcome of the other storage copy was. */
        fun create(
            context: Context,
            onRepair: (String) -> Unit = {},
            legacy: () -> PauseStore,
        ): DevicePauseStore {
            val app = context.applicationContext
            val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                app.createDeviceProtectedStorageContext()
            } else {
                app
            }
            return DevicePauseStore(
                // commit(): the pause must survive a kill right after it is set
                PreferencesStorage(
                    deContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                    PREFIX,
                    synchronous = true,
                ),
                legacy,
                isUnlocked = { DeviceStorage.isUnlocked(app) },
                onRepair = onRepair,
            )
        }
    }
}

/** The pause state cannot be read (not copied yet and locked, or a storage fault). Treated as paused. */
class PauseStateUnreadableException(message: String) : IllegalStateException(message)
