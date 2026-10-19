package org.unitedsoftware.alexandrite.agent.messaging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection

/** The agent messages whose reply has not come back yet, as records that a restart reads. */
@Singleton
internal class MessageRecords(records: Records) {
    private val messages = records.collection<MessageRecord>(COLLECTION)

    /** Records [id], which a restart reports to [record]'s conversation as interrupted. */
    suspend fun put(id: RunId, record: MessageRecord) {
        try {
            messages.put(id.value, record, null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Message {} is not recorded, so a restart would not report it: {}", id, e.toString())
        }
    }

    suspend fun remove(id: RunId) {
        try {
            messages.remove(id.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("The record of message {} could not be removed: {}", id, e.toString())
        }
    }

    suspend fun all(): Map<RunId, MessageRecord> = messages.list().associate { RunId(it.key) to it.value }

    private companion object {
        const val COLLECTION = "agent_messages"
    }
}

/** A message to [agent] that [key] sent from [chat] at [sentAt], told in [conversation] at [turn] when interrupted. */
@Serializable
internal class MessageRecord(
    val conversation: ConversationId,
    val key: AgentChatKey,
    val chat: ChatAddress,
    val turn: TurnId,
    val agent: AgentId,
    /** An ISO-8601 instant. */
    val sentAt: String,
)

private val logger = LoggerFactory.getLogger(MessageRecords::class.java)
