package org.foedusprogramme.alexandrite.provider.common.messages

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpException
import org.foedusprogramme.alexandrite.internal.http.HttpResponseStream
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.provider.common.EndpointConnection
import org.foedusprogramme.alexandrite.provider.common.EndpointSettings
import org.foedusprogramme.alexandrite.provider.common.ErrorDetail
import org.foedusprogramme.alexandrite.provider.common.ModelCatalog
import org.foedusprogramme.alexandrite.provider.common.modelException
import org.foedusprogramme.alexandrite.provider.common.toModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId

/** What an endpoint of the Messages API does beyond its [EndpointSettings]. */
public class MessagesSettings(
    /** Whether requests mark cache breakpoints. */
    public val promptCaching: Boolean = true,
    /** Beta features that every request asks for in `anthropic-beta`. */
    public val betas: List<String> = emptyList(),
    /** Whether the endpoint's models take turn-scoped system messages, which then carry trusted turn context. */
    public val turnScopedSystem: Boolean = false,
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
 * last listing it read. It sends the API key in [EndpointSettings.authHeader], [AUTH_HEADER] for the standard API.
 */
public class MessagesEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    private val flavor: MessagesFlavor,
    private val messages: MessagesSettings = MessagesSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val connection = EndpointConnection(settings, auth() + ("anthropic-version" to flavor.version))
    private val catalog = ModelCatalog(id, settings, flavor.listing, flavor.dialect, connection)

    override suspend fun models(): List<ModelInfo> = catalog.models()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = admit(request)
        val built = MessagesRequest(request, info, flavor, messages, settings.turnContext) { trust ->
            mode(info, request.options, trust)
        }
        val response = MessagesResponse(request, flavor, built.warnings, built.kept)
        val headers = built.betas.takeIf { it.isNotEmpty() }?.let { mapOf("anthropic-beta" to it.joinToString(",")) }
        val url = "${connection.base}/v1/messages"
        val call = connection.call("POST", url, "text/event-stream", built.body.toString(), headers.orEmpty())
        try {
            connection.transport.open(call) { stream -> read(stream, response) }
        } catch (e: HttpException) {
            throw e.toModelException(response.started, ::classify)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        mode(describe(model), options, trust)

    override fun close() {
        connection.close()
    }

    internal fun describe(model: String): ModelInfo = catalog.describe(model)

    private fun mode(info: ModelInfo, options: ModelOptions, trust: Trust): TurnContextMode {
        val effort = options.reasoning?.takeIf { it in info.reasoningEfforts }
        val thinks = flavor.reasoning.thinks(effort, info)
        return flavor.turnContext.mode(trust, settings.turnContext, messages.turnScopedSystem, thinks)
    }

    private fun auth(): Map<String, String> {
        val key = settings.apiKey?.reveal() ?: return emptyMap()
        val header = settings.authHeader.lowercase()
        return mapOf(header to if (header == "authorization") "Bearer $key" else key)
    }

    private fun classify(detail: ErrorDetail): ModelErrorKind? = flavor.errors.kind(detail.type, detail.message)

    /** The model of [request], which this endpoint can answer. */
    private fun admit(request: ModelRequest): ModelInfo {
        if (request.model.endpoint != id) {
            throw modelException(
                ModelErrorKind.INVALID_REQUEST,
                "Endpoint '$id' got a request for ${request.model}.",
                false,
            )
        }
        val info = describe(request.model.model)
        if (request.tools.isNotEmpty() && !info.nativeTools) {
            throw modelException(ModelErrorKind.UNSUPPORTED, "Model ${request.model} takes no tools.", false)
        }
        return info
    }

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

            else -> throw modelException(
                ModelErrorKind.PROTOCOL,
                "Endpoint '$id' answered with ${stream.mediaType}.",
                response.started,
            )
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
        /** The header that carries the API key of the standard API. */
        public const val AUTH_HEADER: String = "x-api-key"

        /** The headers that a Messages endpoint sets on every request itself. */
        public val PROVIDER_HEADERS: Set<String> = setOf("anthropic-version", "anthropic-beta")
    }
}
