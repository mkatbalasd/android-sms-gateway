package me.capcom.smsgateway.modules.gateway.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import me.capcom.smsgateway.R
import me.capcom.smsgateway.helpers.SSEManager
import me.capcom.smsgateway.modules.account.PauseGate
import me.capcom.smsgateway.modules.connection.ConnectionService
import me.capcom.smsgateway.modules.events.ExternalEvent
import me.capcom.smsgateway.modules.events.ExternalEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.capcom.smsgateway.modules.gateway.GatewayService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.gateway.Heartbeat
import me.capcom.smsgateway.modules.ping.PingSettings
import me.capcom.smsgateway.modules.gateway.WorkScheduler
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import me.capcom.smsgateway.modules.notifications.NotificationsService
import me.capcom.smsgateway.modules.orchestrator.EventsRouter
import org.koin.android.ext.android.inject

class SSEForegroundService : Service() {
    private val settings: GatewaySettings by inject()
    private val scheduler: WorkScheduler by inject()

    private val eventsRouter by inject<EventsRouter>()

    private val notificationsSvc: NotificationsService by inject()
    private val logsService: LogsService by inject()
    private val connectionService: ConnectionService by inject()

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.javaClass.name)
        }
    }
    private val wifiLock: WifiManager.WifiLock by lazy {
        (getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            this.javaClass.name
        )
    }

    private val gatewayService: GatewayService by inject()
    private val pingSettings: PingSettings by inject()

    // CARD-21: an authenticated pull at start and every 300 s (floor 60), independent of
    // WorkManager, so the platform sees the phone well inside its 15-minute threshold.
    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartbeat by lazy {
        Heartbeat(
            heartbeatScope,
            configuredSeconds = { pingSettings.intervalSeconds },
            beat = { gatewayService.getNewMessages(this@SSEForegroundService) },
        )
    }

    private val sseManager by lazy {
        SSEManager(
            "${settings.serverUrl}/events",
            // Resolve the bearer token fresh on every connect() so account
            // replacement picks up the new credentials without recreating the
            // service. Returns null while unregistered, in which case connect()
            // tears down gracefully instead of throwing.
            tokenProvider = { settings.registrationInfo?.token }
        )
            .apply {
                onConnected = {
                    settings.recordContact()
                    Log.d("SSEForegroundService", "SSE connected, pulling pending messages")
                    try {
                        scheduler.pullMessages()
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        logsService.insert(
                            LogEntry.Priority.ERROR,
                            "SSEForegroundService",
                            "Failed to start PullMessagesWorker on connect",
                        )
                    }
                }
                onEvent = { event, data ->
                    settings.recordContact()
                    Log.d("SSEForegroundService", "$event: $data")

                    try {
                        processEvent(event, data)
                    } catch (e: Throwable) {
                        e.printStackTrace()

                        logsService.insert(
                            LogEntry.Priority.ERROR,
                            "SSEForegroundService",
                            "Failed to process event",
                            mapOf("event" to event, "data" to data)
                        )
                    }
                }
                // CARD V2-L19: quiet for over a minute: pull now, same pull as on connect,
                // so delivery never waits for WorkManager's 15-minute floor.
                onSilent = { onConnected?.invoke() }
                onWatchdogReconnect = { seconds ->
                    logsService.insert(
                        LogEntry.Priority.WARN,
                        "SSEForegroundService",
                        "SSE silent for ${seconds}s, reconnecting",
                    )
                }
            }
    }

    override fun onCreate() {
        super.onCreate()

        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
        if (!wifiLock.isHeld) {
            wifiLock.acquire()
        }

        // CARD V2-L19: replace the stream as soon as the network is back.
        connectionService.onNetworkAvailable = {
            // V2-L23: never reconnect while paused (the stream is torn down by the pause).
            if (!PauseGate.isPaused()) sseManager.reconnectNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationsSvc.makeNotification(
            this,
            NotificationsService.NOTIFICATION_ID_REALTIME_EVENTS,
            getString(R.string.listening_to_the_server_events)
        )

        startForeground(NotificationsService.NOTIFICATION_ID_REALTIME_EVENTS, notification)

        sseManager.connect()
        heartbeat.start()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun processEvent(event: String?, data: String) {
        val type = try {
            event?.let { ExternalEventType.valueOf(it) }
                ?: ExternalEventType.MessageEnqueued
        } catch (e: Throwable) {
            throw RuntimeException("Unknown event type: $event", e)
        }

        eventsRouter.route(
            ExternalEvent(
                type = type,
                data = data
            )
        )
    }

    override fun onDestroy() {
        heartbeat.stop()
        heartbeatScope.cancel()
        sseManager.disconnect()
        connectionService.onNetworkAvailable = null
        if (wifiLock.isHeld) {
            wifiLock.release()
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // V2-L23: a paused phone keeps no live stream (resume starts it again).
            if (PauseGate.isPaused()) return

            val intent = Intent(context, SSEForegroundService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SSEForegroundService::class.java))
        }
    }
}