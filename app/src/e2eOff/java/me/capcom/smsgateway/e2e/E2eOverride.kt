package me.capcom.smsgateway.e2e

/**
 * Compiled into EVERY build type except `emulatorE2e` (build.gradle source sets). There is no
 * endpoint override here: the app talks to the production servers only. The `emulatorE2e`
 * variant has its own copy of this file under src/e2eOn. Keep the two in step; the release
 * guard (JVM test + the emulator-e2e workflow) fails closed when this one is not the one
 * shipped in a release build.
 */
object E2eOverride {
    /** Always null: no override exists in this build. */
    val stubBase: String? = null

    /** Always false: this build only accepts the built-in server in a link payload. */
    fun isStubServer(server: String): Boolean = false
}
