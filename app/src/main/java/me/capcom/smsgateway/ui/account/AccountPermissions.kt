package me.capcom.smsgateway.ui.account

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.content.ContextCompat

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

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

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
