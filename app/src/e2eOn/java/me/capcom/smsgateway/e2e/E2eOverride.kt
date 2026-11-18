package me.capcom.smsgateway.e2e

/**
 * ONLY in the `emulatorE2e` build type (never in release, debug, debugInsecure or insecure):
 * points the app at the e2e stub server that runs on the CI host, reached from the emulator
 * through the host alias 10.0.2.2. Compile-time, not an intent extra or a setting, so there is
 * nothing a user, another app or a remote import can switch on. The marker string below is
 * what the CI release guard greps for to prove this class is absent from a release build.
 */
object E2eOverride {
    const val MARKER = "E2E_STUB_OVERRIDE_ACTIVE"
    const val STUB_BASE = "http://10.0.2.2:8787"

    val stubBase: String? = STUB_BASE

    /** The stub's gateway URL is the only non-https server a link payload may name here. */
    fun isStubServer(server: String): Boolean =
        server.trimEnd('/') == "$STUB_BASE/api/mobile/v1"
}
