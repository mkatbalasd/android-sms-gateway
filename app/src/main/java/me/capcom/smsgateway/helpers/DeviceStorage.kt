package me.capcom.smsgateway.helpers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.os.UserManagerCompat
import me.capcom.smsgateway.receivers.BootPolicy
import me.capcom.smsgateway.receivers.RelaunchReceiver
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * CARD-21: device-protected (DE) storage for what the phone needs before the
 * first unlock: the default SharedPreferences file and the Room database `gateway`.
 *
 * Stays in credential-encrypted (CE) storage: `mnaseb-account` (the app session)
 * and WorkManager's own database.
 *
 * The gateway password is never copied to DE (see [PreferencesFileScrubber]).
 *
 * ROLLBACK / DOWNGRADE: the migration COPIES. The CE preferences file and the CE database
 * stay where they were, so a build from before CARD-21 still finds its data there: stale
 * (everything after the copy lives only in DE) but intact, and it keeps working. Going
 * forward again does not overwrite DE (the flag stays set), so the newer DE data wins.
 * The CE copies are only the safety net for that one release; they may be removed by a
 * LATER release, once no build older than CARD-21 is in use (a cleanup that runs only when
 * the flag is set and the user is unlocked). Until then they also keep the gateway password
 * that DE never holds, exactly as before CARD-21.
 */
object DeviceStorage {
    const val DATABASE_NAME = "gateway"

    private const val TAG = "DeviceStorage"
    private const val FLAG_FILE = "mnaseb_device_storage"
    private const val FLAG_KEY = "migrated_v1"

    /** Set when the copy failed in this process: the app then runs on the original CE data. */
    @Volatile
    private var useCredentialStorage = false

    /** What the migration did at this process start (null before it ran). */
    @Volatile
    var lastResult: DeviceStorageMigration.Result? = null
        private set

    /** Which store failed and with which exception class; never a message, a path or a value. */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * The context whose files are device-protected (API 24+); the same context below that.
     * After a FAILED copy the app keeps using the original credential-encrypted data, so a
     * failed copy never makes the phone look unlinked. While the copy waits for the unlock
     * this is the (empty) device-protected storage; nothing runs on it (the locked pipeline
     * needs the flag) and the process restarts at the unlock, see [restartAtUnlock].
     */
    fun deContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !useCredentialStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

    fun isUnlocked(context: Context): Boolean = UserManagerCompat.isUserUnlocked(context)

    /** The copy finished (flag set). Before that, DE holds nothing that may be trusted. */
    fun isMigrated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(FLAG_FILE, Context.MODE_PRIVATE)
            .getBoolean(FLAG_KEY, false)
    }

    /**
     * Copies the default preferences and the database from CE to DE, once, and only while
     * the user is unlocked. Call it before anything opens either of them (the database must
     * be closed). Never throws; read [lastResult] and [lastFailure].
     */
    fun migrateOnce(context: Context): DeviceStorageMigration.Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DeviceStorageMigration.Result.ALREADY_DONE
        }

        val ce = context
        val result = try {
            val de = ce.createDeviceProtectedStorageContext()
            val prefsName = "${ce.packageName}_preferences"
            val flags = de.getSharedPreferences(FLAG_FILE, Context.MODE_PRIVATE)

            val migration = DeviceStorageMigration(
                isUnlocked = { isUnlocked(ce) },
                isDone = { flags.getBoolean(FLAG_KEY, false) },
                stores = listOf(
                    DeviceStorageStores.preferences(
                        File(ce.dataDir, "shared_prefs"),
                        File(de.dataDir, "shared_prefs"),
                        prefsName,
                    ),
                    DeviceStorageStores.database(
                        ce.getDatabasePath(DATABASE_NAME).parentFile!!,
                        de.getDatabasePath(DATABASE_NAME).parentFile!!,
                        DATABASE_NAME,
                    ),
                ),
                // LAST: after every store was copied
                markDone = {
                    if (!flags.edit().putBoolean(FLAG_KEY, true).commit()) {
                        throw IOException("the flag was not written")
                    }
                },
            )
            migration.run().also { lastFailure = migration.failure }
        } catch (e: Throwable) {
            lastFailure = "setup: ${e.javaClass.simpleName}"
            DeviceStorageMigration.Result.FAILED
        }

        lastResult = result
        useCredentialStorage = result == DeviceStorageMigration.Result.FAILED
        if (result == DeviceStorageMigration.Result.FAILED) {
            Log.e(TAG, "storage copy failed ($lastFailure); using the original storage, retry at the next start")
        }
        return result
    }

    /**
     * The process started while locked, so the copy was deferred. At the unlock this process
     * restarts itself, and the next process runs the copy in `attachBaseContext`, before any
     * database is open. The unlock can land before the receiver is registered, so after
     * registering it is checked again and the restart happens at once.
     */
    fun restartAtUnlock(context: Context) {
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                exitAndRelaunch(app)
            }
        }, IntentFilter("android.intent.action.USER_UNLOCKED"))

        if (isUnlocked(app)) {
            exitAndRelaunch(app)
        }
    }

    /**
     * Exits this process and brings the app back with an app-private action (never the protected
     * BOOT_COMPLETED, which the system refuses from a non-system sender); [RelaunchReceiver] (not exported) maps it
     * to the full start through `BootPolicy`.
     */
    fun exitAndRelaunch(app: Context) {
        try {
            val relaunch = PendingIntent.getBroadcast(
                app,
                0,
                Intent(BootPolicy.RELAUNCH).setClass(app, RelaunchReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC, System.currentTimeMillis() + 1_500L, relaunch)
        } catch (e: Throwable) {
            Log.w(TAG, "no relaunch alarm: ${e.javaClass.simpleName}")
        }
        Log.d(TAG, "unlocked: restarting to run the deferred storage copy")
        exitProcess(0)
    }
}

/** What `App` does after the migration ran at this process start. Pure. */
object MigrationFollowUp {
    enum class Action {
        NONE,

        /** The copy failed: say so (log), keep running on the original data. */
        REPORT_FAILURE,

        /** The process started locked and still is: restart at the unlock so the copy runs in a fresh process. */
        RESTART_AT_UNLOCK,

        /** The copy was skipped (locked at start) but the user is unlocked by now: restart at once. */
        RESTART_NOW,
    }

    fun of(result: DeviceStorageMigration.Result?, unlockedNow: Boolean): Action = when (result) {
        DeviceStorageMigration.Result.FAILED -> Action.REPORT_FAILURE
        DeviceStorageMigration.Result.SKIPPED_LOCKED ->
            if (unlockedNow) Action.RESTART_NOW else Action.RESTART_AT_UNLOCK
        else -> Action.NONE
    }
}
