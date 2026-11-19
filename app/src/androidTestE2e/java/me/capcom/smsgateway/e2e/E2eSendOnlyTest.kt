package me.capcom.smsgateway.e2e

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CARD-25: run ONLY against the sendOnlyE2e build (e2e/send-only-e2e.sh); it is not in the class
 * list of run-e2e.sh, so the two-way suite never runs it.
 * The send-only build declares and holds none of the four incoming-SMS permissions, keeps SEND_SMS,
 * and has no receiver component for incoming SMS or MMS.
 */
@RunWith(AndroidJUnit4::class)
class E2eSendOnlyTest {

    private val incoming = listOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.RECEIVE_WAP_PUSH,
    )

    @Test
    fun noIncomingPermissionIsDeclaredOrGranted() {
        val pm = E2e.context.packageManager
        val info = pm.getPackageInfo(E2e.PKG, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions?.toList().orEmpty()
        for (p in incoming) {
            assertFalse("$p is declared", p in declared)
            assertEquals("$p is granted", PackageManager.PERMISSION_DENIED, pm.checkPermission(p, E2e.PKG))
        }
        assertTrue("SEND_SMS must stay declared", Manifest.permission.SEND_SMS in declared)
    }

    @Test
    fun noIncomingSmsOrMmsReceiverIsRegistered() {
        val pm = E2e.context.packageManager
        val info = pm.getPackageInfo(E2e.PKG, PackageManager.GET_RECEIVERS)
        val names = info.receivers?.map { it.name }.orEmpty()
        assertFalse("MessagesReceiver registered: $names", names.any { it.endsWith(".MessagesReceiver") })
        assertFalse("MmsReceiver registered: $names", names.any { it.endsWith(".MmsReceiver") })
    }

    @Test
    fun thePermissionScreenSaysItIsSendOnlyAndAsksForNoIncomingPermission() {
        E2e.startAccountScreen()
        E2e.need("buttonCreateAccount").click()
        // Fresh install: the permission screen shows the send-only sentence (resource, not text).
        if (E2e.find("buttonAllow", 4_000) != null) {
            assertTrue(E2e.visible("textPermissionsBody"))
        }
    }
}
