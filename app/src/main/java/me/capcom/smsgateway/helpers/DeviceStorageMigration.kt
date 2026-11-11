package me.capcom.smsgateway.helpers

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * CARD-21: the one-time move of the app's data from credential-encrypted (CE)
 * storage to device-protected (DE) storage, so the phone can serve before the
 * first unlock after a reboot.
 *
 * Pure logic: every Android call is injected, so the rules are unit-tested on the JVM.
 *
 * Rules:
 *  - never while the user is locked (CE is unreadable; nothing is changed);
 *  - runs once: after a full success a flag is written and later calls do nothing;
 *  - order is preferences, then password scrub, then database; the flag is written
 *    only after all of them succeeded, so a failure is retried on the next start;
 *  - a missing source (fresh install, or only one of the two ever existed) is not a failure.
 */
class DeviceStorageMigration(
    private val isUnlocked: () -> Boolean,
    private val isDone: () -> Boolean,
    private val prefsSourceExists: () -> Boolean,
    private val movePrefs: () -> Boolean,
    private val scrubPassword: () -> Unit,
    private val dbSourceExists: () -> Boolean,
    private val moveDb: () -> Boolean,
    private val markDone: () -> Unit,
) {
    enum class Result { SKIPPED_LOCKED, ALREADY_DONE, DONE, FAILED }

    fun run(): Result {
        if (!isUnlocked()) return Result.SKIPPED_LOCKED
        if (isDone()) return Result.ALREADY_DONE

        try {
            if (prefsSourceExists() && !movePrefs()) return Result.FAILED
            scrubPassword()
            if (dbSourceExists() && !moveDb()) return Result.FAILED
            markDone()
        } catch (e: Exception) {
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
