package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.agent.control.ConversationSeals
import org.unitedsoftware.alexandrite.agent.control.PendingRecords
import org.unitedsoftware.alexandrite.agent.control.afterShown
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.SENT_HEADER
import org.unitedsoftware.alexandrite.agent.prompt.lines
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
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
@Contribute(ConversationSeals::class)
internal class SentMessages(records: Records, private val conversations: ConversationStore) :
    Lifecycle,
    ConversationSeals {
    private val pending = PendingRecords(records.collection<SentRecord>(COLLECTION), "sent messages") {
        it.messages.isEmpty()
    }

    override suspend fun onStart() {
        pending.prune(conversations)
    }

    /** Records that [message] was sent to the chat of [conversation]; keeps the newest [MAX_MESSAGES]. */
    suspend fun add(conversation: ConversationId, message: SentMessage) {
        pending.change(conversation) { current ->
            SentRecord((current?.messages.orEmpty() + message.cut()).takeLast(MAX_MESSAGES))
        }
    }

    /** The messages sent to [conversation]'s chat, told in [zone], null when there are none or they cannot be read. */
    suspend fun pending(conversation: ConversationId, zone: ZoneId): SentComparison? =
        pending.get(conversation)?.let { SentComparison(it.messages, zone) }

    /** Clears the messages of [comparison] from [conversation], keeping any sent since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: SentComparison) {
        pending.change(conversation) { current ->
            current?.let { SentRecord(it.messages.afterShown(comparison.messages)) }
        }
    }

    override suspend fun sealed(conversation: ConversationId) {
        pending.forget(conversation)
    }

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
