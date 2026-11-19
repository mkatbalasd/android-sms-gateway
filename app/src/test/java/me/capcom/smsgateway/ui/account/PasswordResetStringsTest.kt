package me.capcom.smsgateway.ui.account

import me.capcom.smsgateway.R
import me.capcom.smsgateway.modules.account.IntlPhone
import me.capcom.smsgateway.modules.account.PhoneCountry
import me.capcom.smsgateway.modules.account.ResetFailure
import me.capcom.smsgateway.modules.account.ResetProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V2-L33: the reset copy exists in English and Arabic, and no sentence is about an account. */
internal class PasswordResetStringsTest {

    private fun resFile(dir: String): File = listOf(
        File("src/main/res/$dir/strings.xml"),
        File("app/src/main/res/$dir/strings.xml"),
    ).firstOrNull { it.exists() } ?: error("strings.xml of $dir not found")

    private fun resetStrings(dir: String): Map<String, String> {
        val text = resFile(dir).readText(Charsets.UTF_8)
        return Regex("""<string name="(account_btn_forgot|account_reset_[a-z_]+)"[^>]*>(.*?)</string>""")
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val arabicLetter = Regex("[ء-ي]")

    @Test
    fun bothLocalesHaveTheSameResetKeys() {
        val en = resetStrings("values")
        val ar = resetStrings("values-ar")
        assertTrue(en.size >= 20)
        assertEquals(en.keys, ar.keys)
    }

    @Test
    fun theArabicCopyIsArabicAndTheEnglishIsNot() {
        val en = resetStrings("values")
        val ar = resetStrings("values-ar")
        for ((key, value) in ar) assertTrue("$key is not Arabic", arabicLetter.containsMatchIn(value))
        for ((key, value) in en) assertFalse("$key is Arabic", arabicLetter.containsMatchIn(value))
        assertEquals("نسيت كلمة المرور", ar["account_btn_forgot"])
        assertEquals("Forgot password", en["account_btn_forgot"])
    }

    @Test
    fun placeholdersMatchBetweenLocales() {
        val en = resetStrings("values")
        val ar = resetStrings("values-ar")
        val placeholder = Regex("%\\d\\$[a-z]")
        for (key in en.keys) {
            assertEquals(key, placeholder.findAll(en.getValue(key)).map { it.value }.toList(),
                placeholder.findAll(ar.getValue(key)).map { it.value }.toList())
        }
    }

    @Test
    fun noResetSentenceSaysWhetherANumberHasAnAccount() {
        val banned = listOf(
            "not registered", "no account", "not found", "unknown number", "does not exist", "doesn't exist",
            "غير مسجّل", "غير مسجل", "لا يوجد حساب", "لا حساب", "غير موجود",
        )
        for (dir in listOf("values", "values-ar")) {
            for ((key, value) in resetStrings(dir)) {
                for (word in banned) assertFalse("$dir/$key says '$word'", value.contains(word, ignoreCase = true))
            }
        }
        // The code screen's sentence is conditional in both languages.
        assertTrue(resetStrings("values")["account_reset_code_neutral"]!!.startsWith("If the number is registered"))
        assertTrue(resetStrings("values-ar")["account_reset_code_neutral"]!!.contains("إن كان الرقم مسجّلاً"))
    }

    @Test
    fun everyProblemMapsToAString() {
        for (problem in ResetProblem.values()) {
            assertNotNull(ResetMessages.resourceFor(ResetFailure(problem)))
        }
        assertEquals(R.string.account_reset_err_rate_limit,
            ResetMessages.resourceFor(ResetFailure(ResetProblem.RATE_LIMIT)))
        assertEquals(R.string.account_reset_err_rate_limit_wait,
            ResetMessages.resourceFor(ResetFailure(ResetProblem.RATE_LIMIT, 600)))
    }

    @Test
    fun aWrongCodeNeverShowsAttemptsLeftOrExpiryOfTheAccount() {
        val rejected = ResetMessages.resourceFor(ResetFailure(ResetProblem.CODE_REJECTED))
        assertEquals(R.string.account_reset_err_code_rejected, rejected)
        assertNotEquals(R.string.account_err_otp_invalid_left, rejected)
        assertNotEquals(R.string.account_err_otp_expired, rejected)
    }

    @Test
    fun anImsiShapedNumberNeverReachesTheResetCall() {
        val yemen = PhoneCountry("YE", 967)
        val imsi = "421030000000000"
        assertNull(IntlPhone.e164(yemen, imsi))
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.required(yemen, imsi))
        assertEquals(IntlPhone.Result.Invalid, IntlPhone.required(null, "+$imsi"))
    }
}
