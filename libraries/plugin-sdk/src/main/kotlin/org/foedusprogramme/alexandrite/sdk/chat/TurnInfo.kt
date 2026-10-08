package org.foedusprogramme.alexandrite.sdk.chat

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable

/**
 * The identity of one turn, carried by every turn-scoped payload.
 *
 * - [key] is the worker and lock key of top-level turns; delegated runs never queue on their key's worker, and per-turn
 *   locks are per conversation.
 * - Only CHAT-target turns open a reply (`Channel.openReply`) and fire `response.preview`, and `response.before` fires
 *   for every reply sent to a chat.
 * - `TurnOutcome.replayable` is false once any run the turn delegated has persisted anything.
 * - Authority flows only from a live `ToolContext`, which is valid only during `Tool.execute`; `TurnInitiator` never
 *   creates a turn with a principal.
 * - Cancelling a chat's running turn cancels every run it delegated, background runs of the chat included.
 */
@Poko
public class TurnInfo private constructor(
    public val id: TurnId,
    /** The turn's policy and attribution home, also when it delivers to its caller. */
    public val chat: ChatAddress,
    /** Not necessarily the chat's current conversation. */
    public val conversation: ConversationId,
    public val kind: TurnKind,
    /** The principal whose authority the turn carries, which delegated turns and approval continuations inherit. */
    public val actor: ChatUser?,
    public val language: LanguageTag?,
    public val agent: AgentId,
    /** How a delegated turn descends from a top-level one, null for a top-level turn. */
    public val lineage: TurnLineage?,
    public val replyTarget: ReplyTarget,
) {
    /** Whether [actor] is an admin, false when there is none. */
    public val actorIsAdmin: Boolean get() = actor?.isAdmin == true

    public val key: AgentChatKey get() = AgentChatKey(agent, chat)

    public fun toBuilder(): Builder = Builder(id, chat, conversation, kind)
        .actor(actor)
        .language(language)
        .agent(agent)
        .lineage(lineage)
        .replyTarget(replyTarget)

    public class Builder internal constructor(
        private var id: TurnId,
        private var chat: ChatAddress,
        private var conversation: ConversationId,
        private var kind: TurnKind,
    ) {
        private var actor: ChatUser? = null
        private var language: LanguageTag? = null
        private var agent: AgentId = AgentId.MAIN
        private var lineage: TurnLineage? = null
        private var replyTarget: ReplyTarget = ReplyTarget.CHAT

        public fun id(id: TurnId): Builder = apply { this.id = id }

        public fun chat(chat: ChatAddress): Builder = apply { this.chat = chat }

        public fun conversation(conversation: ConversationId): Builder = apply { this.conversation = conversation }

        public fun kind(kind: TurnKind): Builder = apply { this.kind = kind }

        public fun actor(actor: ChatUser?): Builder = apply { this.actor = actor }

        public fun language(language: LanguageTag?): Builder = apply { this.language = language }

        public fun agent(agent: AgentId): Builder = apply { this.agent = agent }

        public fun lineage(lineage: TurnLineage?): Builder = apply { this.lineage = lineage }

        public fun replyTarget(replyTarget: ReplyTarget): Builder = apply { this.replyTarget = replyTarget }

        public fun build(): TurnInfo =
            TurnInfo(id, chat, conversation, kind, actor, language, agent, lineage, replyTarget)
    }

    public companion object {
        public fun builder(id: TurnId, chat: ChatAddress, conversation: ConversationId, kind: TurnKind): Builder =
            Builder(id, chat, conversation, kind)
    }
}

public inline fun TurnInfo.rebuild(block: TurnInfo.Builder.() -> Unit): TurnInfo = toBuilder().apply(block).build()

/** What started a turn. */
@JvmInline
@Serializable
public value class TurnKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** A message from the chat. */
        public val MESSAGE: TurnKind = TurnKind("message")

        /** A command invocation from the chat. */
        public val COMMAND: TurnKind = TurnKind("command")

        /** A heartbeat of the agent. */
        public val HEARTBEAT: TurnKind = TurnKind("heartbeat")

        /** A reminder coming due. */
        public val REMINDER: TurnKind = TurnKind("reminder")

        /** The result of an approved, denied or failed tool call. */
        public val APPROVAL: TurnKind = TurnKind("approval")

        /** A sub-agent run delegated by another turn. */
        public val DELEGATED: TurnKind = TurnKind("delegated")

        /** A message from another main agent. */
        public val AGENT_MESSAGE: TurnKind = TurnKind("agent_message")
    }
}

/** Where a turn's reply goes. */
@JvmInline
@Serializable
public value class ReplyTarget internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The turn's chat. */
        public val CHAT: ReplyTarget = ReplyTarget("chat")

        /** Only the turn's initiator. */
        public val CALLER: ReplyTarget = ReplyTarget("caller")
    }
}

/** How a delegated turn descends from the top-level turn of its tree. */
@Poko
public class TurnLineage(
    public val run: RunId,
    public val parentTurn: TurnId,
    public val parentConversation: ConversationId,
    /** The tool call that started the run, null when no call did. */
    public val parentCall: ToolCallId?,
    public val rootTurn: TurnId,
    public val rootConversation: ConversationId,
    /** 1 for a child of a top-level turn. */
    public val depth: Int,
) {
    init {
        require(depth >= 1) { "A lineage depth is at least 1, was $depth." }
    }
}
