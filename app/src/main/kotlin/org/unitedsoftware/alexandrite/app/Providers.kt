package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * A model provider: its config [id] below `providers`, its [title], the [endpoint] it is named by default, its
 * [defaultUrl] (null when the operator names one), the path of its models request and the variable of its key.
 */
internal class ProviderKind(
    val id: String,
    val title: String,
    val endpoint: String,
    val defaultUrl: String?,
    val modelsPath: String,
    val keyVariable: String,
    /** Whether its config needs a key. */
    val keyRequired: Boolean,
    /** Whether its models request is signed as the Anthropic API signs it. */
    val anthropic: Boolean = false,
)

/** The providers in the order `init` offers them. */
internal val PROVIDER_KINDS: List<ProviderKind> = listOf(
    ProviderKind("deepseek", "DeepSeek", "deepseek", "https://api.deepseek.com", "/models", "DEEPSEEK_API_KEY", true),
    ProviderKind(
        "openrouter",
        "OpenRouter",
        "openrouter",
        "https://openrouter.ai/api/v1",
        "/models",
        "OPENROUTER_API_KEY",
        true,
    ),
    ProviderKind(
        "openai-compatible",
        "OpenAI-compatible",
        "compatible",
        null,
        "/models",
        "OPENAI_COMPATIBLE_API_KEY",
        false,
    ),
    ProviderKind(
        "anthropic-compatible",
        "Anthropic-compatible",
        "anthropic",
        "https://api.anthropic.com",
        "/v1/models",
        "ANTHROPIC_API_KEY",
        false,
        anthropic = true,
    ),
    ProviderKind("lmstudio", "LM Studio", "lmstudio", "http://localhost:1234/v1", "/models", "LMSTUDIO_API_KEY", false),
    ProviderKind(
        "openai-responses",
        "OpenAI Responses",
        "openai",
        "https://api.openai.com/v1",
        "/models",
        "OPENAI_API_KEY",
        false,
    ),
)

internal fun providerKind(id: String): ProviderKind? = PROVIDER_KINDS.firstOrNull { it.id == id }

internal fun requireProvider(id: String): ProviderKind = providerKind(id)
    ?: throw UsageException(
        "usage.init_provider",
        "provider" to id,
        "providers" to PROVIDER_KINDS.joinToString { it.id },
    )

/** The headers that sign a request of [kind] with [key], none without a key. */
internal fun keyHeaders(kind: ProviderKind, key: String?): Map<String, String> = when {
    key == null -> emptyMap()
    kind.anthropic -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
    else -> mapOf("Authorization" to "Bearer $key")
}

/** What a models request found. */
internal sealed interface Listing {
    class Models(val ids: List<String>) : Listing

    class Refused(val reason: Text) : Listing
}

/** The models the endpoint of [kind] at [base] lists for [key]. */
internal fun listModels(cli: Cli, kind: ProviderKind, base: String, key: String?): Listing {
    val fetched = try {
        cli.machine.get("${base.trimEnd('/')}${kind.modelsPath}", keyHeaders(kind, key))
    } catch (e: IOException) {
        val reason = (e.message ?: "$e").let { if (key != null && key.length >= 4) it.replace(key, "***") else it }
        return Listing.Refused(Text("models.unreachable", "base" to base, "reason" to reason))
    }
    if (fetched.status == 401 || fetched.status == 403) {
        return Listing.Refused(Text("models.key_refused", "base" to base, "status" to fetched.status))
    }
    if (fetched.status !in 200..299) {
        return Listing.Refused(Text("models.http", "base" to base, "status" to fetched.status))
    }
    val data = try {
        (Json.parseToJsonElement(fetched.body) as? JsonObject)?.get("data") as? JsonArray
    } catch (e: IllegalArgumentException) {
        null
    } ?: return Listing.Refused(Text("models.no_list", "base" to base))
    return Listing.Models(data.mapNotNull { (it as? JsonObject)?.get("id").text }.distinct())
}
