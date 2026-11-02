package me.capcom.smsgateway.modules.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

internal class LoginCodeSanitizerTest {

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
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("‫7wr3bd‬"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("7wr‬3bd"))
    }

    @Test
    fun bidiIsolatesAreRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("⁦7wr3bd⁩"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("⁧⁨7wr3bd⁩"))
    }

    @Test
    fun byteOrderMarkIsRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("﻿7wr3bd"))
    }

    @Test
    fun spacesAndNonBreakingSpacesAreRemoved() {
        assertEquals("7wr3bd", LoginCodeSanitizer.clean("  7wr 3bd\t\n"))
        assertEquals("7wr3bd", LoginCodeSanitizer.clean(" 7wr 3bd "))
    }

    @Test
    fun everyListedFormatRangeIsRemoved() {
        val ranges = listOf(
            '​'..'‏',
            '‪'..'‮',
            '⁠'..'⁤',
            '⁦'..'⁩',
        )
        for (range in ranges) {
            for (ch in range) {
                assertEquals(
                    "U+%04X".format(ch.code),
                    "ab12",
                    LoginCodeSanitizer.clean("a${ch}b1${ch}2")
                )
            }
        }
    }

    @Test
    fun onlyInvisibleInputGivesEmptyResult() {
        assertEquals("", LoginCodeSanitizer.clean(" ‬⁦⁩﻿ ​ "))
        assertEquals("", LoginCodeSanitizer.clean(""))
    }

    @Test
    fun stripFormatCharsKeepsSpaces() {
        assertEquals(
            "مركز الشفاء",
            LoginCodeSanitizer.stripFormatChars(
                "‫مركز الشفاء‬"
            )
        )
    }
}
