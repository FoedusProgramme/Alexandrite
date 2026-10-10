package org.foedusprogramme.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId

/**
 * The conversations of each agent and chat, which of them are current, and the turns that ran in them.
 *
 * - Only a store backend implements it, and only the agent calls it.
 * - Every call is atomic, and a call that throws changes nothing.
 * - The store mints each [ConversationId], unique and opaque.
 * - A chat's address may change later: the store keeps it in one place per chat, so a move rewrites that place alone.
 */
@BoundSpi
public interface ConversationStore {
    /** The current conversation of [key], which [newConversation] replaces, created when there is none. */
    public suspend fun current(key: AgentChatKey): ConversationInfo

    /** The conversation of the heartbeat turns of [key], which [newConversation] keeps, created when there is none. */
    public suspend fun heartbeatBase(key: AgentChatKey): ConversationInfo

    /**
     * Seals the current conversation of [key], creates its successor and makes the successor current, all or nothing,
     * or only creates a current conversation where [key] has none.
     */
    public suspend fun newConversation(key: AgentChatKey): Rotation

    /**
     * A new conversation of [key] for the run that [lineage] describes, which starts from the history [fork] names when
     * there is one; throws [IllegalArgumentException] unless [lineage] descends from a recorded parent turn of [key]'s
     * chat and [fork] names a stored conversation.
     */
    public suspend fun createDelegated(
        key: AgentChatKey,
        lineage: TurnLineage,
        fork: ForkPoint? = null,
    ): ConversationInfo

    /** The conversation [id], null when the store has none. */
    public suspend fun conversation(id: ConversationId): ConversationInfo?

    /** The chats where [agent] has a [ConversationKind.USER_LANE] conversation, each once. */
    public suspend fun chats(agent: AgentId): List<ChatAddress>

    /**
     * Records that [turn] started; throws [IllegalArgumentException] when the turn is recorded already or its
     * conversation is unknown or belongs to another agent chat key, and [IllegalStateException] when the conversation
     * is not [ConversationState.ACTIVE].
     */
    public suspend fun startTurn(turn: TurnInfo)

    /** Records that the turn [id] ended as [end], false when it is unknown or ended before. */
    public suspend fun endTurn(id: TurnId, end: TurnEndKind): Boolean

    /** The turn [id], null when the store has none. */
    public suspend fun turn(id: TurnId): TurnRecord?

    /** The turns that started and never ended, oldest first. */
    public suspend fun unendedTurns(): List<TurnRecord>
}

/** What [ConversationStore.newConversation] did. */
@Poko
public class Rotation(
    /** Null when there was no current conversation. */
    public val sealed: ConversationInfo?,
    public val successor: ConversationInfo,
)

/** The entries of [conversation] up to [through], which a conversation starts from. */
@Poko
public class ForkPoint(public val conversation: ConversationId, public val through: EntryId)
