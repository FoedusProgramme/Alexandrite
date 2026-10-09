package org.foedusprogramme.alexandrite.provider.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.foedusprogramme.alexandrite.internal.http.HttpTimeouts
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.seconds

/** One configured endpoint: where it is, how it signs in and what the operator says about its models. */
public class EndpointSettings(
    /** The URL that the API's paths follow. */
    public val baseUrl: String,
    public val apiKey: Secret? = null,
    /** Headers sent with every request. */
    public val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    public val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    public val discover: Boolean = true,
    /** Whether requests carry the agent's cache key. */
    public val promptCacheKey: Boolean = false,
    public val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    public val timeouts: TimeoutConfig = TimeoutConfig(),
    /** The header that carries [apiKey]. */
    public val authHeader: String = "authorization",
    /** Headers of the wire API that the provider sets on every request itself. */
    public val providerHeaders: Set<String> = emptySet(),
) {
    init {
        require(isHttpUrl(baseUrl)) { "baseUrl must be an http or https URL without user info, query or fragment" }
        val reserved = RESERVED_HEADERS + providerHeaders.map { it.lowercase() }
        for (name in headers.keys) {
            require(name.isNotEmpty() && name.all { it in TOKEN }) { "headers holds a malformed header name" }
            require(name.lowercase() !in reserved) {
                "headers may not set ${name.lowercase()}, which the provider sets itself"
            }
        }
        require(apiKey == null || headers.keys.none { it.equals(authHeader, ignoreCase = true) }) {
            "headers may not set ${authHeader.lowercase()} when apiKey is set"
        }
        for (model in models.keys) {
            require(model.isNotEmpty() && model.none(Char::isISOControl)) { "models holds an empty or malformed id" }
        }
    }

    private companion object {
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

/** How an endpoint renders turn context. */
@Serializable(with = TurnContextSerializer::class)
public enum class TurnContextSetting(internal val id: String) {
    /** Into one request alone. */
    TRANSIENT("transient"),

    /** Into the turn's user entry. */
    BAKE("bake"),
}

internal object TurnContextSerializer : KSerializer<TurnContextSetting> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "org.foedusprogramme.alexandrite.provider.common.TurnContextSetting",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: TurnContextSetting) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): TurnContextSetting {
        val id = decoder.decodeString()
        return requireNotNull(TurnContextSetting.entries.firstOrNull { it.id == id }) {
            "turnContext must be \"transient\" or \"bake\""
        }
    }
}

/** What the operator says about one model, null where the backend or the provider decides. */
@Serializable
public class ModelConfig(
    public val displayName: String? = null,
    /** Used when the backend reports none. */
    public val contextWindow: Int? = null,
    /** Used when the backend reports none. */
    public val maxOutputTokens: Int? = null,
    public val nativeTools: Boolean? = null,
    public val parallelToolCalls: Boolean? = null,
    /** Media kinds such as `image`. */
    public val inputMedia: Set<String>? = null,
    /** Efforts such as `low` or `high`. */
    public val reasoningEfforts: Set<String>? = null,
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

    internal fun facts(id: String): ModelFacts = ModelFacts(
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

/** How long an endpoint waits for its backend. */
@Serializable
public class TimeoutConfig(
    public val connectSeconds: Long = 10,
    /** From sending a request until the first byte of its response body. */
    public val firstByteSeconds: Long = 300,
    /** The longest pause within a response body. */
    public val idleSeconds: Long = 120,
) {
    init {
        require(connectSeconds > 0 && firstByteSeconds > 0 && idleSeconds > 0) { "every timeout must be positive" }
    }

    internal fun timeouts(): HttpTimeouts =
        HttpTimeouts(connectSeconds.seconds, firstByteSeconds.seconds, idleSeconds.seconds)
}

/** Throws when one of [ids], the keys of a provider's `endpoints`, is no [EndpointId]. */
public fun requireEndpointIds(ids: Collection<String>) {
    for (id in ids) {
        require(runCatching { EndpointId(id) }.isSuccess) {
            "endpoints holds '$id', which is no endpoint id: an id is lowercase words of letters and digits, " +
                "each starting with a letter, joined by single hyphens, such as \"deepseek\""
        }
    }
}
