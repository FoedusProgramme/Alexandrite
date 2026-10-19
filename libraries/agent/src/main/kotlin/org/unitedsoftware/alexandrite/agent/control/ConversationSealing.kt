package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.worker.newTurnId
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Rotation
import java.time.Clock

/** Seals conversations at the request of a host API and tells what follows a sealed one. */
@Singleton
internal class ConversationSealing(
    private val directory: AgentDirectory,
    private val states: SettingsStates,
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val seals: List<ConversationSeals>,
    private val hooks: TurnHooks,
    private val clock: Clock,
) {
    /** Seals the conversation at [key] once the turns queued there have ended. */
    fun seal(key: AgentChatKey, command: TurnInfo?, by: ChatUser?) {
        runner.detached(key, "The seal of the conversation") {
            sealed(key, key.chat, command, by, conversations.newConversation(key))
        }
    }

    /**
     * Fires `conversation.sealed` for [rotation] at [key] with the turn of [command] when it ran at [key], else with a
     * COMMAND turn of [by] in [chat].
     */
    suspend fun sealed(key: AgentChatKey, chat: ChatAddress, command: TurnInfo?, by: ChatUser?, rotation: Rotation) {
        val sealed = rotation.sealed?.id ?: return
        for (seal in seals) seal.sealed(sealed)
        val turn = command?.takeIf { it.key == key }
            ?: states.commandTurn(newTurnId(clock.instant()), key, chat, sealed, by, directory.agent(key.agent), clock)
        hooks.conversationSealed(turn, sealed, rotation.successor.id)
    }
}
