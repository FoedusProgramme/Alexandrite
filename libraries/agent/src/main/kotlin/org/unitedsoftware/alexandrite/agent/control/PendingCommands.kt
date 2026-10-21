package org.unitedsoftware.alexandrite.agent.control

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.agent.prompt.COMMANDS_HEADER
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.lines
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/** The source of the entry that tells the model which commands ran since its last turn. */
internal const val COMMANDS_SOURCE: String = "alexandrite.commands"

/** A reply a command sent, told to the model as [text] when [forModel], else withheld from it. */
internal class ReplyRecord(val text: String, val forModel: Boolean)

/** The commands that ran in each conversation since its last turn, not yet shown to the model. */
@Singleton
@Contribute(ConversationSeals::class, PendingNotes::class)
internal class PendingCommands(records: Records, private val conversations: ConversationStore) :
    Lifecycle,
    ConversationSeals,
    PendingNotes {
    private val pending = PendingRecords(records.collection<PendingCommandsRecord>(COLLECTION), "commands") {
        it.commands.isEmpty()
    }

    override suspend fun onStart() {
        pending.prune(conversations)
    }

    /**
     * Records that [issuer], whom the agent [trusted] or not, ran [commandLine] in [conversation], telling it
     * [replies]; keeps the newest 20. A conversation the command itself sealed (e.g. `/new`) is left alone: it is no
     * longer current for anyone.
     */
    suspend fun record(
        conversation: ConversationId,
        issuer: ChatUser,
        commandLine: String,
        replies: List<ReplyRecord>,
        trusted: Boolean = issuer.isOperator,
    ) {
        val entry =
            PendingCommandRecord(issuer.displayName, issuer.isOperator, commandLine, replies.map(::stored), trusted)
        pending.change(conversation) { current ->
            if (conversations.conversation(conversation)?.state != ConversationState.ACTIVE) return@change current
            PendingCommandsRecord((current?.commands.orEmpty() + entry).takeLast(MAX_COMMANDS))
        }
    }

    override val order: Int = NoteOrder.COMMANDS

    override suspend fun pending(turn: TurnInfo, definitions: List<ToolDefinition>): PendingNote? {
        val comparison = pending(turn.conversation) ?: return null
        val taint = UNTRUSTED.takeIf { comparison.commands.any { !it.trusted } }
        return PendingNote(comparison.notice, taint) { clear(turn.conversation, comparison) }
    }

    /** The commands pending for [conversation], null when there are none or they cannot be read. */
    suspend fun pending(conversation: ConversationId): PendingComparison? =
        pending.get(conversation)?.commands?.let(::PendingComparison)

    /** Clears the commands of [comparison] from [conversation], keeping any recorded since it was read. */
    suspend fun clear(conversation: ConversationId, comparison: PendingComparison) {
        pending.change(conversation) { current ->
            current?.let { PendingCommandsRecord(it.commands.afterShown(comparison.commands)) }
        }
    }

    override suspend fun sealed(conversation: ConversationId) {
        pending.forget(conversation)
    }

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
    val who = oneLine(command.who).let { if (command.operator) "$it (operator)" else it }
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

/** [who] ran [commandLine], whether they are an [operator], and the text of each of its [replies] as stored. */
@Serializable
internal data class PendingCommandRecord(
    val who: String,
    @SerialName("admin") val operator: Boolean,
    val commandLine: String,
    val replies: List<String> = emptyList(),
    /** Whether the agent trusts what the issuer writes. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val trusted: Boolean = operator,
)

/** What a reply hidden from the model is recorded as. */
internal const val HIDDEN_REPLY: String = "[a reply hidden from the model]"

/** Why a command of someone whom the agent does not trust taints the next turn. */
private const val UNTRUSTED = "a command of someone whom the agent does not trust"
