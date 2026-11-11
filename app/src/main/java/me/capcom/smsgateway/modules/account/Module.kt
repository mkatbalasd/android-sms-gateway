package me.capcom.smsgateway.modules.account

import android.content.Context
import android.os.Build
import me.capcom.smsgateway.BuildConfig
import me.capcom.smsgateway.modules.encryption.EncryptionService
import me.capcom.smsgateway.modules.gateway.GatewaySettings
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
    // V2-L23: pause and resume of the phone, and the sweep of messages that waited too long.
    single { StaleSweep(get<PendingStore>(), get<AccountSettings>()) }
    factory<LocalCleanup> {
        AndroidLocalCleanup(
            get<Context>(),
            get<AccountSettings>(),
            get(),
            get(),
            get(),
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
