package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.sdk.turn.TurnStatus

/**
 * An [AgentControl] that records its calls, lists the [statuses] the test sets, mints conversations `conversation-<n>`
 * and keeps chat settings per agent and chat, where [defaultAgent] serves every chat until an update switches it.
 */
public class RecordingAgentControl(private val defaultAgent: AgentId = AgentId.MAIN) : AgentControl {
    private val lock = Any()
    private val recorded = mutableListOf<Call>()
    private val agents = HashMap<ChatAddress, AgentId>()
    private val settings = HashMap<AgentChatKey, ChatSettingsSnapshot>()
    private var conversations = 0

    /** The running and queued turns that [turns] lists and the cancels find. */
    @Volatile
    public var statuses: List<TurnStatus> = emptyList()

    public val calls: List<Call> get() = synchronized(lock) { recorded.toList() }

    override fun turns(chat: ChatAddress?): List<TurnStatus> = statuses.filter { chat == null || it.turn.chat == chat }

    /** Whether a turn at [chat] runs. */
    override fun cancel(chat: ChatAddress, by: ChatUser?): Boolean {
        record(Call.Cancel(chat, by))
        return statuses.any { it.turn.chat == chat && it.phase == TurnPhase.RUNNING }
    }

    /** Whether [turn] is listed. */
    override fun cancelTurn(turn: TurnId, by: ChatUser?): Boolean {
        record(Call.CancelTurn(turn, by))
        return statuses.any { it.turn.id == turn }
    }

    /** Whether a turn of [run] is listed. */
    override fun cancelRun(run: RunId, tree: Boolean, by: ChatUser?): Boolean {
        record(Call.CancelRun(run, tree, by))
        return statuses.any { it.turn.lineage?.run == run }
    }

    override suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId = synchronized(lock) {
        ConversationId("conversation-${++conversations}").also { recorded += Call.NewConversation(chat, by, it) }
    }

    override suspend fun settings(chat: ChatAddress): ChatSettingsSnapshot = synchronized(lock) { snapshot(chat) }

    override suspend fun updateSettings(
        chat: ChatAddress,
        update: ChatSettingsUpdate,
        by: ChatUser?,
    ): ChatSettingsSnapshot = synchronized(lock) {
        recorded += Call.UpdateSettings(chat, update, by)
        when (val change = update.agent) {
            is SettingChange.SetTo -> agents[chat] = change.value
            SettingChange.Reset -> agents.remove(chat)
            else -> Unit
        }
        val current = snapshot(chat)
        val next = ChatSettingsSnapshot.builder(current.agent)
            .model(update.model.applyTo(current.model))
            .reasoning(update.reasoning.applyTo(current.reasoning))
            .language(update.language.applyTo(current.language))
            .build()
        settings[AgentChatKey(next.agent, chat)] = next
        next
    }

    private fun snapshot(chat: ChatAddress): ChatSettingsSnapshot {
        val agent = agents[chat] ?: defaultAgent
        return settings[AgentChatKey(agent, chat)] ?: ChatSettingsSnapshot.builder(agent).build()
    }

    private fun record(call: Call) {
        synchronized(lock) { recorded += call }
    }

    /** A call of the control port. */
    public sealed interface Call {
        public data class Cancel(public val chat: ChatAddress, public val by: ChatUser?) : Call

        public data class CancelTurn(public val turn: TurnId, public val by: ChatUser?) : Call

        public data class CancelRun(public val run: RunId, public val tree: Boolean, public val by: ChatUser?) : Call

        public data class NewConversation(
            public val chat: ChatAddress,
            public val by: ChatUser?,
            /** The conversation the call returned. */
            public val conversation: ConversationId,
        ) : Call

        public data class UpdateSettings(
            public val chat: ChatAddress,
            public val update: ChatSettingsUpdate,
            public val by: ChatUser?,
        ) : Call
    }
}

private fun <T : Any> SettingChange<T>.applyTo(current: T?): T? = when (this) {
    is SettingChange.SetTo -> value
    SettingChange.Reset -> null
    else -> current
}
