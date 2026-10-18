package org.foedusprogramme.alexandrite.agent.delivery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.agent.prompt.Escaper
import org.foedusprogramme.alexandrite.agent.prompt.SENT_HEADER
import org.foedusprogramme.alexandrite.agent.prompt.lines
import org.foedusprogramme.alexandrite.agent.prompt.oneLine
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.ConversationStore
import org.foedusprogramme.alexandrite.sdk.store.Records
import org.foedusprogramme.alexandrite.sdk.store.collection
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** The source of the entry that tells the model what it sent to a chat from elsewhere. */
internal const val SENT_SOURCE: String = "alexandrite.sent"

/**
 * The messages that `chat.send` posted to each active conversation's chat from another conversation since the
 * conversation's last turn, not yet shown to the model there.
 */
@Singleton
internal class SentMessages(records: Records, private val conversations: ConversationStore) : Lifecycle {
    private val pending = records.collection<SentRecord>(COLLECTION)
    private val writing = Mutex()

    override suspend fun onStart() {
        for (record in pending.list()) {
            val state = conversations.conversation(ConversationId(record.key))?.state
            if (state != ConversationState.ACTIVE) pending.remove(record.key)
        }
    }

    /** Records that [message] was sent to the chat of [conversation]; keeps the newest [MAX_MESSAGES]. */
    suspend fun add(conversation: ConversationId, message: SentMessage) {
        writing.withLock {
            try {
                val next = (read(conversation) + message.cut()).takeLast(MAX_MESSAGES)
                pending.put(conversation.value, SentRecord(next), null)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("Cannot note a message sent to conversation {}: {}", conversation, e.toString())
            }
        }
    }

    /** The messages sent to [conversation]'s chat, told in [zone], null when there are none or they cannot be read. */
    suspend fun pending(conversation: ConversationId, zone: ZoneId): SentComparison? {
        val messages = try {
            read(conversation)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the messages sent to conversation {}: {}", conversation, e.toString())
            return null
        }
        return messages.takeIf { it.isNotEmpty() }?.let { SentComparison(it, zone) }
    }

    /** Clears the messages of [comparison] from [conversation], keeping any sent since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: SentComparison) {
        writing.withLock {
            try {
                val current = read(conversation)
                if (current.take(comparison.messages.size) != comparison.messages) return@withLock
                val remainder = current.drop(comparison.messages.size)
                if (remainder.isEmpty()) {
                    pending.remove(conversation.value)
                } else {
                    pending.put(conversation.value, SentRecord(remainder), null)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("Cannot clear the messages shown to conversation {}: {}", conversation, e.toString())
            }
        }
    }

    /** Forgets the messages sent to [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        try {
            pending.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the messages sent to sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private suspend fun read(conversation: ConversationId): List<SentMessage> =
        pending.get(conversation.value)?.value?.messages.orEmpty()

    private companion object {
        const val COLLECTION = "sent_messages"
        const val MAX_MESSAGES = 20
    }
}

/** The messages sent to a conversation's chat, with the unstored entry that tells the model about them. */
internal class SentComparison(val messages: List<SentMessage>, zone: ZoneId) {
    val notice: UserEntry = UserEntry(
        null,
        listOf(ContextPart(SENT_SOURCE, sentFraming(messages, zone))),
        UserOrigin.SystemWritten(SENT_SOURCE),
    )
}

/** Sent framing v1: what the model sent to this chat from elsewhere since its last turn here, when and from where. */
internal fun sentFraming(messages: List<SentMessage>, zone: ZoneId): String = buildString {
    appendLine("[alexandrite:sent]")
    appendLine("$SENT_HEADER since your last turn here (they need no answer of their own):")
    for (message in messages) {
        val at = instantOrNull(message.at)?.let { TIME.format(it.atZone(zone)) } ?: message.at
        append("- At $at, from ${oneLine(message.from)}:")
        for (line in lines(Escaper.HEADERS.text(message.text))) append("\n  > $line")
        appendLine()
    }
    append("[/alexandrite:sent]")
}

@Serializable
internal data class SentRecord(val messages: List<SentMessage> = emptyList())

/** [text] sent at the ISO-8601 instant [at] from [from], such as "a heartbeat" or "chat telegram:main:1". */
@Serializable
internal data class SentMessage(val at: String, val from: String, val text: String) {
    /** This message with its text cut to [MAX_TEXT_CHARS]. */
    fun cut(): SentMessage =
        if (text.length <= MAX_TEXT_CHARS) this else copy(text = "${text.take(MAX_TEXT_CHARS)} [cut]")

    private companion object {
        const val MAX_TEXT_CHARS = 2000
    }
}

private fun instantOrNull(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: DateTimeParseException) {
    null
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", Locale.ROOT)

private val logger = LoggerFactory.getLogger(SentMessages::class.java)
