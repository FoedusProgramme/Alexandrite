@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry

/** A hook payload of one turn. */
public interface TurnScoped {
    public val turn: TurnInfo
}

/** The sections of a turn's system prompt. */
@Poko
public class PromptSections @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val sections: List<PromptSection>,
) : TurnScoped {
    init {
        val repeated = sections.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "The prompt names the sections ${repeated.joinToString()} more than once." }
    }

    public fun withSections(sections: List<PromptSection>): PromptSections = PromptSections(turn, sections.toList())
}

/** A turn about to build its prompt. */
@Poko
public class TurnStart @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** The message the turn answers, null when it answers none. */
    public val message: IncomingMessage?,
    /** The tools the turn may call, which a replacement narrows without taking them out of the model's offer. */
    public val tools: List<ToolDefinition>,
) : TurnScoped {
    public fun withTools(tools: List<ToolDefinition>): TurnStart = TurnStart(turn, message, tools.toList())
}

/** Input on its way to the model, a new turn's or a follow-up that joins the running turn. */
@Poko
public class TurnInput @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** The message that brought the input, null for a turn a plugin started. */
    public val message: IncomingMessage?,
    /** What the model reads. */
    public val text: String,
    public val followUp: Boolean,
) : TurnScoped {
    init {
        require(!followUp || message != null) { "A follow-up is a message." }
    }

    public fun withText(text: String): TurnInput = TurnInput(turn, message, text, followUp)
}

/** The history a turn loaded, its rolling summary as a summary entry. */
@Poko
public class ContextLoaded @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val history: List<TranscriptEntry>,
) : TurnScoped {
    init {
        requireNoInlineMedia(history)
    }
}

/** The turn context of a turn's requests. */
@Poko
public class TurnContext @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** The text of the turn's input. */
    public val text: String,
    public val items: List<TurnContextItem>,
) : TurnScoped {
    public operator fun plus(item: TurnContextItem): TurnContext = TurnContext(turn, text, items + item)
}

/** A turn's request to its model, whose history in round 0 may end with the turn's opening entry, not stored yet. */
@Poko
public class ModelCall @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** 0 for the turn's first request. */
    public val round: Int,
    public val request: ModelRequest,
) : TurnScoped {
    init {
        require(round >= 0) { "A round is at least 0, was $round." }
        val ids = request.ids
        require(ids.conversation == turn.conversation && ids.turn == turn.id && ids.round == round) {
            "Round $round of turn ${turn.id} in conversation ${turn.conversation} sends a request of round " +
                "${ids.round} of turn ${ids.turn} in conversation ${ids.conversation}."
        }
    }

    public fun withRequest(request: ModelRequest): ModelCall = ModelCall(turn, round, request)
}

/** A model's response to a turn's request. */
@Poko
public class ModelReply @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** 0 for the response to the turn's first request. */
    public val round: Int,
    public val response: ModelEvent.Completed,
) : TurnScoped {
    init {
        require(round >= 0) { "A round is at least 0, was $round." }
    }
}

/** A tool call about to run. */
@Poko
public class ToolCallCheck @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val call: ToolCallPart,
    public val definition: ToolDefinition,
    /** The call's effective risk. */
    public val risk: ToolRisk,
    /** Why a human must approve the call, null when the permission mode decides. */
    public val requireApproval: String? = null,
) : TurnScoped {
    init {
        require(call.name == definition.name) { "A call of '${call.name}' is checked as '${definition.name}'." }
        require(requireApproval == null || requireApproval.isNotBlank()) { "A required approval gives a reason." }
    }

    public fun withRequireApproval(reason: String): ToolCallCheck = ToolCallCheck(turn, call, definition, risk, reason)
}

/** A tool call and its result. */
@Poko
public class ToolCallDone @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val call: ToolCallPart,
    public val result: ToolResultEntry,
) : TurnScoped {
    init {
        require(result.callId == call.id) { "The result of call ${result.callId} answers call ${call.id}." }
        requireNoInlineMedia(listOf(result))
    }
}

/** A preview of a turn's reply on its way to the chat. */
@Poko
public class ReplyPreview @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val segment: Int,
    /** The whole text of [segment] so far. */
    public val text: String,
) : TurnScoped {
    init {
        require(segment >= 0) { "A segment is at least 0, was $segment." }
    }

    public fun withText(text: String): ReplyPreview = ReplyPreview(turn, segment, text)
}

