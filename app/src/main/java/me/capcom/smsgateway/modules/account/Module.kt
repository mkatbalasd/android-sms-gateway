package me.capcom.smsgateway.modules.account

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.modules.encryption.EncryptionService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
import me.capcom.smsgateway.modules.logs.LogsService
import me.capcom.smsgateway.modules.logs.db.LogEntry
import org.koin.dsl.module

val accountModule = module {
    // One encrypted store, opened once.
    single { AccountSettings(AccountStorageFactory.create(get<Context>())) }
    single { AccountApi() }
    single<AccountBackend> { get<AccountApi>() }
    single<AppBackend> { get<AccountApi>() }
    single {
        AccountRepository(
            get<AccountBackend>(),
            get<AccountSettings>(),
            DeviceProfile(
                model = Build.MODEL ?: "",
                os = "Android " + (Build.VERSION.RELEASE ?: ""),
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
    }
    single<AppClient> { AppRepository(get<AppBackend>(), get<AccountRepository>()) }
    // V2-L23 + CARD-21 round 7: the pause state lives in device-protected storage (readable
    // before the first unlock). AccountSettings is only the source of the one-time copy,
    // asked for lazily and only while unlocked.
    single {
        DevicePauseStore.create(
            get<Context>(),
            onRepair = { reason ->
                // a WARN with a fixed reason text, no numbers, no message content
                get<LogsService>().insert(LogEntry.Priority.WARN, "pause", reason)
                // rev-fork24-1 L6: a repair paused the phone without telling the platform;
                // end what it already holds past the limit now (off the caller's thread,
                // because the store is mid-read here). A no-op when not paused.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        get<StaleSweep>().sweepWhilePaused()
                    } catch (e: Exception) {
                        // the next refresh or the resume sweeps again
                    }
                }
            },
        ) { get<AccountSettings>() }
    }
    single<PauseStore> { get<DevicePauseStore>() }
    // V2-L23: pause and resume of the phone, and the sweep of messages that waited too long.
    single { StaleSweep(get<PendingStore>(), get<PauseStore>(), get<StateReporter>()) }
    factory<LocalCleanup> {
        AndroidLocalCleanup(
            get<Context>(),
            get<AccountSettings>(),
            get<DevicePauseStore>(),
            get(),
            get(),
            get(),
            get(),
            get<android.content.SharedPreferences>(),
            get(),
        )
    }
    factory { AppUnlink(get<AppClient>(), get<LocalCleanup>()) }
    factory {
        val gateway = get<GatewaySettings>()
        val encryption = get<EncryptionService>()
        DeviceReattach(
            get<AppClient>(),
            get<AccountSettings>(),
            deviceId = { gateway.deviceId },
            // The same E2E decrypt as for E2E messages to this phone:
            // "$rsa-oaep-aes-256-gcm$v=1$k=N$<RSA-OAEP key>$<iv>$<AES-GCM text>". A challenge
            // without the leading algorithm tag is the same scheme without it.
            decrypt = { ciphertext ->
                encryption.decrypt(
                    if (ciphertext.startsWith("$")) ciphertext else "\$rsa-oaep-aes-256-gcm\$$ciphertext"
                )
            },
        )
    }
    factory {
        AppEnrolmentSteps(
            get<Context>(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
}
