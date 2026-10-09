package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpCall
import org.foedusprogramme.alexandrite.internal.http.HttpException
import org.foedusprogramme.alexandrite.internal.http.HttpFailureKind
import org.foedusprogramme.alexandrite.internal.http.HttpResponseStream
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextMode
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.slf4j.LoggerFactory
import java.net.URI

/** One endpoint the operator configured, which describes each model by the last listing [models] read. */
internal class ChatEndpoint(
    override val id: EndpointId,
    private val config: EndpointConfig,
    private val transport: HttpTransport,
) : ModelEndpoint,
    AutoCloseable {
    private val profile = config.profileOf()
    private val base = config.baseUrl.trimEnd('/')
    private val secrets = listOfNotNull(config.apiKey?.reveal()) + config.headers.values.map { it.reveal() }

    @Volatile
    private var listed: Map<String, ModelFacts> = emptyMap()

    override suspend fun models(): List<ModelInfo> {
        val found = if (config.discover) discover() else null
        found?.let { facts -> listed = facts.associateBy { it.id!! } }
        val ids = found.orEmpty().map { it.id!! } + config.models.keys
        return ids.distinct().map(::describe)
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = admit(request)
        val chat = ChatRequest(request, info, profile, config.promptCacheKey)
        val body = chat.body.toString()
        val response = ChatResponse(request, profile, chat.warnings)
        val call = HttpCall("POST", URI("$base/chat/completions"), headers("text/event-stream"), body, secrets)
        try {
            transport.open(call) { stream -> read(stream, response) }
        } catch (e: HttpException) {
            throw e.toModelException(response.started)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        if (config.turnContextTransient) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED

    override fun close() {
        transport.close()
    }

    /** The model [model] as the config, the last listing and the profile describe it. */
    fun describe(model: String): ModelInfo =
        modelInfo(model, profile.dialect, config.models[model]?.facts(model), listed[model], profile.defaults)

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

    /** The models the backend lists, or null when it cannot be asked and the config names models. */
    private suspend fun discover(): List<ModelFacts>? {
        var failure: HttpException? = null
        for (listing in profile.listings(base)) {
            try {
                return pages(listing)
            } catch (e: HttpException) {
                failure = failure ?: e
                if (e.kind != HttpFailureKind.NOT_FOUND) break
            }
        }
        val error = failure!!.toModelException(false)
        if (config.models.isEmpty()) throw error
        log.warn("Endpoint '{}' cannot list its models, so it describes the configured ones: {}", id, error.message)
        return null
    }

    private suspend fun pages(first: String): List<ModelFacts> {
        val models = mutableListOf<ModelFacts>()
        var next: String? = first
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val call = HttpCall("GET", URI(next), headers("application/json"), secrets = secrets)
            val listing = listing(transport.send(call))
            models += profile.models(listing).map(profile::facts).filter { facts ->
                facts.id?.let { id -> id.isNotEmpty() && id.none(Char::isISOControl) } == true
            }
            next = profile.next(listing)?.let { URI(first).resolve(it).toString() }
        }
        return models.distinctBy { it.id }
    }

    private fun headers(accept: String): Map<String, String> = buildMap {
        put("accept", accept)
        put("content-type", "application/json")
        config.apiKey?.let { put("authorization", "Bearer ${it.reveal()}") }
        config.headers.forEach { (name, value) -> put(name, value.reveal()) }
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

    private fun listing(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw HttpException(HttpFailureKind.PROTOCOL, "Endpoint '$id' listed its models in no JSON object.")

    private companion object {
        const val DONE = "[DONE]"
        const val MAX_PAGES = 20
        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
        val log = LoggerFactory.getLogger(ChatEndpoint::class.java)
    }
}
