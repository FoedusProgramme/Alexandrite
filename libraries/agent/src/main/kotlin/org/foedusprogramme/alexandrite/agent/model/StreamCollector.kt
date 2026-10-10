package org.foedusprogramme.alexandrite.agent.model

import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.rebuild

/**
 * The whole response of [endpoint] to [request], whose other events go to [observe] as they come.
 *
 * A failure after the first event is thrown as one whose output started.
 */
internal suspend fun collectResponse(
    endpoint: ModelEndpoint,
    request: ModelRequest,
    observe: suspend (ModelEvent) -> Unit = {},
): ModelEvent.Completed {
    var completed: ModelEvent.Completed? = null
    var started = false
    try {
        endpoint.stream(request).collect { event ->
            started = true
            if (event is ModelEvent.Completed) completed = event else observe(event)
        }
    } catch (e: ModelException) {
        if (!started || e.error.outputStarted) throw e
        throw ModelException(e.error.rebuild { outputStarted(true) }, e)
    }
    return completed ?: throw ModelException(
        ModelError.builder(
            ModelErrorKind.PROTOCOL,
            "The response of endpoint '${endpoint.id}' ended without its terminal event.",
        ).outputStarted(started).build(),
    )
}
