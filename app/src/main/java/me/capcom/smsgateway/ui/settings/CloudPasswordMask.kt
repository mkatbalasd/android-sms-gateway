package me.capcom.smsgateway.ui.settings

/** Never renders any character of the cloud-account password. */
internal object CloudPasswordMask {
    fun summary(
        registered: Boolean,
        hasPassword: Boolean,
        notRegisteredText: String,
        setText: String,
        hiddenText: String,
    ): String = when {
        !registered -> notRegisteredText
        hasPassword -> setText
        else -> hiddenText
    }
}
