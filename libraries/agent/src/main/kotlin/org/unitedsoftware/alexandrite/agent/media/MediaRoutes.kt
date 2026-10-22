package org.unitedsoftware.alexandrite.agent.media

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef

/** What a turn does with media of one kind. */
internal sealed interface MediaRoute {
    /** The turn's model takes the media as it is. */
    data object Native : MediaRoute

    /** [model], the agent's reader of the kind, reads the media into text that the turn's model takes. */
    class Read(val model: ModelRef) : MediaRoute

    /** The media is only saved as a file, for [reason], in lower case without a closing full stop. */
    class Saved(val reason: String) : MediaRoute
}

/** How one turn's model takes media, by kind. */
internal class MediaPlan(private val routes: Map<MediaKind, MediaRoute>) {
    /** The route of media of [kind], [MediaRoute.Saved] for a kind the plan does not know. */
    fun route(kind: MediaKind): MediaRoute = routes[kind] ?: MediaRoute.Saved("no model takes $kind media")

    /** The route of each kind the plan knows. */
    val entries: Map<MediaKind, MediaRoute> get() = routes
}

/** Plans how each agent's turns take media, from the agent's media section and reader roles. */
internal interface MediaRouting {
    /**
     * How a turn of [agent] whose model is listed as [model] takes each kind of media; the same inputs, the same
     * plan.
     */
    fun plan(agent: AgentId, model: ModelInfo): MediaPlan
}

/** Reads media into text for a turn whose model does not take it. */
internal interface MediaReader {
    /**
     * What [model] reads from [media], whose bytes are inline, for a turn of [turn]: its answer to [question], or when
     * that is null a full description or transcript; kept by the bytes, the model and the question, so that the same
     * media is read once. Throws a [MediaReadException] when the model cannot read it.
     */
    suspend fun read(turn: TurnInfo, media: MediaPart, model: ModelRef, question: String? = null): MediaReading

    /** What [model] read from [media] before as [read] keeps it, null when it never read it. */
    suspend fun cached(media: MediaPart, model: ModelRef, question: String? = null): MediaReading?
}

/** The [text] that [model] read from a piece of media. */
internal class MediaReading(val text: String, val model: ModelRef)

/** [model] did not read a piece of media, for [reason], in lower case without a closing full stop. */
internal class MediaReadException(val model: ModelRef, val reason: String, cause: Throwable? = null) :
    Exception("$model did not read the media: $reason", cause)
