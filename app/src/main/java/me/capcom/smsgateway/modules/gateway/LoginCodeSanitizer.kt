package me.capcom.smsgateway.modules.gateway

/**
 * Mnaseb SIM: cleans a one-time login code before it is sent as
 * `Authorization: Code <code>`.
 *
 * A code copied from a chat or a web page often carries invisible
 * characters (a bidi mark such as U+202C, a zero-width space, a BOM) or a
 * non-breaking space; the server then refuses an otherwise correct code.
 * Every code entry path (typed, pasted, QR scan, deep link) goes through
 * [clean]. The code's case is kept as entered (owner decision 0014, SIM-S1a).
 */
object LoginCodeSanitizer {

    /** Removes all whitespace and the Unicode format characters listed in [isFormatChar]. */
    fun clean(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            if (isWhitespace(ch) || isFormatChar(ch)) continue
            out.append(ch)
        }
        return out.toString()
    }

    /** Removes only the format characters; spaces are kept (for display labels). */
    fun stripFormatChars(input: String): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            if (isFormatChar(ch)) continue
            out.append(ch)
        }
        return out.toString()
    }

    private fun isWhitespace(ch: Char): Boolean =
        ch.isWhitespace() || ch == ' ' || ch == ' ' || ch == ' '

    /**
     * U+200B–U+200F (zero-width and directional marks), U+202A–U+202E
     * (bidi embeddings and overrides), U+2060–U+2064 (word joiner and invisible
     * operators), U+2066–U+2069 (bidi isolates) and U+FEFF (BOM / ZWNBSP).
     */
    private fun isFormatChar(ch: Char): Boolean =
        ch in '​'..'‏' ||
            ch in '‪'..'‮' ||
            ch in '⁠'..'⁤' ||
            ch in '⁦'..'⁩' ||
            ch == '﻿'
}
