package me.capcom.smsgateway.modules.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.ui.dialogs.KeepAliveGuideDialogFragment

/** The phone side of [AppUnlink]. */
class AndroidLocalCleanup(
    context: Context,
    private val accountSettings: AccountSettings,
    private val gatewaySettings: GatewaySettings,
    private val settingsHelper: SettingsHelper,
    private val orchestrator: OrchestratorService,
    private val database: AppDatabase,
) : LocalCleanup {

    private val appContext: Context = context.applicationContext

    override suspend fun stopService() {
        withContext(Dispatchers.Main) { orchestrator.stop(appContext) }
    }

    override suspend fun purgeData() {
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                val db = database.openHelper.writableDatabase
                // First, nothing that was waiting may be sent later (a re-link must not send the old
                // account's messages); then the queues and the history of the old link go.
                db.execSQL("UPDATE message SET state = 'Failed' WHERE state IN ('Pending', 'Cancelling')")
                // Children first, so this does not depend on foreign-key cascades.
                for (table in PURGED_TABLES) {
                    db.execSQL("DELETE FROM $table")
                }
            }
        }
    }

    override suspend fun forgetLink() {
        gatewaySettings.registrationInfo = null
        gatewaySettings.clearContact()
        settingsHelper.autostart = false
        accountSettings.reset()
        KeepAliveGuideDialogFragment.forget(appContext)
    }

    private companion object {
        // Messages with their recipients and states, the inbox and its upload queue, the webhook
        // delivery queue and the log. Device keys, tokens and webhook definitions stay.
        val PURGED_TABLES = listOf(
            "recipientstate",
            "messagestate",
            "messagerecipient",
            "message",
            "incoming_messages",
            "gateway__inbox_upload",
            "webhook_queue",
            "logs_entries",
        )
    }
}
