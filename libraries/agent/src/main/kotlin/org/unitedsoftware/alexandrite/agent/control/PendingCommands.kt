package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.prompt.COMMANDS_HEADER
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.lines
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/** The source of the entry that tells the model which commands ran since its last turn. */
internal const val COMMANDS_SOURCE: String = "alexandrite.commands"

/** A reply a command sent, told to the model as [text] when [forModel], else withheld from it. */
internal class ReplyRecord(val text: String, val forModel: Boolean)

/** The commands that ran in each conversation since its last turn, not yet shown to the model. */
@Singleton
internal class PendingCommands(records: Records, private val conversations: ConversationStore) : Lifecycle {
    private val pending = records.collection<PendingCommandsRecord>(COLLECTION)
    private val writing = Mutex()

    override suspend fun onStart() {
        for (record in pending.list()) {
            val state = conversations.conversation(ConversationId(record.key))?.state
            if (state != ConversationState.ACTIVE) pending.remove(record.key)
        }
    }

    /**
     * Records that [issuer] ran [commandLine] in [conversation], telling it [replies]; keeps the newest 20. A
     * conversation the command itself sealed (e.g. `/new`) is left alone: it is no longer current for anyone.
     */
    suspend fun record(
        conversation: ConversationId,
        issuer: ChatUser,
        commandLine: String,
        replies: List<ReplyRecord>,
    ) {
        val entry = PendingCommandRecord(issuer.displayName, issuer.isAdmin, commandLine, replies.map(::stored))
        writing.withLock {
            try {
                if (conversations.conversation(conversation)?.state != ConversationState.ACTIVE) return@withLock
                val next = (read(conversation) + entry).takeLast(MAX_COMMANDS)
                pending.put(conversation.value, PendingCommandsRecord(next), null)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn(
                    "Cannot record command '{}' of conversation {}: {}",
                    commandLine,
                    conversation,
                    e.toString(),
                )
            }
        }
    }

    /** The commands pending for [conversation], null when there are none or they cannot be read. */
    suspend fun pending(conversation: ConversationId): PendingComparison? {
        val commands = try {
            read(conversation)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot read the commands pending for conversation {}: {}", conversation, e.toString())
            return null
        }
        return commands.takeIf { it.isNotEmpty() }?.let(::PendingComparison)
    }

    /** Clears the commands of [comparison] from [conversation], keeping any recorded since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: PendingComparison) {
        writing.withLock {
            try {
                val current = read(conversation)
                if (current.take(comparison.commands.size) != comparison.commands) return@withLock
                val remainder = current.drop(comparison.commands.size)
                if (remainder.isEmpty()) {
                    pending.remove(conversation.value)
                } else {
                    pending.put(conversation.value, PendingCommandsRecord(remainder), null)
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.warn("Cannot clear the commands shown for conversation {}: {}", conversation, e.toString())
            }
        }
    }

    /** Forgets the commands of [conversation], which was sealed. */
    suspend fun sealed(conversation: ConversationId) {
        try {
            pending.remove(conversation.value)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Cannot forget the commands of sealed conversation {}: {}", conversation, e.toString())
        }
    }

    private suspend fun read(conversation: ConversationId): List<PendingCommandRecord> =
        pending.get(conversation.value)?.value?.commands.orEmpty()

    /** [reply] as it is kept: the hidden placeholder, or its text cut to [MAX_REPLY_CHARS] with a note. */
    private fun stored(reply: ReplyRecord): String = when {
        !reply.forModel -> HIDDEN_REPLY
        reply.text.length <= MAX_REPLY_CHARS -> reply.text
        else -> "${reply.text.take(MAX_REPLY_CHARS)} [cut after $MAX_REPLY_CHARS characters]"
    }

    private companion object {
        const val COLLECTION = "pending_commands"
        const val MAX_COMMANDS = 20
        const val MAX_REPLY_CHARS = 2000
    }
}

/** The commands pending for a conversation, with the unstored entry that tells the model about them. */
internal class PendingComparison(val commands: List<PendingCommandRecord>) {
    val notice: UserEntry = commandsEntry(commands)
}

/** The unstored entry that tells the model which of [commands] ran since its last turn. */
internal fun commandsEntry(commands: List<PendingCommandRecord>): UserEntry = UserEntry(
    null,
    listOf(ContextPart(COMMANDS_SOURCE, commandsFraming(commands))),
    UserOrigin.SystemWritten(COMMANDS_SOURCE),
)

/** Commands framing v1: who ran each of [commands] since the model's last turn, and what they were told. */
internal fun commandsFraming(commands: List<PendingCommandRecord>): String = buildString {
    appendLine("[alexandrite:commands]")
    appendLine("$COMMANDS_HEADER since your last turn (they need no answer of their own):")
    for (command in commands) appendLine(commandBullet(command))
    append("[/alexandrite:commands]")
}

/** One line naming who ran [command] and what it told them, its replies indented when it told more than one line. */
private fun commandBullet(command: PendingCommandRecord): String {
    val who = oneLine(command.who).let { if (command.admin) "$it (operator)" else it }
    val action = "ran ${oneLine(command.commandLine).trim()}"
    val replies = command.replies
    val inline = replies.singleOrNull()?.takeIf { lines(it).size == 1 }
    return when {
        replies.isEmpty() -> "- $who $action."

        inline != null -> "- $who $action and was told: \"${Escaper.HEADERS.text(inline)}\""

        else -> buildString {
            append("- $who $action and was told:")
            for (reply in replies) for (line in quotedLines(reply)) append("\n$line")
        }
    }
}

/** [reply], escaped against header forgery and quoted, as the indented lines of a bullet's block form. */
private fun quotedLines(reply: String): List<String> {
    val escaped = lines(Escaper.HEADERS.text(reply))
    return escaped.mapIndexed { index, line ->
        val quote = (if (index == 0) "\"" else "") + line + (if (index == escaped.lastIndex) "\"" else "")
        "  $quote"
    }
}

@Serializable
internal data class PendingCommandsRecord(val commands: List<PendingCommandRecord> = emptyList())

/** [who] ran [commandLine], whether they are [admin], and the text of each of its [replies] as stored. */
@Serializable
internal data class PendingCommandRecord(
    val who: String,
    val admin: Boolean,
    val commandLine: String,
    val replies: List<String> = emptyList(),
)

/** What a reply hidden from the model is recorded as. */
internal const val HIDDEN_REPLY: String = "[a reply hidden from the model]"

private val logger = LoggerFactory.getLogger(PendingCommands::class.java)
