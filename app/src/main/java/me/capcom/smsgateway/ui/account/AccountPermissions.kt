package me.capcom.smsgateway.ui.account

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat

enum class PermissionLevel { ALL_GRANTED, SMS_GRANTED_OTHERS_MISSING, SMS_MISSING }

/** What is really granted: [missing] are the required permissions the phone has not granted. */
data class PermissionState(val missing: List<String>, val smsMissing: List<String>) {
    val allGranted: Boolean get() = missing.isEmpty()
    val smsGranted: Boolean get() = smsMissing.isEmpty()
    val sendSmsGranted: Boolean get() = Manifest.permission.SEND_SMS !in smsMissing

    val level: PermissionLevel
        get() = when {
            allGranted -> PermissionLevel.ALL_GRANTED
            smsGranted -> PermissionLevel.SMS_GRANTED_OTHERS_MISSING
            else -> PermissionLevel.SMS_MISSING
        }
}

/** The permissions the app asks for on its first screen, before any account exists. */
object AccountPermissions {

    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = listOfNotNull(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.READ_PHONE_NUMBERS.takeIf { sdkInt >= Build.VERSION_CODES.TIRAMISU },
        Manifest.permission.POST_NOTIFICATIONS.takeIf { sdkInt >= Build.VERSION_CODES.TIRAMISU },
    )

    /** The permissions without which the phone cannot send or read the SMS the product depends on. */
    val sms: List<String> = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )

    /**
     * The grant state of every required permission, from [isGranted] (the real runtime answer
     * of ContextCompat.checkSelfPermission). Nothing else - not Enhanced Confirmation, not a
     * restricted-settings guess - may report a permission as missing.
     */
    fun state(sdkInt: Int = Build.VERSION.SDK_INT, isGranted: (String) -> Boolean): PermissionState {
        val missing = required(sdkInt).filterNot(isGranted)
        return PermissionState(missing = missing, smsMissing = missing.filter { it in sms })
    }

    /** Evaluates the real grant state now; call again on resume, it is never cached. */
    fun state(context: Context): PermissionState = state { granted(context, it) }

    fun missing(context: Context): List<String> = state(context).missing

    /** True when SEND_SMS is granted at this moment: the gate in front of the proof SMS. */
    fun canSendSms(context: Context): Boolean = granted(context, Manifest.permission.SEND_SMS)

    /**
     * The «Allow restricted settings» guidance is for a phone that blocks the prompt (Enhanced
     * Confirmation on a sideloaded app). It is shown only while an SMS permission is really
     * missing; it never changes what is reported as granted.
     */
    fun showRestrictedGuidance(state: PermissionState, enhancedConfirmationRestricted: Boolean): Boolean =
        enhancedConfirmationRestricted && !state.smsGranted

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canReadSms(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Messages are sent from the main user of the phone only. A secondary user
     * (or a work profile) gets a stop screen. UserManager.isSystemUser needs
     * API 23; older phones are treated as the main user.
     */
    fun isMainUser(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
        return users.isSystemUser
    }
}
