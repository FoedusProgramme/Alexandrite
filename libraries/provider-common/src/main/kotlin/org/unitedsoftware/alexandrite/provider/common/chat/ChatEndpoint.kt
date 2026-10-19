package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.internal.http.HttpResponseStream
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

/**
 * An endpoint that speaks Chat Completions in [flavor] at `{baseUrl}/chat/completions`, which describes each model by
 * the last listing it read.
 */
public class ChatEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    private val flavor: ChatFlavor,
    private val chat: ChatApiSettings = ChatApiSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(id, settings, flavor.listing, flavor.dialect)

    override suspend fun models(): List<ModelInfo> = core.models()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = ChatRequest(request, info, flavor, chat.promptCacheKey)
        val response = ChatResponse(request, flavor, built.warnings)
        core.post("/chat/completions", built.body.toString(), emptyMap(), { response.started }) { stream ->
            read(stream, response)
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
        core.close()
    }

    internal fun describe(model: String): ModelInfo = core.describe(model)

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: ChatResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> stream.events().takeWhile { it.data != DONE }.collect { event ->
                if (event.data.isNotBlank()) response.chunk(chunk(event.data, response.started)).forEach { emit(it) }
            }

            "application/json", null ->
                response.chunk(chunk(stream.text(HttpTransport.DEFAULT_LIMIT), false)).forEach { emit(it) }

            else -> throw core.unreadable(stream.mediaType, response.started)
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