/** A turn's final reply on its way to one of its chats, which the transcript keeps as the model wrote it. */
@Poko
public class ReplyDraft @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val message: OutboundMessage,
    /** The chat this copy of the reply goes to. */
    public val destination: ChatAddress,
) : TurnScoped {
    /** This draft with [message], which keeps the kind and the conversation of the draft's message. */
    public fun withMessage(message: OutboundMessage): ReplyDraft {
        require(message.kind == this.message.kind && message.conversation == this.message.conversation) {
            "A replaced reply keeps its kind ${this.message.kind} and conversation ${this.message.conversation}."
        }
        return ReplyDraft(turn, message, destination)
    }
}

/** What a turn stored, once it has ended. */
@Poko
public class TurnCommitted @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** In the order they were stored. */
    public val entries: List<TranscriptEntry>,
    public val outcome: TurnOutcome,
    /** The usage of the turn's requests, null when it made none. */
    public val usage: Usage?,
) : TurnScoped {
    init {
        require(entries.all { it.record != null }) { "The entries a turn committed are stored." }
        requireNoInlineMedia(entries)
    }
}

/** A conversation sealed for its [successor]. */
@Poko
public class ConversationSealed @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val sealed: ConversationId,
    public val successor: ConversationId,
) : TurnScoped {
    init {
        require(sealed != successor) { "Conversation $sealed cannot succeed itself." }
    }
}

/** A compaction about to fold [entries] and the [previous] summary into a new one. */
@Poko
public class ContextCompaction @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** The stored entries that the new summary stands in for, oldest first. */
    public val entries: List<TranscriptEntry>,
    /** The rolling summary that the new one folds in, null when there is none. */
    public val previous: SummaryEntry?,
    /** What the person who asked for the compaction wants the summary to keep in view, null when nobody asked. */
    public val focus: String?,
    /** The summary that a hook wrote, null while the model is to write it. */
    public val summary: String? = null,
) : TurnScoped {
    init {
        require(entries.isNotEmpty()) { "A compaction folds at least one entry." }
        require(entries.all { it.record != null } && (previous == null || previous.record != null)) {
            "A compaction folds stored entries."
        }
        require(summary == null || summary.isNotBlank()) { "A summary is not blank." }
        requireNoInlineMedia(entries)
    }

    public fun withSummary(summary: String): ContextCompaction =
        ContextCompaction(turn, entries, previous, focus, summary)
}

/** A compaction that stored [summary], which shrank the prompt from [tokensBefore] to about [tokensAfter] tokens. */
@Poko
public class ContextCompacted @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val summary: SummaryEntry,
    public val tokensBefore: Long,
    public val tokensAfter: Long,
) : TurnScoped {
    init {
        require(summary.record != null) { "A compaction stored its summary." }
    }
}

/** Tool results whose content requests leave out from now on, which frees about [tokensFreed] tokens. */
@Poko
public class ContextCleared @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    /** The calls whose results were cleared, oldest first. */
    public val calls: List<ToolCallPart>,
    public val tokensFreed: Long,
) : TurnScoped {
    init {
        require(calls.isNotEmpty()) { "A clearing clears at least one result." }
    }
}

private fun requireNoInlineMedia(entries: List<TranscriptEntry>) {
    val index = entries.indexOfFirst { entry ->
        val parts = when (entry) {
            is UserEntry -> entry.parts
            is ToolResultEntry -> entry.content
            else -> emptyList()
        }
        parts.any { it is MediaPart && it.source is InlineMedia }
    }
    require(index < 0) { "Entry $index holds inline media, which an observed payload refers to by its stored id." }
}

/** The sub-agent [run] of [profile] that [turn] delegated, as it starts or ends. */
@Poko
public class AgentRunLifecycle @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val run: RunId,
    public val profile: AgentId,
    public val background: Boolean,
) : TurnScoped

/** The job [run] that runs [call] of [turn] in the background, as it starts, moves there or ends. */
@Poko
public class JobLifecycle @InternalAlexandriteApi constructor(
    override val turn: TurnInfo,
    public val run: RunId,
    public val call: ToolCallPart,
) : TurnScoped
