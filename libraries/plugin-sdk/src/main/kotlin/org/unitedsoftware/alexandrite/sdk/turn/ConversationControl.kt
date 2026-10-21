package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.time.Instant

/**
 * The current conversation of a chat's agent, controlled without a permission check, recording who asked as `by`.
 *
 * A change that breaks a rule throws [ControlRefusedException] with a message for the user and changes nothing.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ConversationControl {
    /**
     * Seals the conversation of the chat's agent once the turns queued at its key before the call have ended, and
     * returns the new one.
     *
     * Throws [IllegalStateException] when called in a turn, in a change of the chat's key or a hook it fires, or once
     * the agent stops.
     */
    public suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId

    /**
     * Folds the older part of the conversation of the chat's agent into its rolling summary once the turns queued at its
     * key before the call have ended, with [focus] added to the summarizer's instructions.
     *
     * Throws [ControlRefusedException] when there is nothing to fold or no summary was written, and
     * [IllegalStateException] where [newConversation] does.
     */
    public suspend fun compact(chat: ChatAddress, focus: String?, by: ChatUser?): CompactionReport

    /** How much of its model's context window the current conversation of the chat's agent takes. */
    public suspend fun context(chat: ChatAddress): ContextStatus

    /**
     * Clears the taint of the conversations that the chat's agents have in the chat, or of [agent]'s alone, once the
     * turns queued at their keys before the call have ended, so that their next turns run as in clean conversations
     * until something new taints them, and returns those that had a mark in the order of the chat's agents. Background
     * jobs and sub-agent runs started before taint it again only with what they take in themselves.
     *
     * Throws [IllegalStateException] where [newConversation] does.
     */
    public suspend fun untaint(chat: ChatAddress, by: ChatUser?, agent: AgentId? = null): List<UntaintedConversation>

    /**
     * Clears the taint of the heartbeat conversation of the chat's agent, or of [agent], as [untaint] does for the
     * chat's own, once the heartbeat queued before the call has ended, and returns the mark it had, null when it had
     * none.
     *
     * Throws [IllegalStateException] where [newConversation] does.
     */
    public suspend fun untaintHeartbeat(chat: ChatAddress, by: ChatUser?, agent: AgentId? = null): ConversationTaint?
}

/** The conversation of [agent] in a chat whose mark [ConversationControl.untaint] cleared. */
@Poko
public class UntaintedConversation(
    public val agent: AgentId,
    /** The mark it had. */
    public val taint: ConversationTaint,
)

/** What a compaction folded into the rolling summary. */
@Poko
public class CompactionReport(
    /** The stored entries that the compaction folded into the summary. */
    public val folded: Int,
    /** The prompt tokens before the compaction, as the agent estimates them. */
    public val tokensBefore: Long,
    /** The prompt tokens after the compaction, as the agent estimates them. */
    public val tokensAfter: Long,
)

/** How much of its model's context window a conversation takes. */
@Poko
public class ContextStatus(
    public val conversation: ConversationId,
    /** The model the conversation's turns call now, null when its agent has none. */
    public val model: ModelRef?,
    /** The prompt tokens of the conversation's last request, as its backend counted them, null before one. */
    public val promptTokens: Long?,
    /** When [promptTokens] was counted. */
    public val measuredAt: Instant?,
    /** The context window the conversation is kept within, null without a [model]. */
    public val window: Int?,
    /** The prompt tokens from which the conversation is compacted, null without a [window]. */
    public val trigger: Long?,
    /** The prompt tokens a compaction sheds down to, null without a [window]. */
    public val target: Long?,
    /** When the conversation was last compacted, null when it never was. */
    public val compactedAt: Instant?,
    /** The stored entries that the conversation's summary stands in for. */
    public val summarized: Int,
    /** Whether [window] is assumed, as the model's endpoint gives none. */
    public val windowAssumed: Boolean = false,
)
