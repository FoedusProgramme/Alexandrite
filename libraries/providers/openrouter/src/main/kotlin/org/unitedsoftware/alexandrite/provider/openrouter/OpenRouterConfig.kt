package org.unitedsoftware.alexandrite.provider.openrouter

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.chat.ChatApiSettings
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@ConfigSection
@Serializable
internal class OpenRouterConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<ChatApiSettings>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<ChatApiSettings>(
    "org.unitedsoftware.alexandrite.provider.openrouter.EndpointConfig",
    ChatApiSettings.serializer(),
    baseUrl = "https://openrouter.ai/api/v1",
    keyRequired = true,
)
