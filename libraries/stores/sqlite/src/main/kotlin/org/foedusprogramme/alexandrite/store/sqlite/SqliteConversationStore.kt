package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationInfo
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.ForkPoint
import org.foedusprogramme.alexandrite.sdk.store.Rotation
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import java.sql.ResultSet

@Singleton
@Binds(ConversationStore::class)
internal class SqliteConversationStore(private val database: StoreDatabase) : ConversationStore {
    override suspend fun current(key: AgentChatKey): ConversationInfo =
        database.transaction { currentOf(key, ConversationKind.USER_LANE) }

    override suspend fun heartbeatBase(key: AgentChatKey): ConversationInfo =
        database.transaction { currentOf(key, ConversationKind.HEARTBEAT_BASE) }

    override suspend fun newConversation(key: AgentChatKey): Rotation = database.transaction {
        val chat = chatId(key.chat)
        val old = pointer(key.agent, chat, ConversationKind.USER_LANE)
        val successor = insert(ConversationKind.USER_LANE, key.agent, chat, lineage = null, fork = null)
        if (old != null) {
            val sealed = execute(
                "UPDATE conversations SET state = ?, sealed_at = ?, successor = ? WHERE id = ? AND state = ?",
                ConversationState.SEALED.id,
                now,
                successor.value,
                old.value,
                ConversationState.ACTIVE.id,
            )
            check(sealed == 1) { "The current conversation $old of $key is not active." }
        }
        point(key.agent, chat, ConversationKind.USER_LANE, successor)
        Rotation(old?.let { info(it) }, info(successor))
    }

    override suspend fun createDelegated(key: AgentChatKey, lineage: TurnLineage, fork: ForkPoint?): ConversationInfo =
        database.transaction {
            val parent = turnOrNull(lineage.parentTurn)
                ?: throw IllegalArgumentException("The parent turn ${lineage.parentTurn} is not recorded.")
            require(parent.conversation == lineage.parentConversation) {
                "The parent turn ${parent.id} ran in conversation ${parent.conversation}, not in " +
                    "${lineage.parentConversation}."
            }
            require(parent.key.chat == key.chat) {
                "A delegated conversation belongs to the chat of its parent turn, ${parent.key.chat}, not to " +
                    "${key.chat}."
            }
            require(
                lineage.depth == (parent.lineage?.depth ?: 0) + 1 &&
                    lineage.rootTurn == (parent.lineage?.rootTurn ?: parent.id) &&
                    lineage.rootConversation == (parent.lineage?.rootConversation ?: parent.conversation),
            ) { "The lineage of run ${lineage.run} does not descend from its parent turn ${parent.id}." }
            fork?.let { require(infoOrNull(it.conversation) != null) { "Cannot fork the unknown ${it.conversation}." } }
            info(insert(ConversationKind.DELEGATED, key.agent, chatId(key.chat), lineage, fork))
        }

    override suspend fun conversation(id: ConversationId): ConversationInfo? = database.transaction { infoOrNull(id) }

    override suspend fun chats(agent: AgentId): List<ChatAddress> = database.transaction {
        query(
            "SELECT address FROM chats WHERE id IN " +
                "(SELECT chat_id FROM conversations WHERE agent = ? AND kind = ?) ORDER BY id",
            agent.value,
            ConversationKind.USER_LANE.id,
        ) { ChatAddress.parse(getString(1)) }
    }

    override suspend fun startTurn(turn: TurnInfo) {
        database.transaction {
            val conversation = requireNotNull(infoOrNull(turn.conversation)) {
                "Turn ${turn.id} names the unknown conversation ${turn.conversation}."
            }
            require(conversation.key == turn.key) {
                "Turn ${turn.id} of ${turn.key} cannot run in conversation ${conversation.id} of ${conversation.key}."
            }
            require(turnOrNull(turn.id) == null) { "Turn ${turn.id} is recorded already." }
            check(conversation.state == ConversationState.ACTIVE) {
                "Conversation ${conversation.id} is ${conversation.state}, so it takes no new turns."
            }
            val lineage = turn.lineage
            execute(
                "INSERT INTO turns (id, conversation, agent, chat_id, kind, actor, run_id, parent_turn, " +
                    "parent_conversation, parent_call, root_turn, root_conversation, depth, started_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                turn.id.value,
                turn.conversation.value,
                turn.agent.value,
                chatId(turn.chat),
                turn.kind.id,
                turn.actor?.address?.toString(),
                lineage?.run?.value,
                lineage?.parentTurn?.value,
                lineage?.parentConversation?.value,
                lineage?.parentCall?.value,
                lineage?.rootTurn?.value,
                lineage?.rootConversation?.value,
                lineage?.depth ?: 0,
                now,
            )
        }
    }

