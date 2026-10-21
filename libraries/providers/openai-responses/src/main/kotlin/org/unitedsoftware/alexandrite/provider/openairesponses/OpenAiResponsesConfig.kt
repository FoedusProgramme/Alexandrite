package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesSettings
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@ConfigSection
@Serializable
internal class OpenAiResponsesConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<ResponsesSettings>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<ResponsesSettings>(
    "org.unitedsoftware.alexandrite.provider.openairesponses.EndpointConfig",
    ResponsesSettings.serializer(),
    baseUrl = "https://api.openai.com/v1",
)
