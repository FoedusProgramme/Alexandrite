package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.requireOpaqueId

/** Text for the requests of one turn, which is never persisted. */
@Poko
public class TurnContextItem(
    /** What contributed the text. */
    public val source: String,
    public val text: String,
    public val trust: Trust = Trust.UNTRUSTED,
    public val fallback: TurnContextFallback = TurnContextFallback.DROP,
) {
    init {
        requireOpaqueId(source, "turn context source")
    }
}

/** How far the agent trusts a text. */
public enum class Trust {
    /** Text that may speak with the system's authority. */
    TRUSTED,

    /** Text that may hold anyone's words. */
    UNTRUSTED,
}

/** What the agent does with a turn-context item that the endpoint cannot render for one request alone. */
public enum class TurnContextFallback {
    /** Persisted in the turn's user entry. */
    BAKE,

    /** Left out. */
    DROP,
}

/** How an endpoint renders turn context; a `when` over it needs an `else` branch. */
public enum class TurnContextMode {
    /** Into one request, kept nowhere. */
    TRANSIENT,

    /** Not at all, which leaves each item to its [TurnContextItem.fallback]. */
    NOT_SUPPORTED,

    /**
     * Into the requests of one turn, shown to the model during that turn alone and kept in the provider data of the
     * turn's responses, so an item whose fallback is [TurnContextFallback.DROP] is left out.
     */
    KEPT_UNRENDERED,
}
