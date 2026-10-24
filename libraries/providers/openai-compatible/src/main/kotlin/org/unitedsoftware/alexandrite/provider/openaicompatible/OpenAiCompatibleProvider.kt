package org.unitedsoftware.alexandrite.provider.openaicompatible

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider

@Singleton
@Contribute(ModelProvider::class)
internal class OpenAiCompatibleProvider(config: OpenAiCompatibleConfig) :
    EndpointProvider<ChatEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ChatEndpoint(id, endpoint.settings, ChatFlavor.STANDARD, endpoint.options)
        },
    )
