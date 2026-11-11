package me.capcom.smsgateway.helpers

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * CARD-21: copies one store (a set of files that belong together) into a directory.
 *
 * - copies, never moves: the originals stay (a pre-CARD-21 build still finds them);
 * - every file is written under a temporary name first, passed through [transform], and
 *   renamed into place only when the whole set is ready, so a failure or a kill before the
 *   renames changes nothing in the target;
 * - files of the set that the source does not have (a stale -wal of an older copy) are
 *   removed from the target, so a database is never opened with the log of another one;
 * - files outside the set are never touched.
 *
 * Pure java.io: unit-tested on the JVM.
 */
object AtomicFileSet {
    const val TMP_SUFFIX = ".mnaseb-tmp"

    /**
     * @param sources the files of the set; the ones that do not exist are skipped
     * @param ownedNames every name that belongs to the set in the target, present in the source or not
     * @param transform edits a temporary copy before it is renamed into place
     * @throws IOException when any step fails; the target is then unchanged
     */
    fun copy(
        sources: List<File>,
        targetDir: File,
        ownedNames: Collection<String> = emptyList(),
        transform: ((File) -> Unit)? = null,
    ) {
        val existing = sources.filter { it.exists() }
        if (existing.isEmpty()) return

        if (!targetDir.isDirectory && !targetDir.mkdirs() && !targetDir.isDirectory) {
            throw IOException("cannot create the target directory")
        }

        val temps = ArrayList<Pair<File, File>>()
        try {
            for (source in existing) {
                val tmp = File(targetDir, source.name + TMP_SUFFIX)
                temps += tmp to File(targetDir, source.name)
                copyFile(source, tmp)
                transform?.invoke(tmp)
            }

            val kept = existing.map { it.name }.toSet()
            for (name in ownedNames) {
                if (name in kept) continue
                val stale = File(targetDir, name)
                if (stale.exists() && !stale.delete()) {
                    throw IOException("cannot remove a stale file of the set")
                }
            }

            for ((tmp, target) in temps) {
                replace(tmp, target)
            }
        } finally {
            temps.forEach { (tmp, _) -> tmp.delete() }
        }
    }

    /**
     * Removes the temporary files a killed attempt left in [dir]. They can hold an unscrubbed
     * copy, so every migration attempt starts by deleting them.
     */
    fun deleteLeftovers(dir: File) {
        dir.listFiles()?.forEach {
            if (it.name.endsWith(TMP_SUFFIX)) it.delete()
        }
    }

    private fun copyFile(source: File, target: File) {
        val expected = source.length()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (target.length() != expected) {
            throw IOException("the copy is not the size of the original")
        }
    }

    private fun replace(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        // some file systems refuse to rename over an existing file
        if (target.exists() && !target.delete()) {
            throw IOException("cannot replace a file of the set")
        }
        if (!tmp.renameTo(target)) {
            throw IOException("cannot rename a file of the set into place")
        }
    }
}
