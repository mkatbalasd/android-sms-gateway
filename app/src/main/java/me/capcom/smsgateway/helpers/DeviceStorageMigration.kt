package me.capcom.smsgateway.helpers

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * CARD-21: the one-time copy of the app's data from credential-encrypted (CE) storage to
 * device-protected (DE) storage, so the phone can serve before the first unlock after a reboot.
 *
 * Pure logic: every Android and file call is injected, so the rules are unit-tested on the JVM.
 *
 * Rules:
 *  - never while the user is locked (CE is unreadable; nothing is changed). The process that
 *    started locked does not migrate; the next unlocked start does (see `App`);
 *  - the flag is the only authority: when it is set, nothing is copied again, so DE data that
 *    is newer than CE is never overwritten; when it is absent, DE is not authoritative and is
 *    (re)built from CE;
 *  - it COPIES: the CE originals stay (rollback, see [DeviceStorage]);
 *  - each store is copied as a set (see [AtomicFileSet]) in this order, and the flag is written
 *    LAST, only after every store succeeded; a failure is retried from scratch on the next start;
 *  - a missing store (fresh install, or only one of the two ever existed) is not a failure.
 *
 * The caller must run it while the database is closed (before Koin and Room start).
 */
class DeviceStorageMigration(
    private val isUnlocked: () -> Boolean,
    private val isDone: () -> Boolean,
    private val stores: List<Store>,
    private val markDone: () -> Unit,
) {
    /** One store: the files that belong together. [copy] throws on any failure. */
    interface Store {
        /** Short, content-free name used in the failure report. */
        val name: String

        fun hasSource(): Boolean

        fun copy()

        /** Deletes temporary files a killed earlier attempt left in the destination. Never throws on a missing directory. */
        fun cleanLeftovers() {}
    }

    enum class Result { SKIPPED_LOCKED, ALREADY_DONE, DONE, FAILED }

    /** After [Result.FAILED]: which store and which exception class (never a message or a path). */
    var failure: String? = null
        private set

    fun run(): Result {
        failure = null
        if (!isUnlocked()) return Result.SKIPPED_LOCKED
        if (isDone()) return Result.ALREADY_DONE

        // a killed earlier attempt may have left temporary copies in the destination
        for (store in stores) {
            try {
                store.cleanLeftovers()
            } catch (e: Exception) {
                // best effort; the copy below overwrites its own temporary names anyway
            }
        }

        for (store in stores) {
            try {
                if (store.hasSource()) store.copy()
            } catch (e: Exception) {
                failure = "${store.name}: ${e.javaClass.simpleName}"
                return Result.FAILED
            }
        }

        try {
            markDone()
        } catch (e: Exception) {
            failure = "flag: ${e.javaClass.simpleName}"
            return Result.FAILED
        }
        return Result.DONE
    }
}

/**
 * Removes the gateway password from the stored `REGISTRATION_INFO` JSON.
 * The gateway password is never kept in device-protected storage (CARD-21).
 */
object RegistrationInfoScrubber {
    /** @return the JSON without `password`, or `null` when nothing has to change. */
    fun scrub(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val obj: JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (!obj.has("password") || obj.get("password").isJsonNull) return null
        obj.remove("password")
        return obj.toString()
    }
}
