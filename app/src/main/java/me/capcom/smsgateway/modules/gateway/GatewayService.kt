package me.capcom.smsgateway.modules.gateway

import android.content.Context
import android.os.Build
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import me.capcom.smsgateway.domain.EntitySource
import me.capcom.smsgateway.domain.MessageContent
import me.capcom.smsgateway.domain.ProcessingState
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.device.DeviceService
import me.capcom.smsgateway.modules.events.EventBus
import me.capcom.smsgateway.modules.gateway.events.DeviceRegisteredEvent
import me.capcom.smsgateway.modules.gateway.inbox.InboxUploadService
import me.capcom.smsgateway.modules.gateway.inbox.InboxUploadWorker
import me.capcom.smsgateway.modules.gateway.services.SSEForegroundService
import me.capcom.smsgateway.modules.gateway.workers.PullMessagesWorker
import me.capcom.smsgateway.modules.gateway.workers.SettingsUpdateWorker
import me.capcom.smsgateway.modules.gateway.workers.WebhooksUpdateWorker
import me.capcom.smsgateway.modules.localserver.domain.SimCard
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.messages.MessagesService
import me.capcom.smsgateway.modules.messages.MessagesSettings
import me.capcom.smsgateway.modules.messages.data.SendParams
import me.capcom.smsgateway.modules.messages.data.SendRequest
import me.capcom.smsgateway.modules.receiver.data.InboxMessage
import me.capcom.smsgateway.services.PushService
import java.util.Date

