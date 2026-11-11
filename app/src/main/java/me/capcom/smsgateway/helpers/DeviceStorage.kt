package me.capcom.smsgateway.helpers

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.core.os.UserManagerCompat
import java.io.File

/**
 * CARD-21: device-protected (DE) storage for what the phone needs before the
 * first unlock: the default SharedPreferences file and the Room database `gateway`.
 *
 * Stays in credential-encrypted (CE) storage: `mnaseb-account` (the app session)
 * and WorkManager's own database.
 *
 * The gateway password is never copied to DE (see [RegistrationInfoScrubber]).
 */
object DeviceStorage {
    const val DATABASE_NAME = "gateway"

    private const val FLAG_FILE = "mnaseb_device_storage"
    private const val FLAG_KEY = "migrated_v1"
    private const val REGISTRATION_INFO_KEY = "gateway.REGISTRATION_INFO"

    /** The context whose files are device-protected (API 24+); the same context below that. */
    fun deContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

    fun isUnlocked(context: Context): Boolean = UserManagerCompat.isUserUnlocked(context)

    /**
     * Moves the default preferences and the database from CE to DE, once, and only
     * while the user is unlocked. Call it before anything opens either of them.
     */
    fun migrateOnce(context: Context): DeviceStorageMigration.Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DeviceStorageMigration.Result.ALREADY_DONE
        }

        val ce = context
        val de = ce.createDeviceProtectedStorageContext()
        val prefsName = "${ce.packageName}_preferences"
        val flags = de.getSharedPreferences(FLAG_FILE, Context.MODE_PRIVATE)

        return DeviceStorageMigration(
            isUnlocked = { isUnlocked(ce) },
            isDone = { flags.getBoolean(FLAG_KEY, false) },
            prefsSourceExists = {
                File(ce.dataDir, "shared_prefs/$prefsName.xml").exists()
            },
            movePrefs = { de.moveSharedPreferencesFrom(ce, prefsName) },
            scrubPassword = {
                val prefs = de.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                RegistrationInfoScrubber.scrub(prefs.getString(REGISTRATION_INFO_KEY, null))
                    ?.let { scrubbed ->
                        prefs.edit(commit = true) { putString(REGISTRATION_INFO_KEY, scrubbed) }
                    }
            },
            dbSourceExists = { ce.getDatabasePath(DATABASE_NAME).exists() },
            moveDb = { de.moveDatabaseFrom(ce, DATABASE_NAME) },
            markDone = { flags.edit(commit = true) { putBoolean(FLAG_KEY, true) } },
        ).run()
    }
}
