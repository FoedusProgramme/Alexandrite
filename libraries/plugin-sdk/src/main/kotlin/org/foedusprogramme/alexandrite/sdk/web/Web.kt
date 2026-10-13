package org.foedusprogramme.alexandrite.sdk.web

import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef

/** A search service contributed by a plugin. */
@ContributedSpi
public interface WebSearchBackend {
    public val id: String

    public suspend fun search(query: String, limit: Int): List<WebSearchResult>
}

/** A result returned by a search service. */
public class WebSearchResult(public val title: String, public val url: String, public val snippet: String)

/** The model service used by web tools. */
@BoundSpi
public interface WebPageAnswers {
    public suspend fun answer(turn: TurnInfo, url: String, text: String, prompt: String, model: ModelRef?): String

    /** Null when the selected endpoint has no native search capability. */
    public suspend fun search(turn: TurnInfo, query: String, limit: Int): List<WebSearchResult>?
}

/** An optional capability implemented by endpoints with server-side web search. */
public interface NativeWebSearchEndpoint {
    public suspend fun search(model: ModelRef, query: String, limit: Int): List<WebSearchResult>
}