class GatewayService(
    private val inboxUploadService: InboxUploadService,
    private val messagesService: MessagesService,
    private val settings: GatewaySettings,
    private val events: EventBus,
    private val logsService: LogsService,
    private val deviceService: DeviceService,
    private val scheduler: WorkScheduler,
) {
    private val eventsReceiver by lazy { EventsReceiver() }

    private var _api: GatewayApi? = null

    private val api
        get() = _api ?: GatewayApi(
            settings.serverUrl,
            settings.privateToken
        ).also { _api = it }

    //region Start, stop, etc...
    fun start(context: Context) {
        if (!settings.enabled) return

        PushService.register(context)
        PullMessagesWorker.start(context)
        WebhooksUpdateWorker.start(context)
        SettingsUpdateWorker.start(context)
        InboxUploadWorker.start(context)

        eventsReceiver.start()
    }

    /** CARD-21: before the first unlock only the events collector runs; no WorkManager. */
    fun startLocked() {
        if (!settings.enabled) return

        eventsReceiver.start()
    }

    fun stop(context: Context) {
        eventsReceiver.stop()

        InboxUploadWorker.stop(context)
        SSEForegroundService.stop(context)
        SettingsUpdateWorker.stop(context)
        WebhooksUpdateWorker.stop(context)
        PullMessagesWorker.stop(context)

        this._api = null
    }

    /**
     * V2-L23: «إيقاف مؤقت» stops only the pulling: the pull worker and the live stream.
     * The events receiver and the state reports stay up (state events have no replay).
     */
    fun pausePulling(context: Context) {
        SSEForegroundService.stop(context)
        PullMessagesWorker.stop(context)
    }

    fun isActiveLiveData(context: Context) = PullMessagesWorker.getStateLiveData(context)
    //endregion

    //region Account
    suspend fun getLoginCode(): GatewayApi.GetUserCodeResponse {
        val password = settings.password
            ?: throw IllegalStateException("Password is not set")

        return getLoginCodeWithPassword(password)
    }

    suspend fun getLoginCodeWithPassword(password: String): GatewayApi.GetUserCodeResponse {
        val username = settings.username
            ?: throw IllegalStateException("Username is not set")

        return api.getUserCode(username to password)
    }

    suspend fun changePassword(current: String, new: String) {
        val info = settings.registrationInfo
            ?: throw IllegalStateException("The device is not registered on the server")

        this.api.changeUserPassword(
            info.token,
            GatewayApi.PasswordChangeRequest(current, new)
        )

        settings.registrationInfo = info.copy(password = new)

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                info.login,
                new,
            )
        )
    }
    //endregion

    //region Device
    /**
     * @param nameSuffix Mnaseb SIM self-enrolment: the server's nonce. When given, the device
     * registers as `Mnaseb SIM/<model>/<nonce>`; otherwise the upstream `<MANUFACTURER>/<PRODUCT>`.
     */
    internal suspend fun registerDevice(
        context: Context,
        pushToken: String?,
        registerMode: RegistrationMode,
        nameSuffix: String? = null,
    ) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo
        val accessToken = settings?.token

        if (accessToken != null) {
            // if there's an access token, try to update push token
            try {
                updateDevice(context, pushToken)
                return
            } catch (e: ClientRequestException) {
                // if token is invalid, try to register new one
                if (e.response.status != HttpStatusCode.Unauthorized) {
                    throw e
                }
            }
        }

        try {
            val deviceName = DeviceNames.forRegistration(
                Build.MANUFACTURER,
                Build.PRODUCT,
                Build.MODEL ?: "",
                nameSuffix,
            )
            val simCards = SubscriptionsHelper.getActiveSimCards(context)
            val keyInfo = deviceService.ensureKey()
            val request = GatewayApi.DeviceRegisterRequest(
                deviceName,
                pushToken,
                simCards.toDTO(),
                publicKey = keyInfo?.publicKeyBase64,
                keyVersion = keyInfo?.keyVersion,
            )
            val response = when (registerMode) {
                RegistrationMode.Anonymous -> api.deviceRegister(request, null)
                // Mnaseb SIM: the single choke point for every code entry path.
                is RegistrationMode.WithCode -> api.deviceRegister(
                    request,
                    LoginCodeSanitizer.clean(registerMode.code)
                )
                is RegistrationMode.WithCredentials -> api.deviceRegister(
                    request,
                    registerMode.login to registerMode.password
                )
            }

            this.settings.fcmToken = pushToken
            this.settings.registrationInfo = response
            this.settings.recordContact()

            events.emit(
                DeviceRegisteredEvent.Success(
                    api.hostname,
                    response.login,
                    response.password,
                )
            )
        } catch (th: Throwable) {
            events.emit(
                DeviceRegisteredEvent.Failure(
                    api.hostname,
                    th.localizedMessage ?: th.message ?: th.toString()
                )
            )

            throw th
        }
    }

    internal suspend fun updateDevice(context: Context, pushToken: String?) {
        if (!settings.enabled) return

        val settings = settings.registrationInfo ?: return
        val accessToken = settings.token
        val simCards = SubscriptionsHelper.getActiveSimCards(context)
        val encryptionKey = deviceService.ensureKey()

        api.devicePatch(
            accessToken,
            GatewayApi.DevicePatchRequest(
                settings.id,
                pushToken,
                encryptionKey?.publicKeyBase64,
                encryptionKey?.keyVersion,
                simCards.toDTO(),
            )
        )

        this.settings.fcmToken = pushToken
        this.settings.recordContact()

        events.emit(
            DeviceRegisteredEvent.Success(
                api.hostname,
                settings.login,
                settings.password,
            )
        )
    }

    private fun List<SimCard>.toDTO(): List<GatewayApi.SimCard> = map {
        GatewayApi.SimCard(
            it.slotIndex,
            it.simNumber,
            it.phoneNumber.redact(),
            it.carrierName.redact(),
            it.iccid.redact(),
        )
    }

    sealed class RegistrationMode {
        object Anonymous : RegistrationMode()
        class WithCredentials(val login: String, val password: String) : RegistrationMode()
        class WithCode(val code: String) : RegistrationMode()
    }
    //endregion

    //region Messages
    internal suspend fun getNewMessages(context: Context) {
        if (!settings.enabled) return
        // V2-L23: the one sink of every pull (worker, push, SSE connect and watchdog, ping):
        // a paused phone neither pulls nor sends.
        if (me.capcom.smsgateway.modules.account.PauseGate.isPaused()) return
        val settings = settings.registrationInfo ?: return
        val processingOrder = when (messagesService.processingOrder) {
            MessagesSettings.ProcessingOrder.LIFO -> GatewayApi.ProcessingOrder.LIFO
            MessagesSettings.ProcessingOrder.FIFO -> GatewayApi.ProcessingOrder.FIFO
        }
        val messages = api.getMessages(settings.token, processingOrder)
        this.settings.recordContact()
        processPulled(
            messages,
            process = { processMessage(context, it) },
            log = { text, data -> logsService.insert(LogEntry.Priority.ERROR, MODULE_NAME, text, data) },
        )
    }

    // V2-L23, CARD-21 round 7: the device-protected store, readable before the first unlock.
    // Looked up lazily so this class keeps its constructor.
    private val pauseStore: me.capcom.smsgateway.modules.account.PauseStore by lazy {
        org.koin.core.context.GlobalContext.get().get<me.capcom.smsgateway.modules.account.PauseStore>()
    }

    private suspend fun processMessage(context: Context, message: GatewayApi.Message) {
        when (message.state) {
            GatewayApi.MessageState.Pending, null -> {}
            GatewayApi.MessageState.Cancelling -> {
                handleCloudCancel(message, messagesService::cancelMessage, ::sendState)
                return
            }

            GatewayApi.MessageState.Cancelled,
            GatewayApi.MessageState.Processed,
            GatewayApi.MessageState.Sent,
            GatewayApi.MessageState.Delivered,
            GatewayApi.MessageState.Failed -> return
        }

        val messageState = messagesService.getMessage(message.id)
        if (messageState != null) {
            scheduler.sendState(message.id)
            return
        }

        // V2-L23: a message that was held past the limit by the last pause is failed
        // before it is enqueued, so nothing can send it late.
        enqueueUnlessHeld(message, pauseStore, System.currentTimeMillis(), ::sendState) {
            messagesService.enqueueMessage(toSendRequest(message))
        }
    }

    private fun toSendRequest(message: GatewayApi.Message): SendRequest {
        return SendRequest(
            EntitySource.Cloud,
            me.capcom.smsgateway.modules.messages.data.Message(
                message.id,
                when (val content = message.content) {
                    is GatewayApi.MessageContent.Text -> MessageContent.Text(content.text)
                    is GatewayApi.MessageContent.Data -> MessageContent.Data(
                        content.data,
                        content.port
                    )
                    is GatewayApi.MessageContent.Mms -> MessageContent.Mms(
                        subject = content.subject,
                        text = content.text,
                        attachments = content.attachments.map {
                            MessageContent.Mms.Attachment(
                                contentType = it.contentType,
                                name = it.name,
                                data = it.data,
                            )
                        }
                    )
                },
                message.phoneNumbers,
                message.isEncrypted ?: false,
                message.createdAt ?: Date(),
            ),
            SendParams(
                message.withDeliveryReport ?: true,
                skipPhoneValidation = true,
                simNumber = message.simNumber,
                validUntil = message.validUntil,
                scheduleAt = message.scheduleAt,
                priority = message.priority,
            )
        )
    }

    internal suspend fun sendState(
        request: GatewayApi.MessagePatchRequest
    ) {
        val settings = settings.registrationInfo ?: return

        api.patchMessages(
            settings.token,
            listOf(request)
        )
    }
    //endregion

    //region Inbox
    fun enqueueInboxMessage(
        context: Context,
        message: InboxMessage,
        sender: String,
        recipient: String?,
        simNumber: Int?,
        date: Date,
    ) {
        if (!settings.enabled) {
            return
        }

        inboxUploadService.enqueue(
            context, message, sender, recipient, simNumber, date
        )
    }

    internal suspend fun uploadInbox(messages: List<GatewayApi.InboxMessage>) {
        val token = settings.registrationInfo?.token
            ?: return

        api.postInbox(token, messages)
    }
    //endregion

    //region Webhooks
    internal suspend fun getWebHooks(): List<GatewayApi.WebHook> {
        val settings = settings.registrationInfo
        return if (settings != null) {
            api.getWebHooks(settings.token)
        } else {
            emptyList()
        }
    }
    //endregion

    //region Settings
    internal suspend fun getSettings(): Map<String, *>? {
        val settings = settings.registrationInfo ?: return null

        return api.getSettings(settings.token).also { this.settings.recordContact() }
    }
    //endregion

    //region Utility
    suspend fun getPublicIP(): String {
        return GatewayApi(
            settings.serverUrl,
            settings.privateToken
        )
            .getDevice(settings.registrationInfo?.token)
            .externalIp
    }
    //endregion

    private fun String?.redact(): String? = when {
        this == null -> null
        length > 4 -> replaceRange(
            0,
            length - 4,
            "*".repeat(length - 4)
        )

        else -> "****"
    }
}

