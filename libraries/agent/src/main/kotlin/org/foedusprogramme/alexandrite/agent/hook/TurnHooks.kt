package org.foedusprogramme.alexandrite.agent.hook

import org.foedusprogramme.alexandrite.agent.prompt.Escaper
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.hook.Hooks
import org.foedusprogramme.alexandrite.sdk.hook.Interception
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.Usage
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.turn.ContextLoaded
import org.foedusprogramme.alexandrite.sdk.turn.ConversationSealed
import org.foedusprogramme.alexandrite.sdk.turn.ModelCall
import org.foedusprogramme.alexandrite.sdk.turn.ModelReply
import org.foedusprogramme.alexandrite.sdk.turn.PromptSections
import org.foedusprogramme.alexandrite.sdk.turn.ReplyDraft
import org.foedusprogramme.alexandrite.sdk.turn.ReplyPreview
import org.foedusprogramme.alexandrite.sdk.turn.ToolCallCheck
import org.foedusprogramme.alexandrite.sdk.turn.ToolCallDone
import org.foedusprogramme.alexandrite.sdk.turn.TurnCommitted
import org.foedusprogramme.alexandrite.sdk.turn.TurnContext
import org.foedusprogramme.alexandrite.sdk.turn.TurnInput
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.sdk.turn.TurnStart
import org.slf4j.LoggerFactory

/** What a chain of interceptors left of a turn. */
internal sealed interface Hooked<out T> {
    class Proceed<T>(val value: T) : Hooked<T>

    /** A hook stopped the turn with [reply], null when it gave none or failed. */
    class Stopped(val reply: String?) : Hooked<Nothing>
}

/** The value a chain of interceptors left, or what [stop] makes of the reply of the hook that stopped it. */
internal inline fun <T> Hooked<T>.or(stop: (reply: String?) -> Nothing): T = when (this) {
    is Hooked.Proceed -> value
    is Hooked.Stopped -> stop(reply)
}

/** Fires the turn points with the payloads the agent builds. */
@Singleton
internal class TurnHooks(private val hooks: Hooks) {
    /** The tools of [tools] that [turn] keeps. */
    suspend fun turnStart(
        turn: TurnInfo,
        message: IncomingMessage?,
        tools: List<ToolDefinition>,
    ): Hooked<List<ToolDefinition>> =
        hooks.fire(TurnPoints.TURN_START, TurnStart(turn, message, tools)).map { narrowed(turn, tools, it.tools) }

    /** The text that the model reads of [turn]'s input, or of a follow-up that joins [turn]. */
    suspend fun turnInput(
        turn: TurnInfo,
        message: IncomingMessage?,
        text: String,
        followUp: Boolean = false,
    ): Hooked<String> = hooks.fire(TurnPoints.TURN_INPUT, TurnInput(turn, message, text, followUp)).map { it.text }

    /** The turn-context items of [turn], whose input reads [text]: [builtIns] and what hooks added. */
    suspend fun contextInject(turn: TurnInfo, text: String, builtIns: List<TurnContextItem>): List<TurnContextItem> {
        val fired = hooks.fire(TurnPoints.CONTEXT_INJECT, TurnContext(turn, text, builtIns))
        val items = (fired as? Interception.Proceed)?.payload?.items ?: return builtIns
        if (items.take(builtIns.size) != builtIns) {
            logger.warn("A context.inject hook removed turn {}'s own turn context: no item of a hook is kept", turn.id)
            return builtIns
        }
        val (reserved, added) = items.drop(builtIns.size).partition { isReserved(it.source) }
        if (reserved.isNotEmpty()) {
            logger.warn(
                "A context.inject hook gave turn {} turn context of the reserved sources {}: it is ignored",
                turn.id,
                reserved.joinToString { it.source },
            )
        }
        return builtIns + added
    }

    suspend fun contextLoaded(turn: TurnInfo, history: List<TranscriptEntry>) {
        hooks.fire(TurnPoints.CONTEXT_LOADED, ContextLoaded(turn, history))
    }

