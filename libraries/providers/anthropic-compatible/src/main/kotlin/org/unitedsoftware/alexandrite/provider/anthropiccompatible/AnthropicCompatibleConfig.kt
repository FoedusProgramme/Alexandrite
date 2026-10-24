package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesSettings
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@ConfigSection
@Serializable
internal class AnthropicCompatibleConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<MessagesSettings>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<MessagesSettings>(
    "org.unitedsoftware.alexandrite.provider.anthropiccompatible.EndpointConfig",
    MessagesSettings.serializer(),
    baseUrl = "https://api.anthropic.com",
    wire = MessagesEndpoint.WIRE,
)
