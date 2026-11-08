package me.capcom.smsgateway.modules.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class OtpSmsReaderTest {

    private val platformLine = "+967700000001"

    private fun matcher(sender: String = platformLine): Pair<OtpSmsMatcher, MutableList<String>> {
        val codes = mutableListOf<String>()
        return OtpSmsMatcher(sender) { codes.add(it) } to codes
    }

    @Test
    fun matchesTheSenderInInternationalForm() {
        val (m, codes) = matcher()
        assertTrue(m.onMessage("+967700000001", "Mnaseb: your code is 482913"))
        assertEquals(listOf("482913"), codes)
    }

    @Test
    fun matchesTheSenderInNationalForms() {
        for (shown in listOf("0700000001", "700000001", "00967700000001", "+967 700 000 001")) {
            val (m, codes) = matcher()
            assertTrue("sender $shown", m.onMessage(shown, "code 111222"))
            assertEquals(listOf("111222"), codes)
        }
    }

    @Test
    fun ignoresAnotherSender() {
        val (m, codes) = matcher()
        assertFalse(m.onMessage("+967711111111", "code 111222"))
        assertFalse(m.onMessage("Bank", "code 111222"))
        assertFalse(m.onMessage(null, "code 111222"))
        assertTrue(codes.isEmpty())
        assertFalse(m.isFinished)
    }

    @Test
    fun aTooShortSenderIsNotASuffixMatch() {
        val (m, codes) = matcher()
        assertFalse(m.onMessage("0001", "code 111222"))
        assertFalse(m.onMessage("+1", "code 111222"))
        assertTrue(codes.isEmpty())
    }

    @Test
    fun anAlphanumericSenderMustBeEqual() {
        val (m, codes) = matcher("Mnaseb")
        assertFalse(m.onMessage("Other", "code 111222"))
        assertTrue(m.onMessage("mnaseb", "code 111222"))
        assertEquals(listOf("111222"), codes)
    }

    @Test
    fun extractsExactlyOneSixDigitGroup() {
        assertEquals("482913", OtpSmsMatcher.extractCode("Your code: 482913. Do not share it."))
        assertEquals("000123", OtpSmsMatcher.extractCode("000123"))
        assertNull(OtpSmsMatcher.extractCode("no digits here"))
        assertNull(OtpSmsMatcher.extractCode("12345"))
        assertNull(OtpSmsMatcher.extractCode("1234567"))
        assertNull(OtpSmsMatcher.extractCode(""))
        assertNull(OtpSmsMatcher.extractCode(null))
    }

    @Test
    fun aMessageWithTwoCodesIsIgnored() {
        val (m, codes) = matcher()
        assertFalse(m.onMessage(platformLine, "old 111111 new 222222"))
        assertFalse(m.onMessage(platformLine, "111111 111111"))
        assertTrue(codes.isEmpty())
        assertFalse(m.isFinished)
    }

    @Test
    fun aMessageWithoutACodeIsIgnored() {
        val (m, codes) = matcher()
        assertFalse(m.onMessage(platformLine, "Welcome to Mnaseb"))
        assertFalse(m.onMessage(platformLine, null))
        assertTrue(codes.isEmpty())
    }

    @Test
    fun readsArabicIndicDigits() {
        assertEquals("123456", OtpSmsMatcher.extractCode("الرمز: ١٢٣٤٥٦"))
        assertEquals("123456", OtpSmsMatcher.extractCode("۱۲۳۴۵۶"))
    }

    @Test
    fun stopsAfterTheFirstMatch() {
        val (m, codes) = matcher()
        assertTrue(m.onMessage(platformLine, "code 111111"))
        assertTrue(m.isFinished)
        assertFalse(m.onMessage(platformLine, "code 222222"))
        assertEquals(listOf("111111"), codes)
    }
}
