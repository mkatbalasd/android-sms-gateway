package me.capcom.smsgateway.ui.account

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-22: the permission screen reports the real runtime grant state of each
 * permission (what ContextCompat.checkSelfPermission answers), never an
 * "Enhanced Confirmation" / restricted-settings guess.
 */
internal class AccountPermissionStateTest {

    private val sms = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
    )
    private val sdk = Build.VERSION_CODES.TIRAMISU

    private fun state(granted: Set<String>) =
        AccountPermissions.state(sdk) { it in granted }

    @Test
    fun grantedSmsPermissionsAreNeverReportedMissing() {
        // The phone granted the three SMS permissions; the others are still open.
        val result = state(sms.toSet())

        assertTrue(result.smsGranted)
        assertTrue(result.sendSmsGranted)
        assertTrue(result.missing.none { it in sms })
        assertFalse(result.allGranted)
        assertEquals(PermissionLevel.SMS_GRANTED_OTHERS_MISSING, result.level)
    }

    @Test
    fun theRestrictedSettingsGuidanceNeverTurnsAGrantedSmsPermissionIntoAMissingOne() {
        val result = state(sms.toSet())

        // Even when the phone says the app is restricted (Enhanced Confirmation), the
        // granted state stands and the «Allow restricted settings» guidance stays hidden.
        assertFalse(AccountPermissions.showRestrictedGuidance(result, enhancedConfirmationRestricted = true))
        assertTrue(result.smsGranted)
        assertTrue(result.missing.none { it in sms })
    }

    @Test
    fun theRestrictedSettingsGuidanceShowsOnlyWhenASmsPermissionIsReallyMissing() {
        val none = state(emptySet())
        val noSend = state(setOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))

        assertEquals(PermissionLevel.SMS_MISSING, none.level)
        assertFalse(noSend.sendSmsGranted)
        assertTrue(AccountPermissions.showRestrictedGuidance(none, enhancedConfirmationRestricted = true))
        assertTrue(AccountPermissions.showRestrictedGuidance(noSend, enhancedConfirmationRestricted = true))
        // Not restricted: nothing to guide.
        assertFalse(AccountPermissions.showRestrictedGuidance(none, enhancedConfirmationRestricted = false))
    }

    @Test
    fun allGrantedIsTrueOnlyWhenEveryRequiredPermissionIsGranted() {
        val everything = AccountPermissions.required(sdk).toSet()

        assertTrue(state(everything).allGranted)
        assertEquals(PermissionLevel.ALL_GRANTED, state(everything).level)
        assertFalse(state(everything - Manifest.permission.POST_NOTIFICATIONS).allGranted)
    }

    @Test
    fun theStateFollowsTheGrantAtEachEvaluation() {
        // Re-evaluated on resume: the same lookup answers differently once the keeper allows it.
        var granted = emptySet<String>()
        val lookup = { permission: String -> permission in granted }

        assertFalse(AccountPermissions.state(sdk, lookup).sendSmsGranted)
        granted = setOf(Manifest.permission.SEND_SMS)
        assertTrue(AccountPermissions.state(sdk, lookup).sendSmsGranted)
    }
}
