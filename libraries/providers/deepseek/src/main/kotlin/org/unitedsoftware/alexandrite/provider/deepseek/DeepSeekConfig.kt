package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.NoOptions
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.requireEndpointIds
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection

@ConfigSection
@Serializable
internal class DeepSeekConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<
        String,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<NoOptions>,
        > =
        emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

internal object EndpointSerializer : EndpointConfigSerializer<NoOptions>(
    "org.unitedsoftware.alexandrite.provider.deepseek.EndpointConfig",
    NoOptions.serializer(),
    baseUrl = "https://api.deepseek.com",
    keyRequired = true,
    turnContext = TurnContextSetting.BAKE,
)