/**
 * Resolves a wire-level "Cancelling" instruction from the gateway (used by
 * [GatewayService.processMessage]). Cancelling is a server-side async-cancel
 * marker that is handled here and never persisted locally:
 * - [cancelMessage] succeeds (message found, still Pending): nothing else to do;
 * - [cancelMessage] throws [IllegalArgumentException] (message missing locally):
 *   report a Cancelled patch back to the server;
 * - [cancelMessage] throws [IllegalStateException] (already sent/cancelled):
 *   the actual outcome was or will be reported, stay silent.
 */
internal suspend fun handleCloudCancel(
    message: GatewayApi.Message,
    cancelMessage: suspend (String) -> Unit,
    sendCancelled: suspend (GatewayApi.MessagePatchRequest) -> Unit
) {
    try {
        cancelMessage(message.id)
    } catch (_: IllegalArgumentException) {
        // message not found locally — nothing to cancel
        sendCancelled(
            GatewayApi.MessagePatchRequest(
                message.id,
                ProcessingState.Cancelled,
                message.phoneNumbers.map {
                    GatewayApi.RecipientState(
                        it,
                        ProcessingState.Cancelled,
                        null
                    )
                },
                mapOf(ProcessingState.Cancelled to Date())
            )
        )
    } catch (_: IllegalStateException) {
        // message not in Pending state — already sent/cancelled
    }
}

