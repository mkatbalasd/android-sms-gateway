package me.capcom.smsgateway.modules.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import me.capcom.smsgateway.helpers.DeviceStorage
import me.capcom.smsgateway.modules.settings.KeyValueStorage
import me.capcom.smsgateway.modules.settings.PreferencesStorage
import java.io.File
import java.lang.reflect.Type

/**
 * Opens the encrypted store of the account tokens ("mnaseb-account",
 * AES-256 keys and values, master key in the Android Keystore).
 *
 * If the Keystore key and the file no longer match (a restored or corrupted
 * file, a changed lock screen on some phones) the file is deleted and created
 * again: the keeper signs in again. If even that fails the tokens are kept in
 * memory only. They are never written to an unencrypted file.
 */
object AccountStorageFactory {
    private const val FILE = "mnaseb-account"
    private const val PREFIX = "account"
    private const val TAG = "AccountStorage"

    /**
     * CARD-21: refuses while the user storage is locked (a phone that booted and was not
     * unlocked yet). The file is credential-encrypted: opening it then fails, and the
     * "unreadable, start a new one" path below would delete the real file and keep an
     * in-memory store for the life of the process, so the app would look signed out
     * after the unlock. Nothing is opened, nothing is deleted.
     */
    fun create(context: Context): KeyValueStorage =
        requireUnlocked({ DeviceStorage.isUnlocked(context) }) { createUnlocked(context) }

    private fun createUnlocked(context: Context): KeyValueStorage {
        val app = context.applicationContext
        val prefs = open(app) ?: run {
            Log.w(TAG, "encrypted store unreadable, starting a new one")
            deleteFile(app)
            open(app)
        }
        return if (prefs != null) {
            PreferencesStorage(prefs, PREFIX)
        } else {
            Log.w(TAG, "encrypted store unavailable, keeping the session in memory only")
            InMemoryKeyValueStorage()
        }
    }

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    private fun deleteFile(context: Context) {
        try {
            File(context.applicationInfo.dataDir, "shared_prefs/$FILE.xml").delete()
        } catch (e: Exception) {
            // nothing more to do; open() is tried once more and may fall back to memory
        }
    }
}

/** Thrown when the account store is asked for before the first unlock (CARD-21). */
class LockedException : IllegalStateException("The account store is not available before the first unlock")

/** Runs [block] only when [isUnlocked] says so; otherwise throws [LockedException] and runs nothing. */
internal fun <T> requireUnlocked(isUnlocked: () -> Boolean, block: () -> T): T {
    if (!isUnlocked()) throw LockedException()
    return block()
}

/** Keeps values for the life of the process only; the last resort of [AccountStorageFactory]. */
class InMemoryKeyValueStorage : KeyValueStorage {
    private val values = HashMap<String, Any?>()

    @Synchronized
    override fun <T> set(key: String, value: T) {
        if (value == null) values.remove(key) else values[key] = value
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, typeOfT: Type): T? = values[key] as T?

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }
}
