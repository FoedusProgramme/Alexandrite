package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.provider.common.EndpointSettings
import org.foedusprogramme.alexandrite.provider.common.ModelConfig
import org.foedusprogramme.alexandrite.provider.common.TimeoutConfig
import org.foedusprogramme.alexandrite.provider.common.TurnContextSetting
import org.foedusprogramme.alexandrite.provider.common.requireEndpointIds
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.Secret

@ConfigSection
@Serializable
internal class DeepSeekConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

@Serializable
internal class EndpointConfig(
    val apiKey: Secret,
    /** The URL that `/chat/completions` and `/models` follow. */
    val baseUrl: String = "https://api.deepseek.com",
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    val discover: Boolean = true,
    val turnContext: TurnContextSetting = TurnContextSetting.BAKE,
    val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    init {
        settings()
    }

    fun settings(): EndpointSettings = EndpointSettings(
        baseUrl,
        apiKey,
        models = models,
        discover = discover,
        turnContext = turnContext,
        timeouts = timeouts,
    )
}
