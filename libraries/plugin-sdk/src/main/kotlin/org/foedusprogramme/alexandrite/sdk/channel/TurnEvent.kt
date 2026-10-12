package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import java.nio.file.Path

/** One step of a turn's activity, shown only through the kinds a channel's capabilities declare. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnEvent {
    /** The model's call [id] of [tool], of kind [activity], titled [title]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ToolCallStarted(
        public val id: ToolCallId,
        public val tool: String,
        public val activity: ToolActivity,
        public val title: String,
    ) : TurnEvent

    /** Call [id] now has [status], shown with [content]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ToolCallUpdated(
        public val id: ToolCallId,
        public val status: ToolCallStatus,
        public val content: Content? = null,
    ) : TurnEvent

    /** A fragment of the model's reasoning. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ThoughtChunk(public val text: String) : TurnEvent

    /** The turn's usage so far. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class UsageUpdated(
        public val promptTokens: Long?,
        public val outputTokens: Long?,
        public val window: Int?,
    ) : TurnEvent

    /** What a [ToolCallUpdated] shows of its call. */
    @SubclassOptInRequired(InternalAlexandriteApi::class)
    public interface Content {
        @OptIn(InternalAlexandriteApi::class)
        @Poko
        public class Text(public val text: String) : Content

        /** A change of the file [path]. */
        @OptIn(InternalAlexandriteApi::class)
        @Poko
        public class Diff(public val path: Path, public val unified: String) : Content

        /** The last lines of a call's output. */
        @OptIn(InternalAlexandriteApi::class)
        @Poko
        public class Output(public val tail: String) : Content
    }
}

/** How a tool call shown through [TurnEvent] is doing. */
@JvmInline
@Serializable
public value class ToolCallStatus internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val PENDING: ToolCallStatus = ToolCallStatus("pending")

        public val WAITING_APPROVAL: ToolCallStatus = ToolCallStatus("waiting_approval")

        public val RUNNING: ToolCallStatus = ToolCallStatus("running")

        /** Moved to the background as a job. */
        public val BACKGROUNDED: ToolCallStatus = ToolCallStatus("backgrounded")

        public val COMPLETED: ToolCallStatus = ToolCallStatus("completed")

        public val FAILED: ToolCallStatus = ToolCallStatus("failed")

        public val NOT_RUN: ToolCallStatus = ToolCallStatus("not_run")

        /** The values this version knows. */
        public val entries: List<ToolCallStatus> = listOf(
            PENDING,
            WAITING_APPROVAL,
            RUNNING,
            BACKGROUNDED,
            COMPLETED,
            FAILED,
            NOT_RUN,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): ToolCallStatus = ToolCallStatus(id)
    }
}

/** The kind of a [TurnEvent], for [ChannelCapabilities.events]. */
@JvmInline
@Serializable
public value class TurnEventKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val TOOL_CALL_STARTED: TurnEventKind = TurnEventKind("tool_call_started")

        public val TOOL_CALL_UPDATED: TurnEventKind = TurnEventKind("tool_call_updated")

        public val THOUGHT_CHUNK: TurnEventKind = TurnEventKind("thought_chunk")

        public val USAGE_UPDATED: TurnEventKind = TurnEventKind("usage_updated")

        /** The values this version knows. */
        public val entries: List<TurnEventKind> =
            listOf(TOOL_CALL_STARTED, TOOL_CALL_UPDATED, THOUGHT_CHUNK, USAGE_UPDATED)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): TurnEventKind = TurnEventKind(id)
    }
}
