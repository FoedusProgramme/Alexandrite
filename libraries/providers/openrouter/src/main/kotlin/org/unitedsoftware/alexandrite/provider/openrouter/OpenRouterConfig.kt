package org.unitedsoftware.alexandrite.provider.openrouter

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.TimeoutConfig
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.chat.ChatApiSettings
import org.unitedsoftware.alexandrite.provider.common.requireEndpointIds
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret

@ConfigSection
@Serializable
internal class OpenRouterConfig(
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
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** Headers sent with every request. */
    val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    val discover: Boolean = true,
    /** Whether requests carry the agent's cache key as `prompt_cache_key` and `session_id`. */
    val promptCacheKey: Boolean = false,
    val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    init {
        settings()
    }

    fun settings(): EndpointSettings = EndpointSettings(
        baseUrl = baseUrl,
        apiKey = apiKey,
        headers = headers,
        models = models,
        discover = discover,
        turnContext = turnContext,
        timeouts = timeouts,
    )

    fun chat(): ChatApiSettings = ChatApiSettings(promptCacheKey)
}
