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
 * Holds no secret: three timestamps/numbers. Kept out of backup and device transfer by
 * `android:allowBackup="false"` (the extraction rules cannot list the `device_*` domains, see
 * data_extraction_rules.xml).
 */
class DevicePauseStore(
    private val storage: KeyValueStorage,
    /** The credential-encrypted source of the one-time copy; only called while unlocked. */
    private val legacy: () -> PauseStore,
    private val isUnlocked: () -> Boolean,
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
        return copied
    }

    private fun rewriteCorrupt(): Stored {
        val clean = Stored(pausedAtMs = 0L, resumedAtMs = null, holdMaxMin = null, migrated = true)
        storage.set(KEY, clean)
        return clean
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

        /** A fixed device-protected file, whatever the outcome of the other storage copy was. */
        fun create(context: Context, legacy: () -> PauseStore): DevicePauseStore {
            val app = context.applicationContext
            val deContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                app.createDeviceProtectedStorageContext()
            } else {
                app
            }
            return DevicePauseStore(
                PreferencesStorage(deContext.getSharedPreferences(FILE, Context.MODE_PRIVATE), PREFIX),
                legacy,
                isUnlocked = { DeviceStorage.isUnlocked(app) },
            )
        }
    }
}

/** The pause state cannot be read (not copied yet and locked, or a storage fault). Treated as paused. */
class PauseStateUnreadableException(message: String) : IllegalStateException(message)
