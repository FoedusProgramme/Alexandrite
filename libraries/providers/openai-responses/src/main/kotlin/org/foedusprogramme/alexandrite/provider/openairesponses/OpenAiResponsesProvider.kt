package org.foedusprogramme.alexandrite.provider.openairesponses

import org.foedusprogramme.alexandrite.provider.common.responses.ResponsesEndpoint
import org.foedusprogramme.alexandrite.provider.common.responses.ResponsesFlavor
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class OpenAiResponsesProvider(config: OpenAiResponsesConfig) :
    ModelProvider,
    Lifecycle {
    private val responses = config.endpoints.map { (id, endpoint) ->
        ResponsesEndpoint(EndpointId(id), endpoint.settings(), ResponsesFlavor.STANDARD, endpoint.responses())
    }

    override val endpoints: List<ModelEndpoint> = responses

    override fun onDestroy() {
        responses.forEach(ResponsesEndpoint::close)
    }
}
