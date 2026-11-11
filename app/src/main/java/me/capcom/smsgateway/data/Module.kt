package me.capcom.smsgateway.data

import me.capcom.smsgateway.helpers.DeviceStorage
import org.koin.dsl.module

val dbModule = module {
    // CARD-21: the database lives in device-protected storage.
    single { AppDatabase.getDatabase(DeviceStorage.deContext(get())) }
    single { get<AppDatabase>().messagesDao() }
    single { get<AppDatabase>().incomingMessagesDao() }
    single { get<AppDatabase>().webhooksDao() }
    single { get<AppDatabase>().webhookQueueDao() }
    single { get<AppDatabase>().inboxUploadDao() }
    single { get<AppDatabase>().logDao() }
    single { get<AppDatabase>().tokensDao() }
    single { get<AppDatabase>().deviceKeysDao() }
}