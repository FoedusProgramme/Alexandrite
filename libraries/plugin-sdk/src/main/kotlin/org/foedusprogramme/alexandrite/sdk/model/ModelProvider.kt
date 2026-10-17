package org.foedusprogramme.alexandrite.sdk.model

import kotlinx.coroutines.flow.Flow
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.web.WebQuery
import org.foedusprogramme.alexandrite.sdk.web.WebSearchResult

/** Contributes model endpoints. */
@ContributedSpi
public interface ModelProvider {
    public val endpoints: List<ModelEndpoint>
}

/** A backend that serves models, which keeps credentials out of its logs and errors, URLs included. */
public interface ModelEndpoint {
    public val id: EndpointId

    /** The endpoint's models as the backend reports them now. */
    public suspend fun models(): List<ModelInfo>

    /**
     * The response to [request] as a cold flow, whose collection makes the call and whose cancellation ends it at once.
     *
     * - [ModelEvent.ResponseStarted] comes first when sent, and [ModelEvent.Completed] comes last.
     * - Deltas are previews: the parts of the [ModelEvent.PartCompleted] events, in index order, make up the message
     *   of [ModelEvent.Completed].
     * - Whether the response calls tools is read from its parts alone.
     * - A failed call throws a [ModelException], and a call is retried only before its first event or after
     *   [ModelErrorKind.MALFORMED_OUTPUT].
     * - A tool call's name is the offered tool's name that the provider maps its wire name back to, by default
     *   [ToolNames.fromWire] of the wire name, or the wire name where that is null.
     * - A tool call that the backend gives no id gets [RequestIds.callId] of its part index.
     * - A user entry that follows tool results goes after them: into the user message that carries the results
     *   (Anthropic), as a `user` message after the `tool` messages (OpenAI chat), or as a user message item after the
     *   `function_call_output` items (OpenAI Responses).
     */
    public fun stream(request: ModelRequest): Flow<ModelEvent>

    /** How the endpoint renders turn context of [trust] for [model] with [options]. */
    public fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode

    /**
     * The results of [query] from the backend's own server-side search, used by [model]; throws a [ModelException]
     * when the search fails.
     *
     * Called only for a model whose [ModelInfo.webSearch] is set: an endpoint that sets it overrides this member,
     * whose default throws [UnsupportedOperationException].
     */
    public suspend fun webSearch(model: String, query: WebQuery): List<WebSearchResult> =
        throw UnsupportedOperationException("Endpoint '$id' has no web search.")
}
