package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.requireEndpointIds
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesSettings
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class OpenAiResponsesConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        String,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<ResponsesSettings>,
        > =
        emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

internal object EndpointSerializer : EndpointConfigSerializer<ResponsesSettings>(
    "org.unitedsoftware.alexandrite.provider.openairesponses.EndpointConfig",
    ResponsesSettings.serializer(),
    baseUrl = "https://api.openai.com/v1",
)
