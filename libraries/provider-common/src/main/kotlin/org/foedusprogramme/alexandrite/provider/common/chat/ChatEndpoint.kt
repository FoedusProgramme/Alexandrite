package org.foedusprogramme.alexandrite.provider.common.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import org.foedusprogramme.alexandrite.internal.http.HttpException
import org.foedusprogramme.alexandrite.internal.http.HttpResponseStream
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.provider.common.EndpointConnection
import org.foedusprogramme.alexandrite.provider.common.EndpointSettings
import org.foedusprogramme.alexandrite.provider.common.ModelCatalog
import org.foedusprogramme.alexandrite.provider.common.TurnContextSetting
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

/** An endpoint that speaks Chat Completions in [flavor], which describes each model by the last listing it read. */
public class ChatEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    private val flavor: ChatFlavor,
) : ModelEndpoint,
    AutoCloseable {
    private val connection = EndpointConnection(
        settings,
        settings.apiKey?.let { mapOf("authorization" to "Bearer ${it.reveal()}") }.orEmpty(),
    )
    private val catalog = ModelCatalog(id, settings, flavor.listing, flavor.dialect, connection)

    override suspend fun models(): List<ModelInfo> = catalog.models()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = admit(request)
        val chat = ChatRequest(request, info, flavor, settings.promptCacheKey)
        val response = ChatResponse(request, flavor, chat.warnings)
        val body = chat.body.toString()
        val call = connection.call("POST", "${connection.base}/chat/completions", "text/event-stream", body)
        try {
            connection.transport.open(call) { stream -> read(stream, response) }
        } catch (e: HttpException) {
            throw e.toModelException(response.started)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        if (settings.turnContext == TurnContextSetting.TRANSIENT) {
            TurnContextMode.TRANSIENT
        } else {
            TurnContextMode.NOT_SUPPORTED
        }

    override fun close() {
        connection.close()
    }

    internal fun describe(model: String): ModelInfo = catalog.describe(model)

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

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: ChatResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> stream.events().takeWhile { it.data != DONE }.collect { event ->
                if (event.data.isNotBlank()) response.chunk(chunk(event.data, response.started)).forEach { emit(it) }
            }

            "application/json", null ->
                response.chunk(chunk(stream.text(HttpTransport.DEFAULT_LIMIT), false)).forEach { emit(it) }

            else -> throw modelException(
                ModelErrorKind.PROTOCOL,
                "Endpoint '$id' answered with ${stream.mediaType}.",
                response.started,
            )
        }
    }

    private fun chunk(text: String, outputStarted: Boolean): ChunkWire = try {
        JSON.decodeFromString(ChunkWire.serializer(), text)
    } catch (e: IllegalArgumentException) {
        throw modelException(
            ModelErrorKind.PROTOCOL,
            "Endpoint '$id' sent a chunk that is no JSON object.",
            outputStarted,
        )
    }

    private companion object {
        const val DONE = "[DONE]"
        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
