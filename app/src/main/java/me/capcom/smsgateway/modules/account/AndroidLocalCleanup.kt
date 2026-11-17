package me.capcom.smsgateway.modules.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.helpers.SettingsHelper
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.SessionPassword
import me.capcom.smsgateway.modules.orchestrator.OrchestratorService
import me.capcom.smsgateway.modules.webhooks.WebhookPayloadStorage
import me.capcom.smsgateway.ui.dialogs.KeepAliveGuideDialogFragment

/** The phone side of [AppUnlink]. */
class AndroidLocalCleanup(
    context: Context,
    private val accountSettings: AccountSettings,
    private val pauseStore: DevicePauseStore,
    private val gatewaySettings: GatewaySettings,
    private val settingsHelper: SettingsHelper,
    private val orchestrator: OrchestratorService,
    private val database: AppDatabase,
    private val preferences: SharedPreferences,
    private val payloads: WebhookPayloadStorage,
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
        // CARD-21 round 7: the pause state moved to device-protected storage; it goes with the account.
        // rev-fork24-2 L3: a failing commit must not abort the rest of the unlink (the secrets,
        // payload files, CE copy and session password below). The pause file then keeps its
        // last state (a paused phone stays paused, one Resume recovers it).
        try {
            pauseStore.reset()
        } catch (e: Exception) {
            // rev-fork28-1 L3: the old copy was just cleared and the DE file keeps its mark
            // flag, so put the "paused" mark on the old copy directly (a later lost file
            // then still reads paused).
            try {
                accountSettings.pausedAtMs = DevicePauseStore.LEGACY_MARK
                accountSettings.resumedAtMs = null
            } catch (e2: Exception) {
                // continue
            }
        }
        KeepAliveGuideDialogFragment.forget(appContext)

        // CARD-21: what the old link left in device-protected storage must not reach the next
        // link: the webhook signing key, the encryption passphrase, the gateway tokens, and the
        // payload files of the webhook queue (its rows go in purgeData).
        withContext(Dispatchers.IO) {
            preferences.edit().also { editor -> LinkSecrets.forget { editor.remove(it) } }.commit()
            payloads.clearAll()

            // ...and from the credential-encrypted rollback copy of the preferences (same file
            // name, left in place by the migration). Unlinking happens unlocked; if it somehow
            // does not, there is nothing readable to clear.
            try {
                val ce = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
                ce.edit().also { editor -> LinkSecrets.forgetInRollbackCopy { editor.remove(it) } }.commit()
            } catch (e: Exception) {
                // locked: credential-encrypted storage is not available
            }
        }
        SessionPassword.forget()
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
