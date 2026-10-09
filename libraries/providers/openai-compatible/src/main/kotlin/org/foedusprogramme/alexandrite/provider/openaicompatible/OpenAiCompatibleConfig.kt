package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.internal.http.HttpTimeouts
import org.foedusprogramme.alexandrite.sdk.config.ConfigSection
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.seconds

@ConfigSection
@Serializable
internal class OpenAiCompatibleConfig(
    /** The endpoints by their ids. */
    val endpoints: Map<String, EndpointConfig> = emptyMap(),
) {
    init {
        for (id in endpoints.keys) {
            require(PluginIds.PATTERN.matches(id)) {
                "endpoints holds '$id', which is no endpoint id: an id is lowercase words of letters and digits, " +
                    "each starting with a letter, joined by single hyphens, such as \"deepseek\""
            }
        }
    }
}

@Serializable
internal class EndpointConfig(
    /** The URL that `/chat/completions` and `/models` follow. */
    val baseUrl: String,
    val apiKey: Secret? = null,
    val profile: String = Profile.GENERIC.id,
    /** Headers sent with every request. */
    val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether `models()` asks the backend for its models. */
    val discover: Boolean = true,
    /** Whether requests carry the agent's cache key as `prompt_cache_key`. */
    val promptCacheKey: Boolean = false,
    /** `transient` or `bake`, the profile's when null. */
    val turnContext: String? = null,
    val timeouts: TimeoutConfig = TimeoutConfig(),
) {
    init {
        require(isHttpUrl(baseUrl)) { "baseUrl must be an http or https URL without user info, query or fragment" }
        require(Profile.of(profile) != null) {
            "profile must be one of ${Profile.entries.joinToString { "\"${it.id}\"" }}"
        }
        require(turnContext == null || turnContext in TURN_CONTEXT) { "turnContext must be \"transient\" or \"bake\"" }
        for (name in headers.keys) {
            require(name.isNotEmpty() && name.all { it in TOKEN }) { "headers holds a malformed header name" }
            require(name.lowercase() !in RESERVED_HEADERS) {
                "headers may not set ${name.lowercase()}, which the provider sets itself"
            }
        }
        require(apiKey == null || headers.keys.none { it.equals("authorization", ignoreCase = true) }) {
            "headers may not set authorization when apiKey is set"
        }
        for (model in models.keys) {
            require(model.isNotEmpty() && model.none(Char::isISOControl)) { "models holds an empty or malformed id" }
        }
    }

    /** Whether turn context goes into one request alone. */
    val turnContextTransient: Boolean get() = turnContext?.let { it == "transient" } ?: !profileOf().bakesTurnContext

    fun profileOf(): Profile = Profile.of(profile)!!

    private companion object {
        val TURN_CONTEXT = setOf("transient", "bake")
        val RESERVED_HEADERS =
            setOf("accept", "connection", "content-length", "content-type", "expect", "host", "upgrade")
        val TOKEN = ('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()

        fun isHttpUrl(text: String): Boolean = try {
            val uri = URI(text)
            uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
        } catch (e: URISyntaxException) {
            false
        }
    }
}

/** What the operator says about one model, null where the backend or the profile decides. */
@Serializable
internal class ModelConfig(
    val displayName: String? = null,
    /** Used when the backend reports none. */
    val contextWindow: Int? = null,
    /** Used when the backend reports none. */
    val maxOutputTokens: Int? = null,
    val nativeTools: Boolean? = null,
    val parallelToolCalls: Boolean? = null,
    /** Media kinds such as `image`. */
    val inputMedia: Set<String>? = null,
    /** Efforts such as `low` or `high`. */
    val reasoningEfforts: Set<String>? = null,
) {
    init {
        require(contextWindow == null || contextWindow > 0) { "contextWindow must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        inputMedia?.forEach { kind ->
            require(MediaKind.entries.any { it.id == kind }) {
                "inputMedia holds '$kind', which is none of ${MediaKind.entries.joinToString()}"
            }
        }
        reasoningEfforts?.forEach { effort ->
            require(ReasoningEffort.entries.any { it.id == effort }) {
                "reasoningEfforts holds '$effort', which is none of ${ReasoningEffort.entries.joinToString()}"
            }
        }
    }

    fun facts(id: String): ModelFacts = ModelFacts(
        id = id,
        displayName = displayName,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        nativeTools = nativeTools,
        parallelToolCalls = parallelToolCalls,
        inputMedia = inputMedia?.mapTo(mutableSetOf(), MediaKind::of),
        reasoningEfforts = reasoningEfforts?.mapTo(mutableSetOf(), ReasoningEffort::of),
    )
}

@Serializable
internal class TimeoutConfig(
    val connectSeconds: Long = 10,
    /** From sending a request until the first byte of its response body. */
    val firstByteSeconds: Long = 300,
    /** The longest pause within a response body. */
    val idleSeconds: Long = 120,
) {
    init {
        require(connectSeconds > 0 && firstByteSeconds > 0 && idleSeconds > 0) { "every timeout must be positive" }
    }

    fun timeouts(): HttpTimeouts = HttpTimeouts(connectSeconds.seconds, firstByteSeconds.seconds, idleSeconds.seconds)
}
