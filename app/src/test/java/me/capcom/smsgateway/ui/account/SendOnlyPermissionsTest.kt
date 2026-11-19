package me.capcom.smsgateway.ui.account

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CARD-25: the send-only build must not ask for, list or count the four incoming-SMS
 * permissions; the two-way build keeps every one of them.
 */
internal class SendOnlyPermissionsTest {

    private val sdk = Build.VERSION_CODES.TIRAMISU
    private val incoming = listOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
    )

    @Test
    fun sendOnlyAsksForNoIncomingPermission() {
        val required = AccountPermissions.required(sdk, sendOnly = true)
        assertTrue(Manifest.permission.SEND_SMS in required)
        assertTrue(required.none { it in incoming })
        assertEquals(listOf(Manifest.permission.SEND_SMS), AccountPermissions.smsPermissions(sendOnly = true))
    }

    @Test
    fun sendOnlyIsFullyGrantedWithSendSmsAndTheOthers() {
        val granted = AccountPermissions.required(sdk, sendOnly = true).toSet()
        val state = AccountPermissions.state(sdk, { it in granted }, sendOnly = true)
        assertTrue(state.allGranted)
        assertTrue(state.smsGranted)
    }

    @Test
    fun twoWayKeepsTheIncomingPermissions() {
        val required = AccountPermissions.required(sdk, sendOnly = false)
        assertTrue(Manifest.permission.RECEIVE_SMS in required)
        assertTrue(Manifest.permission.READ_SMS in required)
        assertTrue(Manifest.permission.RECEIVE_MMS in required)
        assertEquals(3, AccountPermissions.smsPermissions(sendOnly = false).size)
        assertFalse(AccountPermissions.smsPermissions(sendOnly = false).isEmpty())
    }

    @Test
    fun theDefaultVariantOfTheUnitTestBuildIsTwoWay() {
        assertFalse(me.capcom.smsgateway.BuildConfig.SEND_ONLY)
    }
}
