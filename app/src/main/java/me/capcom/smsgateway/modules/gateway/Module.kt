package me.capcom.smsgateway.modules.gateway

import me.capcom.smsgateway.modules.account.StateReporter
import me.capcom.smsgateway.modules.gateway.inbox.InboxUploadRepository
import me.capcom.smsgateway.modules.gateway.inbox.InboxUploadService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val gatewayModule = module {
    singleOf(::GatewayService)
    singleOf(::InboxUploadRepository)
    singleOf(::InboxUploadService)
    single<StateReporter> { WorkerStateReporter(get<WorkScheduler>()) }
}

internal const val MODULE_NAME = "gateway"
