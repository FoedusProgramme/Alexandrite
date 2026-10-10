package org.foedusprogramme.alexandrite.agent.model

import org.foedusprogramme.alexandrite.agent.config.Agent
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import java.security.MessageDigest
import java.util.HexFormat

/** The model a turn calls, as its endpoint lists it. */
internal class SelectedModel(val ref: ModelRef, val endpoint: ModelEndpoint, val info: ModelInfo)

/** The model [ref] names, null when no endpoint lists it. */
internal suspend fun Endpoints.select(ref: ModelRef): SelectedModel? {
    val endpoint = endpoint(ref.endpoint) ?: return null
    return model(ref)?.let { SelectedModel(ref, endpoint, it) }
}

/** The options of [agent]'s requests to [model] with the effort [reasoning]. */
internal fun requestOptions(agent: Agent, model: ModelInfo, reasoning: ReasoningEffort?): ModelOptions =
    ModelOptions.builder()
        .maxOutputTokens(agent.config.maxOutputTokens ?: model.maxOutputTokens)
        .temperature(agent.config.temperature)
        .reasoning(reasoning)
        .parallelToolCalls(model.parallelToolCalls)
        .build()

/** The prompt cache key of the conversations at [key]: the first 32 hex digits of the SHA-256 of the printed key. */
internal fun cacheKey(key: AgentChatKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toString().toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest).take(CACHE_KEY_LENGTH)
}

private const val CACHE_KEY_LENGTH = 32