    /** The sections of [turn]'s system prompt, where hooks' text is escaped and their reserved sections ignored. */
    suspend fun promptSections(turn: TurnInfo, sections: List<PromptSection>): List<PromptSection> {
        val fired = hooks.fire(TurnPoints.PROMPT_SECTIONS, PromptSections(turn, sections))
        val hooked = (fired as? Interception.Proceed)?.payload?.sections ?: return sections
        val own = sections.associateBy { it.id }
        val reserved = mutableListOf<String>()
        val kept = hooked.mapNotNull { section ->
            when {
                section in sections -> section

                isReserved(section.id) -> {
                    reserved += section.id
                    own[section.id]
                }

                else -> PromptSection(section.id, Escaper.HEADERS.text(section.text), section.stable)
            }
        }
        if (reserved.isNotEmpty()) {
            logger.warn(
                "A prompt.sections hook gave turn {} the sections {}, whose ids are reserved: they are ignored",
                turn.id,
                reserved.joinToString(),
            )
        }
        return kept
    }

    suspend fun llmRequest(turn: TurnInfo, round: Int, request: ModelRequest): Hooked<ModelRequest> =
        hooks.fire(TurnPoints.LLM_REQUEST, ModelCall(turn, round, request)).map { it.request }

    suspend fun llmResponse(turn: TurnInfo, round: Int, response: ModelEvent.Completed): Hooked<Unit> =
        hooks.fire(TurnPoints.LLM_RESPONSE, ModelReply(turn, round, response)).map { }

    /** Whether the hooks let [call] run in [turn], and why they require approval, null when they do not. */
    suspend fun toolBefore(
        turn: TurnInfo,
        call: ToolCallPart,
        definition: ToolDefinition,
        risk: ToolRisk,
    ): Hooked<String?> =
        hooks.fire(TurnPoints.TOOL_BEFORE, ToolCallCheck(turn, call, definition, risk)).map { it.requireApproval }

    suspend fun toolAfter(turn: TurnInfo, call: ToolCallPart, result: ToolResultEntry) {
        hooks.fire(TurnPoints.TOOL_AFTER, ToolCallDone(turn, call, result))
    }

    /** The text that [turn]'s chat is shown in place of the preview [text] of [segment], null for none. */
    suspend fun responsePreview(turn: TurnInfo, segment: Int, text: String): String? {
        val fired = hooks.fire(TurnPoints.RESPONSE_PREVIEW, ReplyPreview(turn, segment, text))
        return (fired as? Interception.Proceed)?.payload?.text
    }

    /** The message that [destination] gets in place of [message]. */
    suspend fun responseBefore(turn: TurnInfo, message: OutboundMessage, destination: ChatAddress): OutboundMessage {
        val fired = hooks.fire(TurnPoints.RESPONSE_BEFORE, ReplyDraft(turn, message, destination))
        return (fired as? Interception.Proceed)?.payload?.message ?: message
    }

    suspend fun turnCommitted(turn: TurnInfo, entries: List<TranscriptEntry>, outcome: TurnOutcome, usage: Usage?) {
        hooks.fire(TurnPoints.TURN_COMMITTED, TurnCommitted(turn, entries, outcome, usage))
    }

    suspend fun conversationSealed(turn: TurnInfo, sealed: ConversationId, successor: ConversationId) {
        hooks.fire(TurnPoints.CONVERSATION_SEALED, ConversationSealed(turn, sealed, successor))
    }
}

private fun <P : Any, T> Interception<P>.map(value: (P) -> T): Hooked<T> = when (this) {
    is Interception.Proceed -> Hooked.Proceed(value(payload))
    is Interception.Aborted -> Hooked.Stopped(reply)
    else -> Hooked.Stopped(null)
}

/** Whether [source] is one that only Alexandrite writes. */
private fun isReserved(source: String): Boolean = source.startsWith(RESERVED_PREFIX)

private const val RESERVED_PREFIX = "alexandrite."

/** The tools of [offered] that [kept] names. */
private fun narrowed(turn: TurnInfo, offered: List<ToolDefinition>, kept: List<ToolDefinition>): List<ToolDefinition> {
    val names = kept.map { it.name }.toSet()
    val added = names - offered.map { it.name }.toSet()
    if (added.isNotEmpty()) {
        logger.warn(
            "A turn.start hook gave turn {} the tools {}, which it does not offer: they are ignored",
            turn.id,
            added.sorted().joinToString(),
        )
    }
    return offered.filter { it.name in names }
}

private val logger = LoggerFactory.getLogger(TurnHooks::class.java)
