package me.capcom.smsgateway.helpers

import java.io.File

/**
 * CARD-24: removes the credential-encrypted ROLLBACK copies CARD-21 left for one release (the
 * old default preferences file, which still holds the gateway password DE never gets, and the
 * old `gateway` database with every message in clear). Pure: the files and the conditions are
 * injected, so the rules are unit-tested on temp folders.
 *
 * Only when the copy to device-protected storage is done (the flag), the app runs on it (no
 * failed-copy fallback to the original data) and the user is unlocked (the files are readable).
 * Anything else deletes nothing: the originals are then the only copy.
 */
object RollbackCopies {
    /** The files of the old default preferences and of the old database, with their journals. */
    fun candidates(ceDataDir: File, ceDatabaseDir: File, prefsName: String, dbName: String): List<File> {
        val prefs = listOf("$prefsName.xml", "$prefsName.xml.bak").map {
            File(File(ceDataDir, "shared_prefs"), it)
        }
        val db = listOf(dbName, "$dbName-wal", "$dbName-shm", "$dbName-journal").map {
            File(ceDatabaseDir, it)
        }
        return prefs + db
    }

    /** @return how many files were removed */
    fun remove(
        migrated: Boolean,
        runningOnDeviceProtected: Boolean,
        unlocked: Boolean,
        files: List<File>,
    ): Int {
        if (!migrated || !runningOnDeviceProtected || !unlocked) return 0
        var removed = 0
        for (file in files) {
            if (file.exists() && file.delete()) removed++
        }
        return removed
    }
}
