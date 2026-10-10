package org.foedusprogramme.alexandrite.sdk.store

import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin

/**
 * The entries of every conversation, which are never changed once stored.
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
}
