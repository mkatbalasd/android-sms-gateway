package me.capcom.smsgateway.ui.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.capcom.smsgateway.helpers.KeepAliveGuideState
import me.capcom.smsgateway.modules.account.AppClient
import me.capcom.smsgateway.modules.account.KeepAliveChecklist

/** What the keeper ticked in the keep-alive guide, as `PATCH /v1/app/device {keep_alive_checklist}`. */
object KeepAliveReport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The three rows of the guide; an unknown battery state counts as not set. */
    fun checklist(state: KeepAliveGuideState) = KeepAliveChecklist(
        battery = state.battery == KeepAliveGuideState.Row.ON,
        boot = state.boot == KeepAliveGuideState.Row.ON,
        oem = state.oem == KeepAliveGuideState.Row.ON,
    )

    /** Reports when [checklist] differs from what was last reported ([last]). Returns true when it was sent. */
    suspend fun sendIfChanged(
        client: AppClient,
        checklist: KeepAliveChecklist,
        last: KeepAliveChecklist?,
    ): Boolean {
        if (checklist == last) return false
        send(client, checklist)
        return true
    }

    /** Best effort: a failed report is retried at the next change, and never shown. */
    suspend fun send(client: AppClient, checklist: KeepAliveChecklist) {
        try {
            client.keepAlive(checklist)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to show: the home card stays until the server has seen the phone.
        }
    }

    /** For a screen that is about to close. */
    fun sendInBackground(client: AppClient, checklist: KeepAliveChecklist) {
        scope.launch { send(client, checklist) }
    }
}
