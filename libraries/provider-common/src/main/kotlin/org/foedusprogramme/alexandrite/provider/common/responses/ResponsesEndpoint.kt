package org.foedusprogramme.alexandrite.provider.common.responses

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
import org.foedusprogramme.alexandrite.provider.common.TurnContextSetting
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

/** What an endpoint of the Responses API does beyond its [EndpointSettings]. */
public class ResponsesSettings(
    /** Whether requests carry the agent's cache key. */
    public val promptCacheKey: Boolean = true,
    /** The `reasoning.summary` that requests to reasoning models ask for, null for none. */
    public val reasoningSummary: String? = "auto",
    /** Whether the endpoint's models search the web with the backend's `web_search` tool. */
    public val nativeSearch: Boolean = false,
) {
    init {
        require(reasoningSummary == null || reasoningSummary in SUMMARIES) {
            "reasoningSummary must be one of ${SUMMARIES.joinToString { "\"$it\"" }} or null"
        }
    }

    private companion object {
        val SUMMARIES = listOf("auto", "concise", "detailed")
    }
}

/**
 * An endpoint that speaks the Responses API in [flavor] at `{baseUrl}/responses`, statelessly, which describes each
 * model by the last listing it read.
 */
public class ResponsesEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    private val flavor: ResponsesFlavor,
    private val responses: ResponsesSettings = ResponsesSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(id, settings, flavor.listing, flavor.dialect)

    override suspend fun webSearch(model: String, query: WebQuery): List<WebSearchResult> {
        if (!responses.nativeSearch) return super.webSearch(model, query)
        return nativeSearch(core, model, query, ::classify)
    }

    override suspend fun models(): List<ModelInfo> = core.models().let { models ->
        if (responses.nativeSearch) models.map { it.rebuild { webSearch(true) } } else models
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = ResponsesRequest(request, info, flavor, responses)
        val response = ResponsesResponse(request, flavor, built.warnings)
        core.post("/responses", built.body.toString(), emptyMap(), { response.started }, ::classify) { stream ->
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

    private fun classify(detail: ErrorDetail): ModelErrorKind? =
        flavor.errors.kind(detail.type, detail.code, detail.message)

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: ResponsesResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> stream.events().transformWhile { event ->
                emit(event)
                !response.done
            }.collect { event ->
                if (event.data.isNotBlank() && event.data != DONE) {
                    response.event(json(event.data, response.started), event.type, ::classify).forEach { emit(it) }
                }
            }

            "application/json", null -> {
                val whole = json(stream.text(HttpTransport.DEFAULT_LIMIT), false)
                response.response(whole, ::classify).forEach { emit(it) }
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

    private companion object {
        const val DONE = "[DONE]"
    }
}
