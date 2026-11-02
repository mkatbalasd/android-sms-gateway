package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/** Code point helper: the source holds no literal invisible or bidi characters. */
internal fun cp(vararg codes: Int): String = String(codes, 0, codes.size)

internal class LoginCodeSanitizerTest {

    private val rle = cp(0x202B)
    private val pdf = cp(0x202C)
    private val lri = cp(0x2066)
    private val rli = cp(0x2067)
    private val fsi = cp(0x2068)
    private val pdi = cp(0x2069)
    private val bom = cp(0xFEFF)
    private val nbsp = cp(0x00A0)
    private val nnbsp = cp(0x202F)
    private val zwsp = cp(0x200B)

    @Test
    fun alreadyCleanCodeIsUnchanged() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("7wr3bd"))
    }

    @Test
    fun caseIsKept() {
        assertEquals("7WR3bd", LoginCodeSanitizer.clean("7WR3bd"))
    }

    @Test
    fun popDirectionalFormattingIsRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("${rle}7wr3bd$pdf"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("7wr${pdf}3bd"))
    }

    @Test
    fun bidiIsolatesAreRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("${lri}7wr3bd$pdi"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("$rli${fsi}7wr3bd$pdi"))
    }

    @Test
    fun byteOrderMarkIsRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("${bom}7wr3bd"))
    }

    @Test
    fun spacesAndNonBreakingSpacesAreRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("  7wr 3bd\t\n"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("${nbsp}7wr${nbsp}3bd$nnbsp"))
    }

    @Test
    fun everyListedFormatRangeIsRemoved() {
        val ranges = listOf(0x200B..0x200F, 0x202A..0x202E, 0x2060..0x2064, 0x2066..0x2069)
        for (range in ranges) {
            for (code in range) {
                val ch = cp(code)
                assertEquals(
                    "U+%04X".format(code),
                    "ab12",
                    LoginCodeSanitizer.clean("a${ch}b1${ch}2")
                )
            }
        }
    }

    @Test
    fun charactersOutsideTheRangesAreKept() {
        // U+200A (hair space) is whitespace; U+2065 and U+2070 are not format characters.
        assertEquals("a${cp(0x2065)}b${cp(0x2070)}", LoginCodeSanitizer.clean("a${cp(0x2065)}b${cp(0x2070)}"))
    }

    @Test
    fun onlyInvisibleInputGivesEmptyResult() {
        assertEquals("", LoginCodeSanitizer.clean(" $pdf$lri$pdi$bom$nbsp$zwsp "))
        assertEquals("", LoginCodeSanitizer.clean(""))
    }

    @Test
    fun stripFormatCharsKeepsSpaces() {
        val label = cp(0x0645, 0x0631, 0x0643, 0x0632) + " " + cp(0x0627, 0x0644, 0x0634, 0x0641, 0x0627, 0x0621)
        assertEquals(label, LoginCodeSanitizer.stripFormatChars("$rle$label$pdf"))
    }
}