/**
 * CARD-21 round 7: runs [process] for every pulled message; one failure never stops the
 * others. A failure is logged with the message id and the error class ONLY: never the
 * message (recipient numbers, text) and never an exception message or stack trace, which
 * can carry them. The log row is a visible, long-lived copy.
 */
internal suspend fun processPulled(
    messages: List<GatewayApi.Message>,
    process: suspend (GatewayApi.Message) -> Unit,
    log: (text: String, data: Map<String, String>) -> Unit,
) {
    for (message in messages) {
        try {
            process(message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (th: Throwable) {
            log(
                "Failed to process message",
                mapOf("messageId" to message.id, "exception" to th.javaClass.name),
            )
        }
    }
}

/**
 * V2-L23: the only way a pulled message reaches the send queue. Order:
 *
 * 1. CARD-21 round 7: a paused phone enqueues nothing, locked or unlocked. A pause state
 *    that cannot be read counts as paused (fail closed): [PauseStore.isPaused] throwing
 *    is NOT caught as "not paused". The message is left alone (it stays Pending on the
 *    platform, as when the pull itself is refused while paused).
 * 2. the drop rule: a message held too long by the last pause is failed, not enqueued;
 * 3. [enqueue], only for a message that passed both.
 */
internal suspend fun enqueueUnlessHeld(
    message: GatewayApi.Message,
    store: me.capcom.smsgateway.modules.account.PauseStore,
    nowMs: Long,
    sendFailed: suspend (GatewayApi.MessagePatchRequest) -> Unit,
    enqueue: suspend () -> Unit,
) {
    val paused = try {
        store.isPaused
    } catch (e: Exception) {
        true
    }
    if (paused) return
    if (handlePauseHold(message, store, nowMs, sendFailed)) return
    enqueue()
}

/**
 * V2-L23: the drop rule of the last pause, applied to a pulled message BEFORE it is
 * enqueued. A message the pause held longer than the limit is reported to the platform
 * as Failed with "TTL expired" (mapped to EXPIRED and refunded there) and never reaches
 * the send queue. Returns true when the message was dropped.
 */
internal suspend fun handlePauseHold(
    message: GatewayApi.Message,
    store: me.capcom.smsgateway.modules.account.PauseStore,
    nowMs: Long,
    sendFailed: suspend (GatewayApi.MessagePatchRequest) -> Unit
): Boolean {
    val createdAt = message.createdAt?.time ?: return false
    val drop = me.capcom.smsgateway.modules.account.PauseHold.dropOnPull(
        createdAtMs = createdAt,
        pausedAtMs = store.pausedAtMs,
        resumedAtMs = store.resumedAtMs,
        nowMs = nowMs,
        holdMaxMin = me.capcom.smsgateway.modules.account.PauseHold.holdMaxMin(store.holdMaxMin),
    )
    if (!drop) return false

    val reason = me.capcom.smsgateway.modules.account.StaleSweep.REASON
    sendFailed(
        GatewayApi.MessagePatchRequest(
            message.id,
            ProcessingState.Failed,
            message.phoneNumbers.map {
                GatewayApi.RecipientState(it, ProcessingState.Failed, reason)
            },
            mapOf(ProcessingState.Failed to Date(nowMs))
        )
    )
    return true
}
