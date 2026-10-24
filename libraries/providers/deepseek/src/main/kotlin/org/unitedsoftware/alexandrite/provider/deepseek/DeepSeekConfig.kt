package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.NoOptions
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

@ConfigSection
@Serializable
internal class DeepSeekConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<NoOptions>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<NoOptions>(
    "org.unitedsoftware.alexandrite.provider.deepseek.EndpointConfig",
    NoOptions.serializer(),
    baseUrl = "https://api.deepseek.com",
    keyRequired = true,
    turnContext = TurnContextSetting.BAKE,
)
