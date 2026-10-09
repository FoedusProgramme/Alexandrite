package org.foedusprogramme.alexandrite.provider.openaicompatible

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
internal class OpenAiCompatibleConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

@Serializable
internal class EndpointConfig(
    /** The URL that `/chat/completions` and `/models` follow. */
    val baseUrl: String,
    val apiKey: Secret? = null,
    /** Headers sent with every request. */
    val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    val discover: Boolean = true,
    /** Whether requests carry the agent's cache key as `prompt_cache_key`. */
    val promptCacheKey: Boolean = false,
    val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    init {
        settings()
    }

    fun settings(): EndpointSettings =
        EndpointSettings(baseUrl, apiKey, headers, models, discover, promptCacheKey, turnContext, timeouts)
}
