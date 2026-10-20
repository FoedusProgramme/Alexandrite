package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class AnthropicCompatibleProvider(config: AnthropicCompatibleConfig) :
    EndpointProvider<MessagesEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            MessagesEndpoint(EndpointId(id), endpoint.settings, MessagesFlavor.STANDARD, endpoint.options)
        },
    )
