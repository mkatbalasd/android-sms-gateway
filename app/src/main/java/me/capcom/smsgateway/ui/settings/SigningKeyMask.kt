package me.capcom.smsgateway.ui.settings

/** Never renders any character of the webhook signing key. */
internal object SigningKeyMask {
    fun summary(key: String?, setText: String, notSetText: String): String =
        if (key.isNullOrEmpty()) notSetText else setText
}
