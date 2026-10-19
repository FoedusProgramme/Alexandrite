package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.TimeoutConfig
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesSettings
import org.unitedsoftware.alexandrite.provider.common.requireEndpointIds
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret

@ConfigSection
@Serializable
internal class AnthropicCompatibleConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
) {
    init {
        requireEndpointIds(endpoints.keys)
    }
}

@Serializable
internal class EndpointConfig(
    val apiKey: Secret? = null,
    /** The URL that `/v1/messages` and `/v1/models` follow. */
    val baseUrl: String = "https://api.anthropic.com",
    /** Headers sent with every request. */
    val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    val discover: Boolean = true,
    /** Whether requests mark cache breakpoints. */
    val promptCaching: Boolean = true,
    /** Beta features that every request asks for in `anthropic-beta`. */
    val betas: List<String> = emptyList(),
    val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    /** Whether the endpoint's models take turn-scoped system messages, which then carry trusted turn context. */
    val turnScopedSystem: Boolean = false,
    /** Enables the backend's server-side web search tool. */
    val nativeSearch: Boolean = false,
    val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    init {
        settings()
        messages()
    }

    fun settings(): EndpointSettings = EndpointSettings(
        baseUrl,
        apiKey,
        headers,
        models,
        discover,
        turnContext = turnContext,
        timeouts = timeouts,
        authHeader = MessagesEndpoint.AUTH_HEADER,
        providerHeaders = MessagesEndpoint.PROVIDER_HEADERS,
    )

    fun messages(): MessagesSettings = MessagesSettings(promptCaching, betas, turnScopedSystem, nativeSearch)
}
