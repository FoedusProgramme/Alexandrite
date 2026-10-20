package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesSettings
import org.unitedsoftware.alexandrite.provider.common.requireEndpointIds
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class AnthropicCompatibleConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        String,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<MessagesSettings>,
        > =
        emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

internal object EndpointSerializer : EndpointConfigSerializer<MessagesSettings>(
    "org.unitedsoftware.alexandrite.provider.anthropiccompatible.EndpointConfig",
    MessagesSettings.serializer(),
    baseUrl = "https://api.anthropic.com",
    authHeader = MessagesEndpoint.AUTH_HEADER,
    providerHeaders = MessagesEndpoint.PROVIDER_HEADERS,
)
