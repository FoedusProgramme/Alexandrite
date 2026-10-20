package org.unitedsoftware.alexandrite.provider.openrouter

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class OpenRouterProvider(config: OpenRouterConfig) :
    EndpointProvider<ChatEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ChatEndpoint(EndpointId(id), endpoint.settings, OPENROUTER_FLAVOR, endpoint.options)
        },
    )
