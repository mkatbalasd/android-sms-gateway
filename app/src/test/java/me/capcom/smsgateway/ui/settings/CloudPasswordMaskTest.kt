package me.capcom.smsgateway.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

internal class CloudPasswordMaskTest {

    private fun s(registered: Boolean, has: Boolean) =
        CloudPasswordMask.summary(registered, has, "NOTREG", "SET", "HIDDEN")

    @Test
    fun notRegistered() = assertEquals("NOTREG", s(registered = false, has = false))

    @Test
    fun notRegisteredWinsEvenWithPassword() = assertEquals("NOTREG", s(false, true))

    @Test
    fun registeredWithPasswordShowsSetOnly() = assertEquals("SET", s(true, true))

    @Test
    fun registeredWithoutPasswordShowsHidden() = assertEquals("HIDDEN", s(true, false))
}
