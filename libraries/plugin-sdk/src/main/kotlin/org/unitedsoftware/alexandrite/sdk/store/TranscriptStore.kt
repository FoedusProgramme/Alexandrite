package org.unitedsoftware.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.BoundSpi
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UnknownEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/**
 * The entries of every conversation, which are never changed once stored, except that [deleteMessage] removes them.
 *
 * - Only a store backend implements it, and only the agent calls it.
 * - Every call is atomic, and a call that throws changes nothing.
 * - The store gives each entry its [EntryRecord] as it appends it ([TranscriptEntry.withRecord]), with an [EntryId]
 *   greater than that of every entry it stored before, deleted ones included.
 * - An entry reads back exactly as it was appended, its record added: reasoning seals byte for byte, `Unknown*` values
 *   with their JSON, ids this version does not know as they are.
 * - An entry stored in a form this version cannot read comes back as an [UnknownEntry] with its record.
 * - A conversation takes entries only while it is [ConversationState.ACTIVE].
 * - The store keeps no [InlineMedia]: entries refer to media stored before by [StoredMedia].
 * - An entry keeps the chat addresses it was stored with, also after its chat moved.
 */
@BoundSpi
public interface TranscriptStore {
    /**
     * Stores [entries] in order in the conversation of the turn [turn] and returns them with their records; throws
     * [IllegalStateException] when the conversation takes no entries, and [IllegalArgumentException] when the turn is
     * not recorded or an entry has a record, holds inline media or refers to media the store does not have.
     */
    public suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry>

    /** The entries of [conversation] in the order they were stored. */
    public suspend fun entries(conversation: ConversationId): List<TranscriptEntry>

    /** The entries of [conversation] whose ids are greater than [after], in the order they were stored. */
    public suspend fun entriesAfter(conversation: ConversationId, after: EntryId): List<TranscriptEntry>

    /** The last [count] entries of [conversation] in the order they were stored. */
    public suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry>

    /** The entries stored in the turn [turn], in the order they were stored. */
    public suspend fun turnEntries(turn: TurnId): List<TranscriptEntry>

    /** The entry [id], null when the store has none. */
    public suspend fun entry(id: EntryId): TranscriptEntry?

    /** The [UserEntry]s whose [UserOrigin.FromChat] names [message], in the order they were stored. */
    public suspend fun entries(message: ChannelMessageRef): List<TranscriptEntry>

    /**
     * Deletes the entries of [message] and the stored media that no other entry refers to, at the user's request, and
     * returns how many entries.
     */
    public suspend fun deleteMessage(message: ChannelMessageRef): Int

    /**
     * The stored entries of every conversation of [keys] whose [searchText] holds each of the [searchWords] of [query],
     * ignoring case, the best matches first and at most [limit] of them; empty for a store that does not search.
     * Throws [IllegalArgumentException] when [limit] is less than 1.
     */
    public suspend fun search(query: String, keys: Set<AgentChatKey>, limit: Int): List<TranscriptMatch> {
        require(limit >= 1) { "A search finds at least one entry, was $limit." }
        return emptyList()
    }
}

/** An entry that [TranscriptStore.search] found in a conversation of [key]. */
@Poko
public class TranscriptMatch(
    public val entry: TranscriptEntry,
    public val key: AgentChatKey,
    /** A part of the entry's [searchText] around a match. */
    public val snippet: String,
)

/** The words that [TranscriptStore.search] looks for in [query]. */
public fun searchWords(query: String): List<String> = query.split(WHITESPACE).filter { it.isNotEmpty() }

/**
 * The text of this entry that [TranscriptStore.search] looks through: the text parts of what a person or a plugin
 * wrote, of a model's answers and of tool results, one per line.
 */
public fun TranscriptEntry.searchText(): String {
    val parts = when (this) {
        is UserEntry -> parts.takeIf { origin is UserOrigin.FromChat || origin is UserOrigin.Initiated }.orEmpty()
        is AssistantEntry -> parts
        is ToolResultEntry -> content
        else -> emptyList()
    }
    return parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }
}

private val WHITESPACE = Regex("\\s+")
