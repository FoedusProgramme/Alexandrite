package org.foedusprogramme.alexandrite.provider.openaicompatible

import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class OpenAiCompatibleProvider(config: OpenAiCompatibleConfig) :
    ModelProvider,
    Lifecycle {
    private val chats = config.endpoints.map { (id, endpoint) ->
        ChatEndpoint(EndpointId(id), endpoint, HttpTransport(endpoint.timeouts.timeouts()))
    }

    override val endpoints: List<ModelEndpoint> = chats

    override fun onDestroy() {
        chats.forEach(ChatEndpoint::close)
    }
}
