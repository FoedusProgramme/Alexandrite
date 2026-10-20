package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.context.ContextControl
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.turn.CompactionReport
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.UntaintedConversation

@Singleton
@Binds(ConversationControl::class)
internal class ConversationControlImpl(
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val contexts: ContextControl,
    private val sealing: ConversationSealing,
    private val directory: AgentDirectory,
    private val topology: ChatTopology,
) : ConversationControl {
    override suspend fun newConversation(chat: ChatAddress, by: ChatUser?): ConversationId {
        val key = runner.key(chat)
        val command = currentCoroutineContext()[RunningCommand]?.turn
        val rotation = runner.onWorker(key, after = { sealing.sealed(key, chat, command, by, it) }) {
            conversations.newConversation(key)
        }
        val successor = rotation.successor.id
        logger.info("The conversation of {} is sealed by {}: {} follows", key, who(by), successor)
        return successor
    }

    override suspend fun compact(chat: ChatAddress, focus: String?, by: ChatUser?): CompactionReport {
        val key = runner.key(chat)
        val report = runner.refusing(key) { runner.onWorker(key) { contexts.compact(key, chat, by, focus) } }
        logger.info(
            "The conversation of {} is compacted by {}: {} entries folded, about {} -> {} tokens",
            key,
            who(by),
            report.folded,
            report.tokensBefore,
            report.tokensAfter,
        )
        return report
    }

    override suspend fun context(chat: ChatAddress): ContextStatus = contexts.status(runner.key(chat))

    override suspend fun untaint(chat: ChatAddress, by: ChatUser?, agent: AgentId?): List<UntaintedConversation> {
        val keys = if (agent != null) listOf(topology.key(attached(chat, agent), chat)) else spoken(chat)
        return keys.mapNotNull { key ->
            val cleared = runner.onWorker(key) { conversations.clearTaint(conversations.current(key).id) }
                ?: return@mapNotNull null
            logger.info(
                "The taint of the conversation of {} (since {}: {}) is cleared by {}",
                key,
                cleared.at,
                cleared.reason,
                who(by),
            )
            UntaintedConversation(key.agent, cleared)
        }
    }

    override suspend fun untaintHeartbeat(chat: ChatAddress, by: ChatUser?, agent: AgentId?): ConversationTaint? {
        val id = agent?.let { attached(chat, it) } ?: runner.key(chat).agent
        val key = AgentChatKey(id, ChatAddress.ofAgent(id))
        val cleared = runner.onWorker(key) { conversations.clearTaint(conversations.heartbeatBase(key).id) }
        if (cleared != null) {
            logger.info(
                "The taint of the heartbeat conversation of agent {} (since {}: {}) is cleared by {}",
                id,
                cleared.at,
                cleared.reason,
                who(by),
            )
        }
        return cleared
    }

    /** [agent], refused unless it is attached to the channel instance of [chat]. */
    private suspend fun attached(chat: ChatAddress, agent: AgentId): AgentId = runner.refusing(null) {
        if (directory.attached(chat.instance).none { it.id == agent }) {
            throw Refused(TextKeys.AGENT_NOT_ATTACHED, "agent" to agent, "instance" to chat.instance)
        }
        agent
    }

    /** The keys of the agents of [chat]'s instance that have a conversation in [chat]. */
    private suspend fun spoken(chat: ChatAddress): List<AgentChatKey> =
        directory.attached(chat.instance).map { topology.key(it.id, chat) }
            .filter { key -> key.chat in conversations.chats(key.agent) }
}

private val logger = LoggerFactory.getLogger(ConversationControlImpl::class.java)
