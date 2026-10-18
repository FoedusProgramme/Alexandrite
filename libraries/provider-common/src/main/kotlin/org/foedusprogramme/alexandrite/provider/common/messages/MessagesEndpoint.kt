package org.foedusprogramme.alexandrite.provider.common.messages

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpResponseStream
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.provider.common.EndpointCore
import org.foedusprogramme.alexandrite.provider.common.EndpointSettings
import org.foedusprogramme.alexandrite.provider.common.ErrorDetail
import org.foedusprogramme.alexandrite.provider.common.modelException
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.web.WebQuery
import org.foedusprogramme.alexandrite.sdk.web.WebSearchResult

/** What an endpoint of the Messages API does beyond its [EndpointSettings]. */
public class MessagesSettings(
    /** Whether requests mark cache breakpoints. */
    public val promptCaching: Boolean = true,
    /** Beta features that every request asks for in `anthropic-beta`. */
    public val betas: List<String> = emptyList(),
    /** Whether the endpoint's models take turn-scoped system messages, which then carry trusted turn context. */
    public val turnScopedSystem: Boolean = false,
    /** Whether the endpoint's models search the web with the backend's own search tool ([ModelInfo.webSearch]). */
    public val nativeSearch: Boolean = false,
) {
    init {
        for (beta in betas) {
            require(beta.isNotEmpty() && beta.none { it == ',' || it.isWhitespace() || it.isISOControl() }) {
                "betas holds an empty or malformed name"
            }
        }
    }
}

/**
 * An endpoint that speaks the Messages API in [flavor] at `{baseUrl}/v1/messages`, which describes each model by the
 * last listing it read. Settings that name [PROVIDER_HEADERS] in [EndpointSettings.providerHeaders] keep the operator
 * from setting them.
 */
public class MessagesEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    private val flavor: MessagesFlavor,
    private val messages: MessagesSettings = MessagesSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(
        id,
        settings,
        flavor.listing,
        flavor.dialect,
        mapOf("anthropic-version" to flavor.version),
        AUTH_HEADER,
    )

    override suspend fun webSearch(model: String, query: WebQuery): List<WebSearchResult> {
        if (!messages.nativeSearch) return super.webSearch(model, query)
        return nativeSearch(core, model, query, messages.betas, ::classify)
    }

    override suspend fun models(): List<ModelInfo> = core.models().let { models ->
        if (messages.nativeSearch) models.map { it.rebuild { webSearch(true) } } else models
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = MessagesRequest(request, info, flavor, messages, settings.turnContext) { trust ->
            mode(info, request.options, trust)
        }
        val response = MessagesResponse(request, flavor, built.warnings, built.kept)
        val headers = built.betas.takeIf { it.isNotEmpty() }?.let { mapOf("anthropic-beta" to it.joinToString(",")) }
        core.post("/v1/messages", built.body.toString(), headers.orEmpty(), { response.started }, ::classify) {
            read(it, response)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        mode(describe(model), options, trust)

    override fun close() {
        core.close()
    }

    internal fun describe(model: String): ModelInfo = core.describe(model)

    private fun mode(info: ModelInfo, options: ModelOptions, trust: Trust): TurnContextMode {
        val effort = options.reasoning?.takeIf { it in info.reasoningEfforts }
        val thinks = flavor.reasoning.thinks(effort, info)
        return flavor.turnContext.mode(trust, settings.turnContext, messages.turnScopedSystem, thinks)
    }

    private fun classify(detail: ErrorDetail): ModelErrorKind? = flavor.errors.kind(detail.type, detail.message)

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: MessagesResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> stream.events().transformWhile { event ->
                emit(event)
                !response.done
            }.collect { event ->
                if (event.data.isNotBlank()) {
                    val data = json(event.data, response.started)
                    response.event(data, event.type, ::classify).forEach { emit(it) }
                }
            }

            "application/json", null -> {
                val message = json(stream.text(HttpTransport.DEFAULT_LIMIT), false)
                response.message(message, ::classify).forEach { emit(it) }
            }

            else -> throw core.unreadable(stream.mediaType, response.started)
        }
    }

    private fun json(text: String, outputStarted: Boolean): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        return parsed ?: throw modelException(
            ModelErrorKind.PROTOCOL,
            "Endpoint '$id' sent an event that is no JSON object.",
            outputStarted,
        )
    }

    public companion object {
        /** The header that carries the API key unless [EndpointSettings.authHeader] names another. */
        public const val AUTH_HEADER: String = "x-api-key"

        /** The headers that a Messages endpoint sets on every request itself. */
        public val PROVIDER_HEADERS: Set<String> = setOf("anthropic-version", "anthropic-beta")
    }
}
