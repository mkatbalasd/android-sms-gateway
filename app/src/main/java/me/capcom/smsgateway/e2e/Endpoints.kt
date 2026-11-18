package me.capcom.smsgateway.e2e

import me.capcom.smsgateway.modules.account.AccountApi
import me.capcom.smsgateway.modules.gateway.GatewaySettings

/**
 * The two server base URLs the app talks to. Production constants unless the build carries an
 * [E2eOverride] (only the `emulatorE2e` build type does). The constants themselves
 * ([AccountApi.DEFAULT_BASE_URL], [GatewaySettings.PUBLIC_URL]) are never touched.
 */
object Endpoints {
    val accountBase: String
        get() = E2eOverride.stubBase?.let { "$it/v1" } ?: AccountApi.DEFAULT_BASE_URL

    val gatewayBase: String
        get() = E2eOverride.stubBase?.let { "$it/api/mobile/v1" } ?: GatewaySettings.PUBLIC_URL
}
