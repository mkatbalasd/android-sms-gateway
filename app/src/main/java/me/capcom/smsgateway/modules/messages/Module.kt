package me.capcom.smsgateway.modules.messages

import me.capcom.smsgateway.modules.account.PendingStore
import me.capcom.smsgateway.modules.messages.vm.MessageDetailsViewModel
import me.capcom.smsgateway.modules.messages.vm.MessagesListViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val messagesModule = module {
    singleOf(::MessagesRepository)
    singleOf(::MessagesService)
    single {
        MessageStateService(get(), get()).also { machine ->
            // CARD-24: the text goes when every recipient is in a final state
            machine.finalStateHook = { id ->
                get<me.capcom.smsgateway.modules.retention.RetentionService>()
                    .onMessageStateChanged(id)
            }
        }
    }
    single<PendingStore> { RoomPendingStore(get(), get()) { get<MessagesService>().sendGate } }
    viewModel { MessagesListViewModel(get()) }
    viewModel { MessageDetailsViewModel(get()) }
}

val MODULE_NAME = "messages"
