package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GatewayServiceNameTest {

    @Test
    fun withoutASuffixTheNameIsTheUpstreamOne() {
        assertEquals("samsung/a52q", DeviceNames.forRegistration("samsung", "a52q", "SM-A525F", null))
    }

    @Test
    fun withASuffixTheNameIsMnasebSimModelNonce() {
        val name = DeviceNames.forRegistration("Google", "panther", "Pixel 7", "AB12CD34")
        assertEquals("Mnaseb SIM/Pixel 7/AB12CD34", name)
        assertTrue(name.endsWith("/AB12CD34"))
    }

    @Test
    fun aLongModelIsTruncatedAndTheNonceStaysLast() {
        val model = "X".repeat(300)
        val name = DeviceNames.forRegistration("m", "p", model, "AB12CD34")

        assertTrue(name.length <= DeviceNames.NAME_MAX)
        assertTrue(name.endsWith("/AB12CD34"))
        assertEquals("Mnaseb SIM/" + "X".repeat(40) + "/AB12CD34", name)
    }

    @Test
    fun theLongestAllowedNonceStillFits() {
        val nonce = "a".repeat(32)
        val name = DeviceNames.forRegistration("m", "p", "Y".repeat(100), nonce)
        assertTrue(name.length <= DeviceNames.NAME_MAX)
        assertTrue(name.endsWith("/$nonce"))
    }

    @Test
    fun slashesAndControlCharactersInTheModelAreRemoved() {
        val name = DeviceNames.forRegistration("m", "p", "Pixel/7\u0000 Pro‏", "AB12CD34")
        assertEquals("Mnaseb SIM/Pixel-7 Pro/AB12CD34", name)
        // Exactly three segments, so the nonce is what follows the last slash.
        assertEquals(3, name.split('/').size)
    }

    @Test
    fun anEmptyModelGetsAPlaceholder() {
        assertEquals("Mnaseb SIM/Android/AB12CD34", DeviceNames.forRegistration("m", "p", "  ", "AB12CD34"))
    }

    @Test
    fun aNonceThatIsNotLettersAndDigitsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceNames.forRegistration("m", "p", "Pixel", "AB/CD")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceNames.forRegistration("m", "p", "Pixel", "")
        }
        assertFalse(DeviceNames.isValidNonce(null))
        assertFalse(DeviceNames.isValidNonce("AB 12"))
        assertTrue(DeviceNames.isValidNonce("AB12CD34"))
    }
}
