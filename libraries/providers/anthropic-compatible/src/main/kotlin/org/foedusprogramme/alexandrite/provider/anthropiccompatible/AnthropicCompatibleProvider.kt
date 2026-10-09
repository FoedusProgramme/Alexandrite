package org.foedusprogramme.alexandrite.provider.anthropiccompatible

import org.foedusprogramme.alexandrite.provider.common.messages.MessagesEndpoint
import org.foedusprogramme.alexandrite.provider.common.messages.MessagesFlavor
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class AnthropicCompatibleProvider(config: AnthropicCompatibleConfig) :
    ModelProvider,
    Lifecycle {
    private val messages = config.endpoints.map { (id, endpoint) ->
        MessagesEndpoint(EndpointId(id), endpoint.settings(), MessagesFlavor.STANDARD, endpoint.messages())
    }

    override val endpoints: List<ModelEndpoint> = messages

    override fun onDestroy() {
        messages.forEach(MessagesEndpoint::close)
    }
}
