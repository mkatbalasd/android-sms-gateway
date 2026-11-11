package me.capcom.smsgateway.helpers

import java.io.File

/**
 * CARD-21: the two stores the migration copies, as sets of files in plain directories
 * (so the same code runs in the unit tests on temp folders).
 */
object DeviceStorageStores {
    /**
     * The default SharedPreferences file. SharedPreferences keeps a `.bak` while it writes
     * and prefers it on the next open, so both belong to the set, and both are scrubbed.
     */
    fun preferences(ceDir: File, deDir: File, prefsName: String): DeviceStorageMigration.Store {
        val names = listOf("$prefsName.xml", "$prefsName.xml.bak")
        return FileSetStore("preferences", ceDir, deDir, names) { PreferencesFileScrubber.scrub(it) }
    }

    /**
     * The Room database with its write-ahead log and shared memory file, together: the committed
     * rows that are still only in the `-wal` file must come along. Any one of them counts as a
     * source, not only the main file.
     */
    fun database(ceDir: File, deDir: File, name: String): DeviceStorageMigration.Store =
        FileSetStore(
            "database", ceDir, deDir,
            listOf(name, "$name-wal", "$name-shm", "$name-journal"),
        )

    private class FileSetStore(
        override val name: String,
        private val ceDir: File,
        private val deDir: File,
        private val names: List<String>,
        private val transform: ((File) -> Unit)? = null,
    ) : DeviceStorageMigration.Store {
        override fun hasSource(): Boolean = names.any { File(ceDir, it).exists() }

        override fun copy() {
            AtomicFileSet.copy(
                sources = names.map { File(ceDir, it) },
                targetDir = deDir,
                ownedNames = names,
                transform = transform,
            )
        }
    }
}
