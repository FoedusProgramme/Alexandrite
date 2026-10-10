package org.foedusprogramme.alexandrite.agent.model

import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest

/** The whole response of [endpoint] to [request]. */
internal suspend fun collectResponse(endpoint: ModelEndpoint, request: ModelRequest): ModelEvent.Completed {
    var completed: ModelEvent.Completed? = null
    endpoint.stream(request).collect { event -> if (event is ModelEvent.Completed) completed = event }
    return completed ?: throw ModelException(
        ModelError.builder(
            ModelErrorKind.PROTOCOL,
            "The response of endpoint '${endpoint.id}' ended without its terminal event.",
        ).build(),
    )
}
