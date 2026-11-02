package me.capcom.smsgateway.modules.gateway

import java.net.URI

/**
 * Mnaseb SIM: what the first-run screen does with each input. A QR scan or a
 * deep link is delivered from outside the app (any app or web page can fire
 * a mnaseb-sim:// link), so a payload alone never registers: it only asks the
 * keeper "Link this phone to {t}?", and only [onConfirm] (the keeper's tap on
 * Continue) yields [Step.Register]. A code the keeper typed or pasted is
 * already the keeper's own explicit action.
 */
object LinkFlow {

    /** Display cap for the untrusted label (payload v1 caps `t` at 40). */
    const val MAX_LABEL_DISPLAY = 40

    sealed class Step {
        data class ShowError(val error: LinkPayload.Error) : Step()
        data class Confirm(val code: String, val label: String?, val host: String) : Step()
        data class Register(val code: String, val label: String?) : Step()
        object CodeRequired : Step()
    }

    /** QR scan or deep link: never [Step.Register]. */
    fun onPayload(result: LinkPayload.Result, serverUrl: String): Step = when (result) {
        is LinkPayload.Result.Failed -> Step.ShowError(result.error)
        is LinkPayload.Result.Ok -> Step.Confirm(
            result.code,
            displayLabel(result.label),
            hostOf(serverUrl),
        )
    }

    /** The keeper tapped Continue on the confirmation. */
    fun onConfirm(step: Step.Confirm): Step = Step.Register(step.code, step.label)

    /** The keeper typed or pasted a code and tapped Continue. */
    fun onManualCode(input: String): Step {
        val code = LoginCodeSanitizer.clean(input)
        return if (code.isEmpty()) Step.CodeRequired else Step.Register(code, null)
    }

    /**
     * The label is untrusted text: format characters and other control
     * characters are removed, whitespace runs collapsed, length capped.
     */
    fun displayLabel(label: String?): String? {
        if (label == null) return null
        val cleaned = LoginCodeSanitizer.stripFormatChars(label)
            .replace(Regex("\\s+"), " ")
            .filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_LABEL_DISPLAY) cleaned.take(MAX_LABEL_DISPLAY) else cleaned
    }

    private fun hostOf(serverUrl: String): String =
        try {
            URI(serverUrl).host ?: serverUrl
        } catch (e: Exception) {
            serverUrl
        }
}
