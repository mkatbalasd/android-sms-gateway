package me.capcom.smsgateway.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class OemAutostartTest {

    @Test
    fun huaweiHasStartupThenProtect() {
        val t = OemAutostart.targets("HUAWEI")
        assertEquals(2, t.size)
        assertEquals("com.huawei.systemmanager", t[0].pkg)
        assertTrue(t[0].cls.endsWith("startupmgr.ui.StartupNormalAppListActivity"))
        assertTrue(t[1].cls.endsWith("optimize.process.ProtectActivity"))
    }

    @Test
    fun honorUsesHuaweiTable() {
        assertEquals(OemAutostart.targets("huawei"), OemAutostart.targets("Honor"))
    }

    @Test
    fun eachKnownMakerHasItsFirstTarget() {
        val expected = mapOf(
            "Xiaomi" to "com.miui.securitycenter",
            "samsung" to "com.samsung.android.lool",
            "OPPO" to "com.coloros.safecenter",
            "vivo" to "com.vivo.permissionmanager",
            "OnePlus" to "com.oneplus.security",
        )
        for ((maker, pkg) in expected) {
            val t = OemAutostart.targets(maker)
            assertEquals(maker, 1, t.size)
            assertEquals(maker, pkg, t.first().pkg)
        }
    }

    @Test
    fun unknownMakerHasNoOemTarget() {
        assertTrue(OemAutostart.targets("Fairphone").isEmpty())
        assertTrue(OemAutostart.targets("").isEmpty())
        assertTrue(OemAutostart.targets(null).isEmpty())
        assertEquals(OemAutostart.Maker.OTHER, OemAutostart.makerOf("Fairphone"))
    }

    @Test
    fun targetsAreFullyQualifiedAndInTheirOwnPackage() {
        for (m in listOf("huawei", "xiaomi", "samsung", "oppo", "vivo", "oneplus")) {
            for (t in OemAutostart.targets(m)) {
                assertTrue(t.cls, t.cls.contains('.') && !t.cls.startsWith("."))
                assertTrue(t.pkg, t.cls.startsWith(t.pkg.substringBeforeLast('.')))
            }
        }
    }

    @Test
    fun makerNamesAreTrimmedAndCaseInsensitive() {
        assertEquals(OemAutostart.Maker.HUAWEI, OemAutostart.makerOf("  HUAWEI "))
        assertEquals(OemAutostart.Maker.XIAOMI, OemAutostart.makerOf("Redmi"))
    }
}
