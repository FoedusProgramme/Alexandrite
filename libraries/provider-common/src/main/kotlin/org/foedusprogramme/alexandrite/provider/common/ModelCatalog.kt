package org.foedusprogramme.alexandrite.provider.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.internal.http.HttpCall
import org.foedusprogramme.alexandrite.internal.http.HttpException
import org.foedusprogramme.alexandrite.internal.http.HttpFailureKind
import org.foedusprogramme.alexandrite.internal.http.HttpTransport
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.slf4j.LoggerFactory
import java.net.URI

/** The HTTP side of one endpoint, which sends the headers [own] with every request. */
internal class EndpointConnection(settings: EndpointSettings, private val own: Map<String, String>) : AutoCloseable {
    val base: String = settings.baseUrl.trimEnd('/')
    val transport: HttpTransport = HttpTransport(settings.timeouts.timeouts())
    private val headers = settings.headers.mapValues { it.value.reveal() }
    private val secrets = listOfNotNull(settings.apiKey?.reveal()) + headers.values

    fun call(
        method: String,
        url: String,
        accept: String,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpCall {
        val all = buildMap {
            put("accept", accept)
            put("content-type", "application/json")
            putAll(own)
            putAll(extra)
            putAll(headers)
        }
        return HttpCall(method, URI(url), all, body, secrets)
    }

    override fun close() {
        transport.close()
    }
}

/** The models of the endpoint [id], each described by the config, the last listing [models] read and [listing]. */
internal class ModelCatalog(
    private val id: EndpointId,
    private val settings: EndpointSettings,
    private val listing: ModelListing,
    private val dialect: Dialect,
    private val connection: EndpointConnection,
) {
    @Volatile
    private var listed: Map<String, ModelFacts> = emptyMap()

    suspend fun models(): List<ModelInfo> {
        val found = if (settings.discover) discover() else null
        found?.let { facts -> listed = facts.associateBy { it.id!! } }
        val ids = found.orEmpty().map { it.id!! } + settings.models.keys
        return ids.distinct().map(::describe)
    }

    fun describe(model: String): ModelInfo =
        modelInfo(model, dialect, settings.models[model]?.facts(model), listed[model], listing.defaults)

    /** The models the backend lists, or null when it cannot be asked and the config names models. */
    private suspend fun discover(): List<ModelFacts>? {
        var failure: HttpException? = null
        for (url in listing.urls(connection.base)) {
            try {
                return pages(url)
            } catch (e: HttpException) {
                failure = failure ?: e
                if (e.kind != HttpFailureKind.NOT_FOUND) break
            }
        }
        val error = failure!!.toModelException(false)
        if (settings.models.isEmpty()) throw error
        log.warn("Endpoint '{}' cannot list its models, so it describes the configured ones: {}", id, error.message)
        return null
    }

    private suspend fun pages(first: String): List<ModelFacts> {
        val models = mutableListOf<ModelFacts>()
        var next: String? = first
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val page = page(connection.transport.send(connection.call("GET", next, "application/json")))
            models += listing.models(page).map(listing::facts).filter { facts ->
                facts.id?.let { id -> id.isNotEmpty() && id.none(Char::isISOControl) } == true
            }
            next = listing.next(page)?.let { URI(first).resolve(it).toString() }
        }
        return models.distinctBy { it.id }
    }

    private fun page(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw HttpException(HttpFailureKind.PROTOCOL, "Endpoint '$id' listed its models in no JSON object.")

    private companion object {
        const val MAX_PAGES = 20
        val log = LoggerFactory.getLogger(ModelCatalog::class.java)
    }
}
