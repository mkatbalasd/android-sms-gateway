package me.capcom.smsgateway.modules.webhooks

import me.capcom.smsgateway.modules.webhooks.db.WebhookQueueRepository
import me.capcom.smsgateway.modules.webhooks.vm.WebhookQueueViewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val webhooksModule = module {
    singleOf(::WebHooksService)
    singleOf(::WebhookQueueRepository)
    singleOf(::WebhookPayloadStorage)
    factoryOf(::WebhookQueueProcessor)
    viewModelOf(::WebhookQueueViewModel)
}

val NAME = "webhooks"
