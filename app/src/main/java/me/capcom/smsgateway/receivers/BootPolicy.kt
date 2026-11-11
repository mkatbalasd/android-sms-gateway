package me.capcom.smsgateway.receivers

import android.content.Intent

/**
 * CARD-21: what the boot receiver does for an action, depending on whether the
 * user storage is unlocked. Pure, so it is unit-tested on the JVM.
 *
 * - LOCKED: only device-protected storage, no WorkManager, no account store
 *   (see `LockedMode`).
 * - FULL: the orchestrator as before (WorkManager, everything).
 */
object BootPolicy {
    enum class Action { IGNORE, START_FULL, START_LOCKED }

    /** `Intent.ACTION_LOCKED_BOOT_COMPLETED` is API 24; the string is the same on every level. */
    const val LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"

    /** Direct Boot exists from API 24. */
    const val FIRST_DIRECT_BOOT_API = 24

    /** Actions that, while unlocked, start everything (the behaviour before CARD-21). */
    val UNLOCKED_ACTIONS: Set<String> = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.ACTION_BOOT_COMPLETED",
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_REBOOT,
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_SHUTDOWN,
    )

    /** Actions that must never start anything while the storage is still locked. */
    private val IGNORED_WHILE_LOCKED: Set<String> = setOf(
        Intent.ACTION_REBOOT,
        Intent.ACTION_SHUTDOWN,
    )

    fun decide(
        action: String?,
        isUnlocked: Boolean,
        autostart: Boolean,
        sdkInt: Int,
    ): Action {
        if (action == null || !autostart) return Action.IGNORE

        if (action == LOCKED_BOOT_COMPLETED) {
            return when {
                sdkInt < FIRST_DIRECT_BOOT_API -> Action.IGNORE
                isUnlocked -> Action.START_FULL
                else -> Action.START_LOCKED
            }
        }

        if (action !in UNLOCKED_ACTIONS) return Action.IGNORE

        return when {
            isUnlocked -> Action.START_FULL
            action in IGNORED_WHILE_LOCKED -> Action.IGNORE
            sdkInt < FIRST_DIRECT_BOOT_API -> Action.IGNORE
            else -> Action.START_LOCKED
        }
    }
}
