package org.unitedsoftware.alexandrite.agent.model

import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/** The model a turn calls, as its endpoint lists it. */
internal class SelectedModel(val ref: ModelRef, val endpoint: ModelEndpoint, val info: ModelInfo)

/** The model [ref] names, null when no endpoint lists it. */
internal suspend fun Endpoints.select(ref: ModelRef): SelectedModel? {
    val endpoint = endpoint(ref.endpoint) ?: return null
    return model(ref)?.let { SelectedModel(ref, endpoint, it) }
}

/** The [text] that a side model answered and what its call took. */
internal class SideAnswer(val text: String, val usage: Usage)

/**
 * What this model answers to [input], written by [source], under [instructions] with its least reasoning in at most
 * [maxTokens] tokens; [call] wraps the call, such as in retries.
 */
internal suspend fun SelectedModel.ask(
    ids: RequestIds,
    source: String,
    input: String,
    instructions: List<PromptSection>,
    maxTokens: Int,
    temperature: Double? = null,
    call: suspend (suspend () -> ModelEvent.Completed) -> ModelEvent.Completed = { it() },
): SideAnswer {
    val options = ModelOptions.builder()
        .maxOutputTokens(minOf(maxTokens, info.maxOutputTokens ?: maxTokens))
        .reasoning(leastReasoning(info))
        .temperature(temperature)
        .build()
    val history = listOf(UserEntry(null, listOf(TextPart(input)), UserOrigin.SystemWritten(source)))
    val request = ModelRequest.builder(ref, history, 0, ids).instructions(instructions).options(options).build()
    val response = call { collectResponse(endpoint, request) }
    return SideAnswer(response.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }, response.usage)
}

/** The options of [agent]'s requests to [model] with the effort [reasoning]. */
internal fun requestOptions(agent: Agent, model: ModelInfo, reasoning: ReasoningEffort?): ModelOptions =
    ModelOptions.builder()
        .maxOutputTokens(outputReserve(agent, model))
        .temperature(agent.config.temperature)
        .reasoning(reasoning)
        .parallelToolCalls(model.parallelToolCalls)
        .build()

/**
 * The tokens [agent]'s requests to [model] leave for the answer: its config's, else the model's most but at most
 * [DEFAULT_MAX_OUTPUT_TOKENS], since a provider counts them against the context window; null when neither says.
 */
internal fun outputReserve(agent: Agent, model: ModelInfo): Int? =
    agent.config.maxOutputTokens ?: model.maxOutputTokens?.coerceAtMost(DEFAULT_MAX_OUTPUT_TOKENS)

/**
 * The least reasoning [model] takes, for a side request whose short answer a reasoning model could spend all its
 * tokens before writing: none where the model may skip it, else its lowest effort, null when it takes no effort.
 */
internal fun leastReasoning(model: ModelInfo): ReasoningEffort? = EFFORTS.firstOrNull { it in model.reasoningEfforts }

private val EFFORTS = listOf(
    ReasoningEffort.NONE,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

/** The answer tokens a request leaves unless the agent's config says otherwise. */
internal const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 32_768

/** The prompt cache key of the conversations at [key]: the first 32 hex digits of the SHA-256 of the printed key. */
internal fun cacheKey(key: AgentChatKey): String = sha256Hex(key.toString()).take(CACHE_KEY_LENGTH)

private const val CACHE_KEY_LENGTH = 32
