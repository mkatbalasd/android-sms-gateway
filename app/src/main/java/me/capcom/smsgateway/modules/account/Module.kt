package me.capcom.smsgateway.modules.account

import android.content.Context
import android.os.Build
import me.capcom.smsgateway.BuildConfig
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
    factory<LocalCleanup> {
        AndroidLocalCleanup(
            get<Context>(),
            get<AccountSettings>(),
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
