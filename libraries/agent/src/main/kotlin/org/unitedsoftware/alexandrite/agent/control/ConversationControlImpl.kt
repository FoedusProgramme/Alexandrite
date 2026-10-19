package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.context.ContextControl
import org.unitedsoftware.alexandrite.agent.permission.UnstoredTaints
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
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

@Singleton
@Binds(ConversationControl::class)
internal class ConversationControlImpl(
    private val runner: ControlRunner,
    private val conversations: ConversationStore,
    private val contexts: ContextControl,
    private val sealing: ConversationSealing,
    private val unstored: UnstoredTaints,
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

    override suspend fun untaint(chat: ChatAddress, by: ChatUser?): ConversationTaint? {
        val key = runner.key(chat)
        val cleared = runner.onWorker(key) { cleared(conversations.current(key).id) }
        if (cleared != null) {
            logger.info(
                "The taint of the conversation of {} (since {}: {}) is cleared by {}",
                key,
                cleared.at,
                cleared.reason,
                who(by),
            )
        }
        return cleared
    }

    override suspend fun untaintHeartbeat(chat: ChatAddress, by: ChatUser?): ConversationTaint? {
        val agent = runner.key(chat).agent
        val key = AgentChatKey(agent, ChatAddress.ofAgent(agent))
        val cleared = runner.onWorker(key) { cleared(conversations.heartbeatBase(key).id) }
        if (cleared != null) {
            logger.info(
                "The taint of the heartbeat conversation of agent {} (since {}: {}) is cleared by {}",
                agent,
                cleared.at,
                cleared.reason,
                who(by),
            )
        }
        return cleared
    }

    /** Clears the mark of [conversation], one that could not be stored included, and returns it. */
    private suspend fun cleared(conversation: ConversationId): ConversationTaint? {
        val unstored = unstored.cleared(conversation)
        return conversations.clearTaint(conversation) ?: unstored
    }
}

private val logger = LoggerFactory.getLogger(ConversationControlImpl::class.java)
