package org.foedusprogramme.alexandrite.sdk.model

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantPart
import org.foedusprogramme.alexandrite.sdk.transcript.ProviderData
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal

/** One event of a model's response, where an index is the position of a part in the response's message. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ModelEvent {
    /** The backend accepted the request. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ResponseStarted(
        /** The backend's id of the response. */
        public val responseId: String?,
        /** The model the backend says answers. */
        public val model: String?,
        public val warnings: List<Warning>,
    ) : ModelEvent

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class TextDelta(public val index: Int, public val text: String) : ModelEvent {
        init {
            requireIndex(index)
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ReasoningDelta(public val index: Int, public val text: String?, public val summary: String?) :
        ModelEvent {
        init {
            requireIndex(index)
            require(text != null || summary != null) { "A reasoning delta holds text, a summary or both." }
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ReasoningSealed(public val index: Int, public val seal: ReasoningSeal) : ModelEvent {
        init {
            requireIndex(index)
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ToolCallStarted(public val index: Int, public val id: ToolCallId, public val name: String) :
        ModelEvent {
        init {
            requireIndex(index)
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ToolArgumentsDelta(public val index: Int, public val fragment: String) : ModelEvent {
        init {
            requireIndex(index)
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class PartCompleted(public val index: Int, public val part: AssistantPart) : ModelEvent {
        init {
            requireIndex(index)
        }
    }

    /** The usage of the call so far. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class UsageUpdated(public val usage: Usage) : ModelEvent

    /** The whole response. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Completed(
        public val message: AssistantEntry,
        public val finish: FinishReason,
        public val usage: Usage,
        /** Data of the call that the transcript does not keep. */
        public val providerData: ProviderData = ProviderData.EMPTY,
    ) : ModelEvent {
        init {
            require(message.record == null) { "A response's message is not stored yet, so it has no record." }
        }
    }
}

private fun requireIndex(index: Int) {
    require(index >= 0) { "A part index is at least 0, was $index." }
}

/** Why a response ended. */
@Poko
public class FinishReason(
    public val kind: FinishKind,
    /** The backend's own value. */
    public val raw: String?,
    /** What else the backend said about the reason. */
    public val detail: String?,
)

/** Why a response ended, as the agent acts on it. */
@JvmInline
@Serializable
public value class FinishKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The model finished its answer. */
        public val END_TURN: FinishKind = FinishKind("end_turn")

        /** The model stopped to call tools. */
        public val TOOL_USE: FinishKind = FinishKind("tool_use")

        /** The model wrote a stop sequence. */
        public val STOP_SEQUENCE: FinishKind = FinishKind("stop_sequence")

        /** The output reached its token limit. */
        public val MAX_OUTPUT_TOKENS: FinishKind = FinishKind("max_output_tokens")

        /** The context window filled up. */
        public val CONTEXT_WINDOW_EXCEEDED: FinishKind = FinishKind("context_window_exceeded")

        /** The backend paused the response for the agent to resend the request. */
        public val PAUSED: FinishKind = FinishKind("paused")

        /** The model refused to answer. */
        public val REFUSAL: FinishKind = FinishKind("refusal")

        /** Any other reason. */
        public val OTHER: FinishKind = FinishKind("other")

        /** The values this version knows. */
        public val entries: List<FinishKind> = listOf(
            END_TURN,
            TOOL_USE,
            STOP_SEQUENCE,
            MAX_OUTPUT_TOKENS,
            CONTEXT_WINDOW_EXCEEDED,
            PAUSED,
            REFUSAL,
            OTHER,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): FinishKind = FinishKind(id)
    }
}

/** A problem that did not stop the call. */
@Poko
public class Warning(public val kind: String, public val message: String) {
    init {
        require(kind.isNotBlank()) { "A warning's kind may not be blank." }
        require(message.isNotBlank()) { "A warning's message may not be blank." }
    }
}
