package me.capcom.smsgateway.modules.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class IsoTimeAndMobileInputTest {

    @Test
    fun parsesUtcTimestamps() {
        // 2026-11-08T10:00:00Z
        assertEquals(1_794_132_000_000L, IsoTime.parseMillis("2026-11-08T10:00:00Z"))
        assertEquals(1_794_132_000_123L, IsoTime.parseMillis("2026-11-08T10:00:00.123Z"))
        assertEquals(1_794_132_000_120L, IsoTime.parseMillis("2026-11-08T10:00:00.12Z"))
        assertEquals(1_794_132_000_123L, IsoTime.parseMillis("2026-11-08T10:00:00.123456Z"))
    }

    @Test
    fun parsesOffsets() {
        assertEquals(
            IsoTime.parseMillis("2026-11-08T10:00:00Z"),
            IsoTime.parseMillis("2026-11-08T13:00:00+03:00")
        )
        assertEquals(
            IsoTime.parseMillis("2026-11-08T10:00:00Z"),
            IsoTime.parseMillis("2026-11-08T08:30:00-0130")
        )
    }

    @Test
    fun refusesWhatIsNotATimestamp() {
        assertNull(IsoTime.parseMillis(null))
        assertNull(IsoTime.parseMillis(""))
        assertNull(IsoTime.parseMillis("tomorrow"))
        assertNull(IsoTime.parseMillis("2026-11-08"))
    }

    @Test
    fun theCountdownIsBoundedAndNeverNegative() {
        val target = "2026-11-08T10:01:00Z"
        val now = IsoTime.parseMillis("2026-11-08T10:00:00Z")!!
        assertEquals(60, IsoTime.secondsUntil(target, now, 120))
        assertEquals(30, IsoTime.secondsUntil(target, now + 30_000L, 120))
        assertEquals(0, IsoTime.secondsUntil(target, now + 90_000L, 120))
        // A phone clock a day behind must not show a day of countdown.
        assertEquals(120, IsoTime.secondsUntil(target, now - 86_400_000L, 120))
        assertEquals(0, IsoTime.secondsUntil(null, now, 120))
        assertEquals(0, IsoTime.secondsUntil("garbage", now, 120))
    }

    @Test
    fun anInternationalNumberIsKept() {
        assertEquals("+967700000200", MobileInput.normalize("+967700000200", null))
        assertEquals("+967700000200", MobileInput.normalize(" +967 700-000-200 ", null))
        assertEquals("+967700000200", MobileInput.normalize("00967700000200", null))
    }

    @Test
    fun arabicIndicDigitsAndInvisibleMarksAreAccepted() {
        val typed = "‎+٩٦٧٧٠٠٠٠٠٢٠٠"
        assertEquals("+967700000200", MobileInput.normalize(typed, null))
    }

    @Test
    fun aNationalNumberUsesTheSimCountry() {
        assertEquals("+967700000200", MobileInput.normalize("0700000200", 967))
        assertEquals("+966501234567", MobileInput.normalize("0501234567", 966))
    }

    @Test
    fun aNumberWithoutACountryIsRefusedRatherThanGuessed() {
        assertNull(MobileInput.normalize("0700000200", null))
        assertNull(MobileInput.normalize("700000200", 967))
        assertNull(MobileInput.normalize("", 967))
        assertNull(MobileInput.normalize("+12", null))
        assertNull(MobileInput.normalize("+9677000002001234567890", null))
        assertNull(MobileInput.normalize("abc", 967))
    }

    @Test
    fun anAddressIsNotAPhone() {
        assertFalse(MobileInput.looksLikePhone("owner@example.com"))
        assertTrue(MobileInput.looksLikePhone("+967700000200"))
        assertFalse(MobileInput.looksLikePhone("   "))
    }
}
