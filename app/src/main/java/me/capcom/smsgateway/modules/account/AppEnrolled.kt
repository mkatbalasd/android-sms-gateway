package me.capcom.smsgateway.modules.account

import me.capcom.smsgateway.modules.gateway.GatewaySettings

/**
 * True for an install that linked itself through an app session (the customer
 * phone). A phone linked by a QR or a typed code, such as the platform phone,
 * is not: it keeps every upstream screen.
 */
object AppEnrolled {
    fun check(accountSettings: AccountSettings, gatewaySettings: GatewaySettings): Boolean =
        accountSettings.linked && gatewaySettings.registrationInfo != null
}
