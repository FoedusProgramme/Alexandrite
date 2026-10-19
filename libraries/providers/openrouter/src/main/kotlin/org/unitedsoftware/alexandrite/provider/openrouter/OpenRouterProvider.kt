package org.unitedsoftware.alexandrite.provider.openrouter

import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class OpenRouterProvider(config: OpenRouterConfig) :
    ModelProvider,
    Lifecycle {
    private val chats = config.endpoints.map { (id, endpoint) ->
        ChatEndpoint(EndpointId(id), endpoint.settings, OPENROUTER_FLAVOR, endpoint.options)
    }

    override val endpoints: List<ModelEndpoint> = chats

    override fun onDestroy() {
        chats.forEach(ChatEndpoint::close)
    }
}
