package org.foedusprogramme.alexandrite.provider.common

import org.foedusprogramme.alexandrite.internal.http.HttpException
import org.foedusprogramme.alexandrite.internal.http.HttpResponseStream
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId

/** What the endpoints of every wire API share: the connection, the models and the checks of a request. */
internal class EndpointCore(
    val id: EndpointId,
    settings: EndpointSettings,
    listing: ModelListing,
    dialect: Dialect,
    own: Map<String, String> = emptyMap(),
) : AutoCloseable {
    private val connection = EndpointConnection(settings, own)
    private val catalog = ModelCatalog(id, settings, listing, dialect, connection)

    suspend fun models(): List<ModelInfo> = catalog.models()

    fun describe(model: String): ModelInfo = catalog.describe(model)

    /** The model of [request], which this endpoint can answer. */
    fun admit(request: ModelRequest): ModelInfo {
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

    /**
     * Posts [body] with the headers [extra] to [path] below the base URL and hands the response to [read], where a
     * failed exchange becomes a [ModelException] whose kind [classify] may refine and whose output [started] tells.
     */
    suspend fun post(
        path: String,
        body: String,
        extra: Map<String, String>,
        started: () -> Boolean,
        classify: (ErrorDetail) -> ModelErrorKind? = { null },
        read: suspend (HttpResponseStream) -> Unit,
    ) {
        val call = connection.call("POST", "${connection.base}$path", "text/event-stream", body, extra)
        try {
            connection.transport.open(call, read)
        } catch (e: HttpException) {
            throw e.toModelException(started(), classify)
        } catch (e: ModelException) {
            throw e.masked(call::mask)
        }
    }

    /** The failure of a response in [mediaType], which no endpoint reads. */
    fun unreadable(mediaType: String?, outputStarted: Boolean): ModelException =
        modelException(ModelErrorKind.PROTOCOL, "Endpoint '$id' answered with $mediaType.", outputStarted)

    override fun close() {
        connection.close()
    }
}