    override suspend fun endTurn(id: TurnId, end: TurnEndKind): Boolean = database.transaction {
        execute(
            "UPDATE turns SET ended_at = ?, outcome = ? WHERE id = ? AND ended_at IS NULL",
            now,
            end.id,
            id.value,
        ) == 1
    }

    override suspend fun turn(id: TurnId): TurnRecord? = database.transaction { turnOrNull(id) }

    override suspend fun unendedTurns(): List<TurnRecord> = database.transaction {
        query(UNENDED_TURNS) { turnRecord() }
    }

    private fun Tx.currentOf(key: AgentChatKey, kind: ConversationKind): ConversationInfo {
        val chat = chatId(key.chat)
        val id = pointer(key.agent, chat, kind)
            ?: insert(kind, key.agent, chat, lineage = null, fork = null).also { point(key.agent, chat, kind, it) }
        return info(id)
    }

    private fun Tx.pointer(agent: AgentId, chat: Long, kind: ConversationKind): ConversationId? = queryOne(
        "SELECT conversation FROM current_conversations WHERE agent = ? AND chat_id = ? AND kind = ?",
        agent.value,
        chat,
        kind.id,
    ) { ConversationId(getString(1)) }

    private fun Tx.point(agent: AgentId, chat: Long, kind: ConversationKind, conversation: ConversationId) {
        execute(
            "INSERT INTO current_conversations (agent, chat_id, kind, conversation) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (agent, chat_id, kind) DO UPDATE SET conversation = excluded.conversation",
            agent.value,
            chat,
            kind.id,
            conversation.value,
        )
    }

    private fun Tx.insert(
        kind: ConversationKind,
        agent: AgentId,
        chat: Long,
        lineage: TurnLineage?,
        fork: ForkPoint?,
    ): ConversationId {
        val id = ConversationId(newId())
        execute(
            "INSERT INTO conversations (id, kind, agent, chat_id, state, created_at, run_id, parent_turn, " +
                "parent_conversation, parent_call, root_turn, root_conversation, depth, forked_from, " +
                "forked_through_entry) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id.value,
            kind.id,
            agent.value,
            chat,
            ConversationState.ACTIVE.id,
            now,
            lineage?.run?.value,
            lineage?.parentTurn?.value,
            lineage?.parentConversation?.value,
            lineage?.parentCall?.value,
            lineage?.rootTurn?.value,
            lineage?.rootConversation?.value,
            lineage?.depth ?: 0,
            fork?.conversation?.value,
            fork?.through?.value,
        )
        return id
    }

    private fun Tx.info(id: ConversationId): ConversationInfo =
        infoOrNull(id) ?: throw IllegalStateException("Conversation $id is not stored.")

    private fun Tx.infoOrNull(id: ConversationId): ConversationInfo? = queryOne(
        "SELECT c.*, chats.address FROM conversations c JOIN chats ON chats.id = c.chat_id WHERE c.id = ?",
        id.value,
    ) {
        val forkedFrom = getString("forked_from")
        ConversationInfo.builder(
            ConversationId(getString("id")),
            ConversationKind.of(getString("kind")),
            key(),
            ConversationState.of(getString("state")),
            instant("created_at"),
        )
            .sealedAt(instantOrNull("sealed_at"))
            .successor(getString("successor")?.let(::ConversationId))
            .lineage(lineage())
            .fork(forkedFrom?.let { ForkPoint(ConversationId(it), EntryId(getLong("forked_through_entry"))) })
            .build()
    }

    private fun Tx.turnOrNull(id: TurnId): TurnRecord? = queryOne("$TURNS WHERE t.id = ?", id.value) { turnRecord() }
}

private const val TURNS = "SELECT t.*, chats.address FROM turns t JOIN chats ON chats.id = t.chat_id"

internal const val UNENDED_TURNS = "$TURNS WHERE t.ended_at IS NULL ORDER BY t.started_at, t.rowid"

private fun ResultSet.turnRecord(): TurnRecord = TurnRecord.builder(
    TurnId(getString("id")),
    ConversationId(getString("conversation")),
    key(),
    TurnKind.of(getString("kind")),
    instant("started_at"),
)
    .actor(getString("actor")?.let(UserAddress::parse))
    .lineage(lineage())
    .endedAt(instantOrNull("ended_at"))
    .end(getString("outcome")?.let { TurnEndKind.of(it) })
    .build()

private fun ResultSet.key(): AgentChatKey =
    AgentChatKey(AgentId(getString("agent")), ChatAddress.parse(getString("address")))

private fun ResultSet.lineage(): TurnLineage? {
    val run = getString("run_id") ?: return null
    return TurnLineage(
        run = RunId(run),
        parentTurn = TurnId(getString("parent_turn")),
        parentConversation = ConversationId(getString("parent_conversation")),
        parentCall = getString("parent_call")?.let(::ToolCallId),
        rootTurn = TurnId(getString("root_turn")),
        rootConversation = ConversationId(getString("root_conversation")),
        depth = getInt("depth"),
    )
}
