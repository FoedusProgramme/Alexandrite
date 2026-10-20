package org.unitedsoftware.alexandrite.provider.openairesponses

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesEndpoint
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesFlavor
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@Singleton
@Contribute(ModelProvider::class)
internal class OpenAiResponsesProvider(config: OpenAiResponsesConfig) :
    EndpointProvider<ResponsesEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ResponsesEndpoint(EndpointId(id), endpoint.settings, ResponsesFlavor.STANDARD, endpoint.options)
        },
    )
