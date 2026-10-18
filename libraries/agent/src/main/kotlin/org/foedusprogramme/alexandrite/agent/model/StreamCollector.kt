package org.foedusprogramme.alexandrite.agent.model

import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelError
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.model.rebuild
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
            if (event is ModelEvent.ResponseStarted) warned(endpoint, request, event.warnings)
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

/**
 * Logs what [endpoint] warns about [request], such as an option its model does not take, at WARN the first time and
 * at DEBUG after that, as the same request repeats each round.
 */
private fun warned(endpoint: ModelEndpoint, request: ModelRequest, warnings: List<Warning>) {
    for (warning in warnings) {
        val model = request.model
        val first = WARNED.size < MAX_WARNED && WARNED.add("$model ${warning.kind}: ${warning.message}")
        if (first) {
            logger.warn(
                "Endpoint {} warns about a request to {}: {} ({})",
                endpoint.id,
                model,
                warning.message,
                warning.kind,
            )
        } else {
            logger.debug(
                "Endpoint {} warns about a request to {}: {} ({})",
                endpoint.id,
                model,
                warning.message,
                warning.kind,
            )
        }
    }
}

/** The warnings logged at WARN so far, by model, kind and message. */
private val WARNED = ConcurrentHashMap.newKeySet<String>()

private const val MAX_WARNED = 1024

private val logger = LoggerFactory.getLogger("org.foedusprogramme.alexandrite.agent.model.StreamCollector")
