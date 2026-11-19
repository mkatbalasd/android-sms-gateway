package me.capcom.smsgateway.modules.retention

import android.content.Context
import me.capcom.smsgateway.helpers.DeviceStorage
import org.koin.dsl.module

val retentionModule = module {
    // CARD-24: device-protected, so the receiver can decide before the first unlock.
    single {
        ActiveLineStore(
            DeviceStorage.deContext(get<Context>())
                .getSharedPreferences(ActiveLineStore.FILE, Context.MODE_PRIVATE)
        )
    }
    single { RetentionService(get(), get(), get()) }
}
